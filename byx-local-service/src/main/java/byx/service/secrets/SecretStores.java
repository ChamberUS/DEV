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
