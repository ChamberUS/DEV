package byx.service.auth;

/** Sessão em MEMÓRIA do serviço (reiniciar o serviço invalida todas). Nunca guarda o token bruto; toString redigido. */
final class Session {
    final String id; // SHA-256 do token (hex)
    final String accountId;
    final long createdAtMs;
    final long peerKey;
    volatile long lastSeenMs;
    volatile long credentialVersion;
    volatile long mfaAtMs = -1;
    volatile long elevatedUntilMs = -1;
    volatile boolean revoked;

    Session(String id, String accountId, long createdAtMs, long peerKey, long credentialVersion) {
        this.id = id;
        this.accountId = accountId;
        this.createdAtMs = createdAtMs;
        this.lastSeenMs = createdAtMs;
        this.peerKey = peerKey;
        this.credentialVersion = credentialVersion;
    }

    @Override
    public String toString() {
        return "Session[redacted]";
    }
}
