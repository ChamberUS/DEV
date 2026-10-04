package panel.model;

public record WalletProof(WalletChallenge challenge, String publicKey, String signature) { }
