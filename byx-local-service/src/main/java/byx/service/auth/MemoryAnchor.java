package byx.service.auth;

import java.util.Optional;

/** Âncora em memória (testes e IDE). Reiniciar o processo a perde: o arquivo existente deixa de ser confiável, por desenho. */
public final class MemoryAnchor implements Anchor {
    private AnchorData data;
    private volatile boolean failWrites;

    @Override
    public synchronized Optional<AnchorData> read() {
        return Optional.ofNullable(data);
    }

    @Override
    public synchronized void write(AnchorData d) throws AnchorException {
        if (failWrites) {
            throw new AnchorException("anchor_write_failed");
        }
        data = d;
    }

    /** Teste: simula o keychain indisponível. */
    public void failWrites(boolean v) {
        failWrites = v;
    }

    /** Teste: o atacante NÃO tem este método na produção (a âncora está no keychain); aqui serve para provar que a perda da âncora é fatal. */
    public synchronized void wipeForTest() {
        data = null;
    }
}
