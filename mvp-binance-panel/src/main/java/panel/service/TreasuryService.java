package panel.service;

import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import panel.adapter.*;
import panel.model.*;
import panel.repository.GasGrantRepository;

public final class TreasuryService {
    private final ByxWalletIdentityService identity;
    private final ByxChainGateway chain;
    private final ByxGasGrantGateway gas;
    private final GasGrantRepository journal;
    private final Supplier<GasSponsorshipPolicy> policy;
    private final Clock clock;
    public TreasuryService(ByxWalletIdentityService identity,ByxChainGateway chain,ByxGasGrantGateway gas,GasGrantRepository journal,Supplier<GasSponsorshipPolicy> policy,Clock clock) {
        this.identity=identity;this.chain=chain;this.gas=gas;this.journal=journal;this.policy=policy;this.clock=clock;
    }
    public TreasurySnapshot refresh() throws Exception {
        var c=identity.network(); var p=policy.get(); p.matches(c);
        var query=new ByxConfig(c.endpoint(),c.rpcEndpoint(),"LOCALNET",c.expectedChainId(),c.genesisFingerprint(),"ubyx","BYX",6,"BANK_METADATA",p.granter());
        var s=chain.read(query); var now=clock.instant();
        boolean trusted="LIVE_NODE".equals(s.source()) && "LOCALNET".equals(s.environment()) && "ONLINE".equals(s.connection()) && "VERIFIED".equals(s.identity())
                && "FRESH".equals(s.freshness()) && p.chainId().equals(s.chainId()) && p.granter().equals(s.address()) && Boolean.FALSE.equals(s.syncing())
                && s.updatedAt()!=null && !s.updatedAt().isAfter(now) && s.updatedAt().isAfter(now.minusSeconds(60)) && s.blockTime()!=null
                && !s.blockTime().isAfter(now.plusSeconds(30)) && s.blockTime().isAfter(now.minusSeconds(60)) && s.balance()!=null && "BYX".equals(s.denom()) && s.decimals()==6;
        if(!trusted) throw new java.io.IOException("Treasury CHAIN OFFLINE/STALE; no verified balance");
        // The gas budget is this account's spendable balance, displayed once in its own category.
        var asset=new TreasuryAsset(TreasuryAsset.Category.GAS_SPONSORSHIP_BUDGET,"BYX",p.chainId()+"/"+p.granter(),"ubyx",s.balance(),6,TreasuryAsset.Source.ON_CHAIN,s.updatedAt(),TreasuryAsset.Verification.VERIFIED,true);
        int active=0;BigInteger consumed=BigInteger.ZERO;boolean complete=true;
        for(var e:journal.all()) if(e.chain().equals(p.chainId()) && e.genesis().equals(p.genesis()) && e.granter().equals(p.granter())) {
            var live=gas.read(c,p,e.address());
            if(live.isPresent()) {
                var g=live.get();
                if(!g.expiration().equals(e.expiration()) || g.remaining().compareTo(e.limit())>0 || g.remaining().signum()<0) throw new java.io.IOException("Grant journal mismatch");
                journal.observed(e,g.remaining());consumed=consumed.add(e.limit().subtract(g.remaining()));
                if(g.activeAt(now)) active++;
            } else { consumed=consumed.add(e.limit().subtract(e.remaining())); complete=false; }
        }
        return new TreasurySnapshot(List.of(asset),"NONE / NOT CONFIGURED","ONLINE/FRESH",s.balance(),active,consumed,
                complete?"Observed native allowance consumption; grant/revoke gas excluded":"Last observed only; absent/revoked allowances require transaction reconciliation",now);
    }
}
