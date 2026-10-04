package panel.model;

import java.math.*;
import java.time.Instant;
import java.util.Objects;

public record TreasuryAsset(Category category, String asset, String networkCustodian, String baseUnit,
        BigInteger balance, int decimals, Source source, Instant asOf, Verification verification, boolean testOnly) {
    public enum Category { OPERATING_CASH, STABLECOIN_RESERVE, BYX_HOLDINGS, BOT_CAPITAL, LIQUIDITY_ALLOCATION, GAS_SPONSORSHIP_BUDGET }
    public enum Source { ON_CHAIN, EXTERNAL_CUSTODY, PAPER, MANUAL_UNVERIFIED }
    public enum Verification { VERIFIED, UNVERIFIED, STALE }
    public TreasuryAsset {
        Objects.requireNonNull(category); Objects.requireNonNull(source); Objects.requireNonNull(verification);
        Objects.requireNonNull(asset); Objects.requireNonNull(networkCustodian); Objects.requireNonNull(baseUnit);
        Objects.requireNonNull(balance); Objects.requireNonNull(asOf);
        if(balance.signum()<0 || decimals<0 || decimals>18) throw new IllegalArgumentException("Exact nonnegative units required");
        if((source==Source.MANUAL_UNVERIFIED || source==Source.PAPER) && verification==Verification.VERIFIED)
            throw new IllegalArgumentException("Manual/paper entries cannot be verified reserves");
    }
    public String formatted() { return new BigDecimal(balance,decimals).toPlainString()+" "+asset; }
}
