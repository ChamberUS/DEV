package byx.service.tx;

import java.util.EnumSet;
import java.util.Set;

/** Estados da operação; as transições são uma tabela FECHADA e qualquer outra é recusada ({@link #require}). */
public enum TxState {
    NEW, VALIDATED, SIMULATING, QUOTED, AWAITING_CONFIRMATION, CONFIRMED, SIGNING, BROADCASTING, SUBMITTED, CONFIRMED_ON_CHAIN, FAILED, EXPIRED, UNKNOWN_OUTCOME;

    private Set<TxState> next() {
        return switch (this) {
            case NEW -> EnumSet.of(VALIDATED, FAILED);
            case VALIDATED -> EnumSet.of(SIMULATING, FAILED);
            case SIMULATING -> EnumSet.of(QUOTED, FAILED);
            case QUOTED -> EnumSet.of(AWAITING_CONFIRMATION, EXPIRED, FAILED);
            // nova cotação (mudança de modo/memo) volta a SIMULATING; confirmar vai a CONFIRMED; vencer/cancelar/invalidar vai a EXPIRED
            case AWAITING_CONFIRMATION -> EnumSet.of(CONFIRMED, SIMULATING, EXPIRED, FAILED);
            case CONFIRMED -> EnumSet.of(SIGNING, FAILED);
            case SIGNING -> EnumSet.of(BROADCASTING, FAILED);
            case BROADCASTING -> EnumSet.of(SUBMITTED, FAILED, UNKNOWN_OUTCOME);
            case SUBMITTED -> EnumSet.of(CONFIRMED_ON_CHAIN, FAILED);
            case UNKNOWN_OUTCOME -> EnumSet.of(CONFIRMED_ON_CHAIN, FAILED);
            // EXPIRED e FAILED ANTES da confirmação permitem nova cotação (SIMULATING); depois dela, a operação é final (decidido por TxService)
            case EXPIRED, FAILED -> EnumSet.of(SIMULATING);
            case CONFIRMED_ON_CHAIN -> EnumSet.noneOf(TxState.class);
        };
    }

    public boolean canGoTo(TxState to) {
        return next().contains(to);
    }

    public TxState require(TxState to) {
        if (!canGoTo(to)) {
            throw new TxException(TxError.QUOTE_MISMATCH);
        }
        return to;
    }

    /** A partir daqui houve execução (assinatura/transmissão): nunca mais se recota a mesma operação. */
    public boolean executionStarted() {
        return switch (this) {
            case CONFIRMED, SIGNING, BROADCASTING, SUBMITTED, CONFIRMED_ON_CHAIN, UNKNOWN_OUTCOME -> true;
            default -> false;
        };
    }
}
