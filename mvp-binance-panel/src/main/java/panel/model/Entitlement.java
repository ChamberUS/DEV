package panel.model;

import java.time.Instant;

public record Entitlement(String id, String displayName, String requiredTier,
        boolean enabled, String source, Instant expiresAt, Status status,
        Instant startsAt, String paymentIntentId, String txHash) {
    public Entitlement(String id,String displayName,String requiredTier,boolean enabled,String source,Instant expiresAt,Status status) {
        this(id,displayName,requiredTier,enabled,source,expiresAt,status,null,null,null);
    }
    public enum Status { UNLOCKED, LOCKED, UNAVAILABLE }
}
