package byx.service.auth;

import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import java.util.Optional;

/** Chave AEAD no cofre do serviço (keychain de proteção de dados; só a identidade do serviço lê). Falha do cofre = falha FECHADA. */
public final class SecretStoreKeyVault implements EncryptionKeyVault {
    private final SecretStore store;
    private final SecretId id;

    public SecretStoreKeyVault(SecretStore store, SecretId id) {
        this.store = store;
        this.id = id;
    }

    boolean testScoped() { return id.scope()==SecretId.Scope.TEST; }

    @Override
    public Optional<byte[]> read() throws VaultException {
        try (SecretBytes v = store.read(id).orElse(null)) {
            if (v == null) {
                return Optional.empty();
            }
            byte[] k = v.copyBytes();
            if (k.length != 32) {
                java.util.Arrays.fill(k, (byte) 0);
                throw new VaultException("enckey_malformed");
            }
            return Optional.of(k);
        } catch (SecretStoreException e) {
            throw new VaultException("enckey_" + e.status().name().toLowerCase());
        }
    }

    @Override
    public void write(byte[] key) throws VaultException {
        try (SecretBytes v = SecretBytes.copyOf(key)) {
            if (!store.update(id, v)) {
                store.write(id, v);
            }
        } catch (SecretStoreException e) {
            throw new VaultException("enckey_" + e.status().name().toLowerCase());
        }
    }
}
