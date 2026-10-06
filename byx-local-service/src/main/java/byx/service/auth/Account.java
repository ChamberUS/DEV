package byx.service.auth;

/**
 * Conta na autoridade. id = 128 bits aleatórios (hex), nunca reutilizado; username normalizado (minúsculo). credentialVersion sobe a cada
 * mudança de credencial/segurança e invalida sessões antigas. passwordHash é um verificador PHC (nunca a senha). toString não imprime o hash.
 */
public record Account(String id, String username, Role role, boolean enabled, long credentialVersion, String passwordHash, long createdAtMs) {
    @Override
    public String toString() {
        return "Account[" + id + "]";
    }
}
