package byx.service.auth;

/**
 * Conta na autoridade. id = 128 bits aleatórios (hex), nunca reutilizado; username normalizado (minúsculo). credentialVersion sobe a cada
 * mudança de credencial/segurança e invalida sessões antigas. passwordHash é um verificador PHC (nunca a senha). toString não imprime nada além do id.
 * Campos de migração/2º fator: legacyUserId (identidade estável do banco legado; 0 = conta nova), email/telefone (destino do 2º fator; só dentro do
 * snapshot cifrado), flags de verificação, troca de senha obrigatória e último login.
 */
public record Account(String id, String username, Role role, boolean enabled, long credentialVersion, String passwordHash, long createdAtMs, long legacyUserId, String email,
        String phone, boolean emailVerified, boolean phoneVerified, boolean mustChangePassword, long lastLoginAtMs) {
    /** Conta sem dados legados (provisionamento de teste). */
    public Account(String id, String username, Role role, boolean enabled, long credentialVersion, String passwordHash, long createdAtMs) {
        this(id, username, role, enabled, credentialVersion, passwordHash, createdAtMs, 0, null, null, false, false, false, 0);
    }

    public Account withRole(Role r) {
        return new Account(id, username, r, enabled, credentialVersion + 1, passwordHash, createdAtMs, legacyUserId, email, phone, emailVerified, phoneVerified, mustChangePassword, lastLoginAtMs);
    }

    public Account withEnabled(boolean e) {
        return new Account(id, username, role, e, credentialVersion + 1, passwordHash, createdAtMs, legacyUserId, email, phone, emailVerified, phoneVerified, mustChangePassword, lastLoginAtMs);
    }

    public Account withPassword(String hash) {
        return new Account(id, username, role, enabled, credentialVersion + 1, hash, createdAtMs, legacyUserId, email, phone, emailVerified, phoneVerified, false, lastLoginAtMs);
    }

    public Account withCredentialBump() {
        return new Account(id, username, role, enabled, credentialVersion + 1, passwordHash, createdAtMs, legacyUserId, email, phone, emailVerified, phoneVerified, mustChangePassword, lastLoginAtMs);
    }

    public Account withLastLogin(long ms) {
        return new Account(id, username, role, enabled, credentialVersion, passwordHash, createdAtMs, legacyUserId, email, phone, emailVerified, phoneVerified, mustChangePassword, ms);
    }

    @Override
    public String toString() {
        return "Account[" + id + "]";
    }
}
