package byx.service.tx;

/** Falha de transação com código fechado. A mensagem é opcional e INTERNA (nunca vai ao IPC, ao log nem à UI). */
public final class TxException extends RuntimeException {
    private final TxError error;

    public TxException(TxError error) {
        super(error.name(), null, false, false);
        this.error = error;
    }

    public TxError error() {
        return error;
    }
}
