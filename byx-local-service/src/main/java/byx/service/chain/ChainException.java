package byx.service.chain;

import java.io.IOException;

/** Falha do conector: só a razão tipada (sem corpo, URL, cabeçalho nem texto do nó). */
public final class ChainException extends IOException {
    public final ChainReason reason;

    public ChainException(ChainReason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
