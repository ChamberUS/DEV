package byx.service.secrets;

/** Fábrica do armazenamento do sistema: SecItem (proteção de dados) validado. Sem fallback: se o Keychain moderno não estiver disponível, falha com NOT_CONFIGURED. */
public final class SecretStores {
    private SecretStores() {
    }

    public static SecretStore system() {
        try {
            return new ValidatedSecretStore(new SecItemSecretStore());
        } catch (RuntimeException | LinkageError e) {
            return new ValidatedSecretStore(new UnavailableSecretStore());
        }
    }

    /** Armazenamento de TESTE/QA: só itens do escopo TEST (incapaz de tocar a autoridade ou os segredos reais). */
    public static SecretStore test() {
        return new ScopedSecretStore(system(), SecretId.Scope.TEST);
    }

    /** Armazenamento de PRODUÇÃO: só itens do escopo PRODUCTION (incapaz de tocar itens de teste). */
    public static SecretStore production() {
        return new ScopedSecretStore(system(), SecretId.Scope.PRODUCTION);
    }

    /** Sem macOS/JNA: tudo NOT_CONFIGURED (nunca texto puro). */
    private static final class UnavailableSecretStore implements SecretStore {
        public SecretStatus status() {
            return SecretStatus.NOT_CONFIGURED;
        }

        public void write(SecretId id, SecretBytes v) throws SecretStoreException {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }

        public boolean update(SecretId id, SecretBytes v) throws SecretStoreException {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }

        public java.util.Optional<SecretBytes> read(SecretId id) throws SecretStoreException {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }

        public boolean delete(SecretId id) throws SecretStoreException {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }
    }
}
