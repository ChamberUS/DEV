package byx.service.secrets;

/**
 * Identificadores TIPADOS dos segredos do produto. Nunca vêm da UI nem de IPC (não existe operação de segredo no protocolo local): o
 * nome do item no Keychain deriva só daqui. Cada id tem um ESCOPO fixo: itens TEST ficam no namespace de teste ({@code invalid.byx-canary-test/…}) e
 * itens PRODUCTION no namespace do serviço final ({@code com.buynnex.byx.service/…}); um armazenamento com escopo recusa o escopo oposto
 * ({@link ScopedSecretStore}), de modo que QA nunca abre, sobrescreve ou apaga a autoridade real e a produção nunca usa itens de teste.
 */
public enum SecretId {
    // ---- TESTE (QA, canário, testes de migração com dados sintéticos) -----------------------------------------------------------------
    /** Canário de teste (valor aleatório, apagado ao fim). */
    TEST_CANARY("test-canary", Scope.TEST),
    /** Âncora de rollback da autoridade de TESTE (chave MAC + versão monotônica + MAC do estado). */
    AUTHORITY_TEST_ANCHOR("authority-test-anchor", Scope.TEST),
    /** Chave AEAD (AES-256) do snapshot da autoridade de TESTE; independente da chave MAC. */
    AUTHORITY_TEST_ENCRYPTION_KEY("authority-test-encryption-key", Scope.TEST),
    WALLET_LIFECYCLE_TEST_ANCHOR("wallet-lifecycle-test-anchor", Scope.TEST),
    WALLET_LIFECYCLE_TEST_ENCRYPTION_KEY("wallet-lifecycle-test-encryption-key", Scope.TEST),
    /** Destinos de TESTE da migração de segredos de provedor (dados sintéticos). */
    MIGRATION_TEST_RESEND("migration-test-resend", Scope.TEST),
    MIGRATION_TEST_TWILIO("migration-test-twilio", Scope.TEST),
    // ---- PRODUÇÃO (só o serviço/migrador assinados; nunca QA) --------------------------------------------------------------------------
    /** Chave AEAD (AES-256, SecureRandom) do snapshot da autoridade REAL. Nunca reutiliza chave de teste. */
    AUTHORITY_ENCRYPTION_KEY("authority-encryption-key", Scope.PRODUCTION),
    /** Âncora de rollback da autoridade REAL. */
    AUTHORITY_ROLLBACK_ANCHOR("authority-rollback-anchor", Scope.PRODUCTION),
    RESEND_API_KEY("resend-api-key", Scope.PRODUCTION),
    TWILIO_API_SECRET("twilio-api-secret", Scope.PRODUCTION),
    // ---- ainda sem uso ------------------------------------------------------------------------------------------------------------------------
    TRUSTED_DEVICE_MASTER_KEY("trusted-device-master-key", Scope.UNUSABLE),
    BINANCE_READONLY_CREDENTIAL("binance-readonly-credential", Scope.UNUSABLE);

    public enum Scope { TEST, PRODUCTION, UNUSABLE }

    private final String wire;
    private final Scope scope;

    SecretId(String wire, Scope scope) {
        this.wire = wire;
        this.scope = scope;
    }

    /** Nome fixo e não secreto do item (parte do atributo de serviço do Keychain). */
    public String wireName() {
        return wire;
    }

    public Scope scope() {
        return scope;
    }

    public boolean usable() {
        return scope != Scope.UNUSABLE;
    }
}
