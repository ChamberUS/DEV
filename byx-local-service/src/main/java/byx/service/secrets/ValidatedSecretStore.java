package byx.service.secrets;

import byx.service.Log;
import java.util.Optional;

/**
 * Decorador que impõe as regras ANTES de qualquer backend: só ids utilizáveis, tamanho já validado por {@link SecretBytes}, e logs só
 * com códigos fixos. Isolar isto do backend permite testar as regras sem o Keychain.
 */
public final class ValidatedSecretStore implements SecretStore {
    private final SecretStore backend;

    public ValidatedSecretStore(SecretStore backend) {
        this.backend = backend;
    }

    /** Só diagnóstico do harness de canário: o backend SecItem, se for ele. */
    SecItemSecretStore backendIfSecItem() {
        return backend instanceof SecItemSecretStore s ? s : null;
    }

    private static void requireUsable(SecretId id) throws SecretStoreException {
        if (id == null || !id.usable()) {
            throw new SecretStoreException(SecretStatus.NOT_CONFIGURED);
        }
    }

    private <T> T guard(String op, java.util.concurrent.Callable<T> c) throws SecretStoreException {
        try {
            return c.call();
        } catch (SecretStoreException e) {
            Log.event("secret_store_" + op, e.status().name() + (e.osStatus() == 0 ? "" : " os=" + e.osStatus()));
            throw e;
        } catch (Exception e) {
            Log.event("secret_store_" + op, "ERROR");
            throw new SecretStoreException(SecretStatus.ERROR);
        }
    }

    @Override
    public SecretStatus status() {
        return backend.status();
    }

    @Override
    public void write(SecretId id, SecretBytes value) throws SecretStoreException {
        requireUsable(id);
        guard("write", () -> {
            backend.write(id, value);
            return null;
        });
    }

    @Override
    public boolean update(SecretId id, SecretBytes value) throws SecretStoreException {
        requireUsable(id);
        return guard("update", () -> backend.update(id, value));
    }

    @Override
    public Optional<SecretBytes> read(SecretId id) throws SecretStoreException {
        requireUsable(id);
        return guard("read", () -> backend.read(id));
    }

    @Override
    public boolean delete(SecretId id) throws SecretStoreException {
        requireUsable(id);
        return guard("delete", () -> backend.delete(id));
    }
}
