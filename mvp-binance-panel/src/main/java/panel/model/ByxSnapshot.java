package panel.model;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.time.Instant;

public record ByxSnapshot(String source, String environment, String connection, String identity,
        String freshness, Boolean syncing, String chainId, String height, Instant blockTime,
        String address, BigInteger balance, String denom, int decimals, Instant updatedAt, String message, String chainState) {
    /** Construtor legado (estado da chain não informado pelo serviço). */
    public ByxSnapshot(String source, String environment, String connection, String identity,
            String freshness, Boolean syncing, String chainId, String height, Instant blockTime,
            String address, BigInteger balance, String denom, int decimals, Instant updatedAt, String message) {
        this(source, environment, connection, identity, freshness, syncing, chainId, height, blockTime, address, balance, denom, decimals, updatedAt, message, null);
    }

    public String execution() { return "DISABLED"; }
    public String formattedBalance() {
        return balance == null ? "UNKNOWN" : new BigDecimal(balance, decimals).toPlainString() + " " + denom;
    }
    public static ByxSnapshot unknown(String source, String environment, String connection, String message) {
        return new ByxSnapshot(source, environment, connection, "UNVERIFIED", "UNKNOWN", null,
                null, null, null, null, null, null, 0, null, message);
    }
    public ByxSnapshot stale(String connection, String message) {
        return new ByxSnapshot(source, environment, connection, identity, "STALE", syncing, chainId,
                height, blockTime, address, balance, denom, decimals, updatedAt, message, chainState == null ? null : "STALE");
    }
}
