package byx.service.migration;

/** Linha da tabela {@code users} do painel legado (somente em memória durante a migração; toString redigido). */
record LegacyUser(long id, String username, String email, String passwordHash, String role, String status, String phone, boolean emailVerified, boolean phoneVerified,
        boolean mustChangePassword, String createdAt, String updatedAt, String lastLoginAt) {
    @Override
    public String toString() {
        return "LegacyUser[" + id + "]";
    }
}
