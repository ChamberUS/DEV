package panel.model;

public record WalletChallenge(String nonce, long userId, String address, String chainId,
        String genesisFingerprint, String issuedAt, String expiresAt, String context) { }
