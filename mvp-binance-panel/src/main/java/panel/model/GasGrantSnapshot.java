package panel.model;

import java.math.BigInteger;
import java.time.Instant;

public record GasGrantSnapshot(String granter, String grantee, String denom,
        BigInteger spendLimit, BigInteger remaining, Instant expiration, String state,
        String txHash, Instant updatedAt) {
    public BigInteger consumed() { return spendLimit.subtract(remaining); }
    public boolean activeAt(Instant now) { return "ACTIVE".equals(state) && expiration.isAfter(now) && remaining.signum() > 0; }
}
