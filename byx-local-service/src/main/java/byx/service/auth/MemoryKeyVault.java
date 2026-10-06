package byx.service.auth;

import java.util.Optional;

/** Cofre em memória (testes). Reiniciar o processo o perde: o snapshot existente deixa de abrir (falha fechada), por desenho. */
public final class MemoryKeyVault implements EncryptionKeyVault {
    private byte[] key;
    private volatile boolean failReads;

    @Override
    public synchronized Optional<byte[]> read() throws VaultException {
        if (failReads) {
            throw new VaultException("enckey_unavailable");
        }
        return Optional.ofNullable(key == null ? null : key.clone());
    }

    @Override
    public synchronized void write(byte[] k) {
        key = k.clone();
    }

    public void failReads(boolean v) {
        failReads = v;
    }

    /** Teste: perde a chave (o atacante não tem isto em produção: a chave está no keychain do serviço). */
    public synchronized void wipeForTest() {
        key = null;
    }

    /** Teste: troca a chave por outra (chave errada). */
    public synchronized void replaceForTest(byte[] other) {
        key = other.clone();
    }
}
