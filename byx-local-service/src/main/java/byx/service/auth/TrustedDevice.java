package byx.service.auth;

/**
 * Dispositivo confiável (modelo do serviço): registro REVOGÁVEL e com validade, ligado a UMA conta; "dispositivo" = esta instalação (o cofre e a
 * autoridade vivem só neste Mac, e só o app assinado verificado fala com o serviço). Nunca hostname/MAC, nunca um booleano permanente: expira,
 * é revogado em troca de senha/papel/habilitação e a pedido. Só reduz o 2º fator para elevação; nunca substitui a senha.
 */
public record TrustedDevice(String id, String accountId, long createdAtMs, long lastUsedAtMs, long expiresAtMs, long revokedAtMs) {
    public boolean activeAt(long nowMs) {
        return revokedAtMs == 0 && nowMs < expiresAtMs;
    }

    public String status(long nowMs) {
        return nowMs >= expiresAtMs ? "EXPIRED" : revokedAtMs != 0 ? "REVOKED" : "ACTIVE";
    }
}
