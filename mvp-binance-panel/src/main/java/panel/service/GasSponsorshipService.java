package panel.service;

import java.time.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;
import panel.auth.SessionManager;
import panel.model.*;
import panel.adapter.ByxGasGrantGateway;
import panel.repository.GasGrantRepository;
import panel.security.AccessDeniedException;

public final class GasSponsorshipService {
    private final SessionManager sessions;
    private final ByxWalletIdentityService identity;
    private final ByxBenefitsService benefits;
    private final GasGrantRepository repository;
    private final ByxGasGrantGateway gateway;
    private final Supplier<GasSponsorshipPolicy> policy;
    private final Clock clock;
    public GasSponsorshipService(SessionManager sessions, ByxWalletIdentityService identity, ByxBenefitsService benefits,
            GasGrantRepository repository, ByxGasGrantGateway gateway, Supplier<GasSponsorshipPolicy> policy, Clock clock) {
        this.sessions=sessions; this.identity=identity; this.benefits=benefits; this.repository=repository; this.gateway=gateway; this.policy=policy; this.clock=clock;
    }
    private long user() { return sessions.user().filter(s -> s.user().active() && !s.user().mustChangePassword()).orElseThrow(() -> new AccessDeniedException("Authenticated user required")).user().id(); }
    public synchronized GasGrantSnapshot request(String address) throws Exception {
        var session=sessions.user().orElseThrow();
        var wallet=identity.verified(address).orElseThrow(() -> new AccessDeniedException("Verified wallet required"));
        benefits.refresh(address).get();
        synchronized (sessions) {
            if (!sessions.user().orElseThrow().id().equals(session.id())) throw new AccessDeniedException("Session changed");
            long u=user(); var c=identity.network(); var p=policy.get(); p.matches(c);
            var b=benefits.snapshot(address);
            if (!wallet.equals(identity.verified(address).orElse(null)) || !"ONLINE/FRESH".equals(b.chainState()) || !"VERIFIED".equals(b.walletStatus())) throw new AccessDeniedException("Fresh verified chain required");
            BigInteger quota=p.quotas().getOrDefault(b.tier(),BigInteger.ZERO);
            var own=repository.own(u,address,p.chainId(),p.genesis());
            var existing=gateway.read(c,p,address);
            if (quota.signum()==0) {
                if (own.isPresent() && existing.isPresent()) revoke(address);
                throw new AccessDeniedException("No sponsorship at current tier");
            }
            if (own.isPresent()) {
                var e=own.get();
                if (existing.isPresent() && existing.get().activeAt(clock.instant()) && e.state().equals("ACTIVE")) {
                    var grant=bind(e,existing.get());
                    if (e.limit().compareTo(quota)>0) { revoke(address); throw new AccessDeniedException("Downgrade: grant revoked"); }
                    return grant;
                }
                throw new AccessDeniedException("V1 lifetime quota used or pending; no replenishment");
            }
            if (existing.isPresent()) throw new AccessDeniedException("Existing unmanaged allowance; refusing overwrite");
            var e=new GasGrantRepository.Entry(u,address,p.chainId(),p.genesis(),p.granter(),quota,clock.instant().plusSeconds(p.validitySeconds()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),"PENDING","",quota);
            repository.claim(e);
            String tx=gateway.grant(c,p,address,quota,e.expiration());
            var grant=bind(e,gateway.read(c,p,address).orElseThrow(() -> new IllegalStateException("Grant not confirmed")));
            if (!grant.activeAt(clock.instant()) || !grant.remaining().equals(quota)) throw new IllegalStateException("Invalid new allowance");
            repository.update(e,"ACTIVE",tx);
            return new GasGrantSnapshot(grant.granter(),address,"ubyx",quota,grant.remaining(),grant.expiration(),"ACTIVE",tx,clock.instant());
        }
    }
    private GasGrantSnapshot bind(GasGrantRepository.Entry e, GasGrantSnapshot g) {
        if (!e.granter().equals(g.granter()) || !e.address().equals(g.grantee()) || !"ubyx".equals(g.denom())
                || !e.expiration().equals(g.expiration()) || g.remaining().signum()<0 || g.remaining().compareTo(e.limit())>0)
            throw new AccessDeniedException("Allowance does not match bounded journal");
        return new GasGrantSnapshot(g.granter(),g.grantee(),g.denom(),e.limit(),g.remaining(),g.expiration(),g.state(),e.txHash(),g.updatedAt());
    }
    public synchronized GasGrantSnapshot refresh(String address) throws Exception {
        if (identity.verified(address).isPresent()) benefits.refresh(address).get();
        synchronized (sessions) {
            long u=user(); var c=identity.network(); var p=policy.get(); p.matches(c);
            var e=repository.own(u,address,p.chainId(),p.genesis()).orElseThrow(() -> new AccessDeniedException("No own grant"));
            var g=gateway.read(c,p,address);
            if (g.isEmpty()) return new GasGrantSnapshot(e.granter(),address,"ubyx",e.limit(),e.remaining(),e.expiration(),e.state().equals("REVOKED")?"REVOKED":"EXPIRED/EXHAUSTED/ABSENT",e.txHash(),clock.instant());
            var result=bind(e,g.get()); repository.observed(e,result.remaining());
            boolean valid=identity.verified(address).isPresent();
            var b=valid?benefits.snapshot(address):null;
            boolean fresh=b!=null && "ONLINE/FRESH".equals(b.chainState());
            if (!valid || (fresh && p.quotas().getOrDefault(b.tier(),BigInteger.ZERO).compareTo(e.limit())<0)) {
                revoke(address); return new GasGrantSnapshot(e.granter(),address,"ubyx",e.limit(),result.remaining(),e.expiration(),"REVOKED",result.txHash(),clock.instant());
            }
            return result;
        }
    }
    public synchronized void revoke(String address) throws Exception {
        synchronized (sessions) {
            long u=user(); var c=identity.network(); var p=policy.get(); p.matches(c);
            var e=repository.own(u,address,p.chainId(),p.genesis()).orElseThrow(() -> new AccessDeniedException("No own grant"));
            var existing=gateway.read(c,p,address);
            if (existing.isPresent()) {
                repository.observed(e,bind(e,existing.get()).remaining());
                String tx=gateway.revoke(c,p,address);
                if (gateway.read(c,p,address).isPresent()) throw new IllegalStateException("Revocation not confirmed");
                repository.update(e,"REVOKED",tx);
            } else repository.update(e,"REVOKED",e.txHash());
        }
    }
    public List<GasGrantRepository.Entry> journal() { user(); return repository.all(); }
}
