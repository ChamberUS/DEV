package panel.service;

import panel.auth.SessionManager;
import panel.security.AccessDeniedException;
import panel.model.*;
import panel.adapter.ByxPaymentVerifier;
import panel.repository.ByxPaymentRepository;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;
import java.util.function.Supplier;

public final class ByxPaymentService {
    public static final String PURPOSE="advanced_analytics_test";
    private final SessionManager sessions;
    private final ByxWalletIdentityService identity;
    private final ByxPaymentRepository repository;
    private final ByxPaymentVerifier verifier;
    private final Clock clock;
    private final Supplier<ByxPaymentPolicy> policy;
    public ByxPaymentService(SessionManager sessions,ByxWalletIdentityService identity,ByxPaymentRepository repository,ByxPaymentVerifier verifier,Clock clock,Supplier<ByxPaymentPolicy> policy){this.sessions=sessions;this.identity=identity;this.repository=repository;this.verifier=verifier;this.clock=clock;this.policy=policy;}
    private long user(){return sessions.user().filter(s->s.user().active()&&!s.user().mustChangePassword()).orElseThrow(()->new AccessDeniedException("Authenticated user required")).user().id();}
    public PaymentIntent create(String address){
        long user=user();var config=identity.network();var p=policy.get();p.check(config);
        var wallet=identity.verified(address).orElseThrow(()->new AccessDeniedException("Verified wallet required"));
        if(wallet.address().equals(p.recipient()))throw new IllegalArgumentException("Service wallet cannot pay itself");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);Instant now=clock.instant();
        var i=new PaymentIntent(HexFormat.of().formatHex(random),user,wallet.address(),wallet.chainId(),wallet.genesisFingerprint(),p.recipient(),p.amountUbyx(),PURPOSE,now,now.plusSeconds(p.intentSeconds()),PaymentIntent.Status.CREATED);
        repository.create(i);repository.status(i,PaymentIntent.Status.AWAITING_PAYMENT);return i.withStatus(PaymentIntent.Status.AWAITING_PAYMENT);
    }
    public PaymentIntent get(String id){var i=repository.get(user(),id);if(Set.of(PaymentIntent.Status.CREATED,PaymentIntent.Status.AWAITING_PAYMENT,PaymentIntent.Status.CONFIRMING).contains(i.status())&&!clock.instant().isBefore(i.expiresAt())){repository.status(i,PaymentIntent.Status.EXPIRED);return i.withStatus(PaymentIntent.Status.EXPIRED);}return i;}
    public PaymentReceipt confirm(String id,String hash) throws Exception {
        var i=get(id);long user=user();var session=sessions.user().orElseThrow().id();
        if(!Set.of(PaymentIntent.Status.CREATED,PaymentIntent.Status.AWAITING_PAYMENT).contains(i.status()))throw new AccessDeniedException("Intent unavailable or replay");
        if(hash==null||!hash.matches("[A-Fa-f0-9]{64}"))throw new IllegalArgumentException("Transaction hash required");hash=hash.toUpperCase(Locale.ROOT);
        var wallet=identity.verified(i.verifiedWallet()).orElseThrow(()->new AccessDeniedException("Verified wallet required"));
        var config=identity.network();var p=policy.get();p.check(config);
        if(!i.chainId().equals(config.expectedChainId())||!i.genesisFingerprint().equals(config.genesisFingerprint())||!i.recipient().equals(p.recipient())||!i.amountUbyx().equals(p.amountUbyx()))throw new AccessDeniedException("Intent policy changed");
        repository.claim(i);
        try {
            var r=verifier.verify(config,i,hash,p.passSeconds());
            if(user()!=user||!sessions.user().orElseThrow().id().equals(session)||identity.verified(wallet.address()).filter(wallet::equals).isEmpty()||!clock.instant().isBefore(i.expiresAt()))throw new AccessDeniedException("Session, wallet or intent expired");
            if(!r.paymentIntentId().equals(i.id())||r.userId()!=user||!r.wallet().equals(i.verifiedWallet())||!r.txHash().equals(hash)||!r.chainId().equals(i.chainId())||!r.genesisFingerprint().equals(i.genesisFingerprint())||!r.amountUbyx().equals(i.amountUbyx())||r.height()<1)throw new IllegalArgumentException("Invalid verifier receipt");
            if(r.startsAt().isAfter(clock.instant())||r.startsAt().isBefore(i.createdAt())||!r.expiresAt().equals(r.startsAt().plusSeconds(p.passSeconds()))
                    ||r.confirmedAt().isBefore(i.createdAt().minusSeconds(30))||!r.confirmedAt().isBefore(i.expiresAt()))throw new IllegalArgumentException("Invalid receipt times");
            repository.consume(i,r);return r;
        }catch(java.io.IOException e){repository.status(i,PaymentIntent.Status.AWAITING_PAYMENT);throw e;}
        catch(Exception e){repository.status(i,PaymentIntent.Status.REJECTED);throw e;}
    }
    public List<PaymentReceipt> receipts(){return repository.receipts(user());}
    public Optional<PaymentReceipt> active(String address){
        if(identity.verified(address).isEmpty())return Optional.empty();var c=identity.network();Instant now=clock.instant();
        return receipts().stream().filter(r->r.wallet().equals(address)&&r.chainId().equals(c.expectedChainId())&&r.genesisFingerprint().equals(c.genesisFingerprint())&&!now.isBefore(r.startsAt())&&now.isBefore(r.expiresAt())).findFirst();
    }
}
