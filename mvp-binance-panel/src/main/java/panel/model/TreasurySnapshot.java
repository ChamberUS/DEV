package panel.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public record TreasurySnapshot(List<TreasuryAsset> assets, String realAssets, String chainState,
        BigInteger sponsorshipBudgetUbyx, int activeGrants, BigInteger observedConsumptionUbyx, String consumptionStatus, Instant asOf) {
    public TreasurySnapshot { assets=List.copyOf(assets); }
    public record Unit(TreasuryAsset.Category category,String asset,String networkCustodian,String baseUnit,int decimals,boolean testOnly) { }
    /** Separate units/categories only; no heterogeneous valuation or implicit FX. */
    public Map<Unit,BigInteger> verifiedBalances() {
        var out=new LinkedHashMap<Unit,BigInteger>();
        for(var a:assets) if(a.verification()==TreasuryAsset.Verification.VERIFIED && a.source()!=TreasuryAsset.Source.MANUAL_UNVERIFIED && a.source()!=TreasuryAsset.Source.PAPER
                && !a.asOf().isAfter(asOf) && a.asOf().isAfter(asOf.minusSeconds(60)))
            out.merge(new Unit(a.category(),a.asset(),a.networkCustodian(),a.baseUnit(),a.decimals(),a.testOnly()),a.balance(),BigInteger::add);
        return Collections.unmodifiableMap(out);
    }
}
