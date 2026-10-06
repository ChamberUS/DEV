package byx.service.secrets;

import java.util.Optional;

/**
 * Restringe um armazenamento a UM escopo ({@link SecretId.Scope}). QA/teste usa {@code TEST} e é incapaz de abrir, sobrescrever, apagar ou até ler um item
 * de produção; a produção usa {@code PRODUCTION} e nunca toca um item de teste. A recusa é {@code NOT_CONFIGURED} antes de qualquer chamada ao backend.
 */
public final class ScopedSecretStore implements SecretStore {
    private final SecretStore backend;
    private final SecretId.Scope scope;

    public ScopedSecretStore(SecretStore backend, SecretId.Scope scope) {
        if (scope == SecretId.Scope.UNUSABLE) {
            throw new IllegalArgumentException("scope");
        }
        this.backend = backend;
        this.scope = scope;
    }

    public SecretId.Scope scope() {
        return scope;
    }

    private void check(SecretId id) throws SecretStoreException {
        if (id == null || id.scope() != scope) {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }
    }

    @Override
    public SecretStatus status() {
        return backend.status();
    }

    @Override
    public void write(SecretId id, SecretBytes value) throws SecretStoreException {
        check(id);
        backend.write(id, value);
    }

    @Override
    public boolean update(SecretId id, SecretBytes value) throws SecretStoreException {
        check(id);
        return backend.update(id, value);
    }

    @Override
    public Optional<SecretBytes> read(SecretId id) throws SecretStoreException {
        check(id);
        return backend.read(id);
    }

    @Override
    public boolean delete(SecretId id) throws SecretStoreException {
        check(id);
        return backend.delete(id);
    }
}
