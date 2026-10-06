package byx.service.secrets;

/**
 * Identificadores TIPADOS dos segredos do produto. Nunca vêm da UI nem de IPC (não existe operação de segredo no protocolo local): o
 * nome do item no Keychain deriva só daqui. Nesta fase SOMENTE {@link #TEST_CANARY} é utilizável; os demais existem para o contrato
 * futuro e são recusados ({@code NOT_CONFIGURED}) até que o ID final do produto e o provisioning existam.
 */
public enum SecretId {
    /** Canário de teste (valor aleatório, apagado ao fim). Único utilizável. */
    TEST_CANARY("test-canary", true),
    /** Âncora de integridade do armazenamento de autoridade (chave MAC + versão monotônica + MAC do estado). SÓ teste nesta fase (namespace de teste). */
    AUTHORITY_TEST_ANCHOR("authority-test-anchor", true),
    /** Chave AEAD (AES-256, 32 bytes aleatórios) que cifra o snapshot da autoridade; INDEPENDENTE da chave MAC da âncora. SÓ teste nesta fase. */
    AUTHORITY_TEST_ENCRYPTION_KEY("authority-test-encryption-key", true),
    RESEND_API_KEY("resend-api-key", false),
    TWILIO_API_SECRET("twilio-api-secret", false),
    TRUSTED_DEVICE_MASTER_KEY("trusted-device-master-key", false),
    BINANCE_READONLY_CREDENTIAL("binance-readonly-credential", false);

    private final String wire;
    private final boolean usable;

    SecretId(String wire, boolean usable) {
        this.wire = wire;
        this.usable = usable;
    }

    /** Nome fixo e não secreto do item (parte do atributo de serviço do Keychain). */
    public String wireName() {
        return wire;
    }

    public boolean usable() {
        return usable;
    }
}
