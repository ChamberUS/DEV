package byx.service.tx;

/** Modos de taxa: conjunto fechado. O painel escolhe o MODO; o preço de cada modo vem da política do serviço. */
public enum FeeMode {
    LOW, STANDARD, HIGH;

    public static FeeMode parse(String s) {
        if (s == null) {
            throw new TxException(TxError.INVALID_FEE_MODE);
        }
        for (FeeMode m : values()) {
            if (m.name().equals(s)) {
                return m;
            }
        }
        throw new TxException(TxError.INVALID_FEE_MODE);
    }
}
