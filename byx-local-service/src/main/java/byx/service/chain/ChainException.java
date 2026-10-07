package byx.service.chain;

import java.io.IOException;

/** Falha do conector: só a razão tipada (sem corpo, URL, cabeçalho nem texto do nó). {@code httpStatus}/{@code errorBody}: uso INTERNO do mapeamento (404 de "não encontrado"); nunca vão ao painel nem aos logs. */
public final class ChainException extends IOException {
    public final ChainReason reason;
    final int httpStatus;
    final byte[] errorBody;

    public ChainException(ChainReason reason) {
        this(reason, 0, null);
    }

    ChainException(ChainReason reason, int httpStatus, byte[] errorBody) {
        super(reason.name());
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.errorBody = errorBody;
    }
}
