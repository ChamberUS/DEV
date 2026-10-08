package byx.service.auth;

import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import java.util.Optional;

/** Âncora no cofre do serviço (keychain de proteção de dados, só a identidade do serviço lê). Falha do cofre = falha FECHADA. */
public final class SecretStoreAnchor implements Anchor {
    private final SecretStore store;
    private final SecretId id;

    public SecretStoreAnchor(SecretStore store, SecretId id) {
        this.store = store;
        this.id = id;
    }

    boolean testScoped() { return id.scope()==SecretId.Scope.TEST; }

    @Override
    public Optional<AnchorData> read() throws AnchorException {
        try (SecretBytes v = store.read(id).orElse(null)) {
            if (v == null) {
                return Optional.empty();
            }
            byte[] copy = v.copyBytes();
            try {
                return Optional.of(AnchorData.decode(copy));
            } finally {
                java.util.Arrays.fill(copy, (byte) 0);
            }
        } catch (SecretStoreException e) {
            throw new AnchorException("anchor_" + e.status().name().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new AnchorException("anchor_malformed");
        }
    }

    @Override
    public void write(AnchorData data) throws AnchorException {
        byte[] enc = data.encode();
        try (SecretBytes v = SecretBytes.copyOf(enc)) {
            store.write(id, v);
        } catch (SecretStoreException e) {
            throw new AnchorException("anchor_" + e.status().name().toLowerCase());
        } finally {
            java.util.Arrays.fill(enc, (byte) 0);
        }
    }
}
