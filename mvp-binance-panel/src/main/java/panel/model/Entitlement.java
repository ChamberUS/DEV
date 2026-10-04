package panel.model;

import java.time.Instant;

public record Entitlement(String id, String displayName, String requiredTier,
        boolean enabled, String source, Instant expiresAt, Status status) {
    public enum Status { UNLOCKED, LOCKED, UNAVAILABLE }
}
