package panel.model;

import java.math.*;
import java.time.Instant;
import java.util.List;

public record BenefitsSnapshot(String walletStatus, String address, BigInteger balanceUbyx, String tier,
        boolean benefitsEnabled, Instant lastChainUpdate, String chainState, List<String> availableBenefits, String nextTier) {
    public String formattedBalance() { return balanceUbyx == null ? "UNKNOWN" : new BigDecimal(balanceUbyx, 6).toPlainString() + " BYX"; }
}
