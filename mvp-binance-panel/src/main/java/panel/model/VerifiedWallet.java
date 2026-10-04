package panel.model;

import java.time.Instant;

public record VerifiedWallet(long userId, String address, String publicKey, String chainId,
        String genesisFingerprint, Instant verifiedAt, Instant lastVerifiedAt, Instant revokedAt) {
    public boolean validAt(Instant now) {
        return revokedAt == null && !lastVerifiedAt.isAfter(now) && now.isBefore(lastVerifiedAt.plusSeconds(86400));
    }
}
