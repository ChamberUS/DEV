package byx.service.market;

/**
 * Taxonomia FECHADA de rejeições e de eventos de ressincronização do feed público. O nome do enum é o que vai para o log; {@link #code} é o código legado (grosso) que
 * os contratos antigos e a UI já conhecem. Nada aqui vem do servidor: toda razão é escolhida pelo nosso código a partir de uma verificação local.
 */
enum MarketReason {
    // parsing / esquema (legado: malformed)
    JSON_INVALID("malformed"),
    SCHEMA_INVALID("malformed"),
    MISSING_FIELD("malformed"),
    TYPE_MISMATCH("malformed"),
    NUMBER_INVALID("malformed"),
    NON_FINITE_NUMBER("malformed"),
    VALUE_OUT_OF_RANGE("malformed"),
    INVARIANT_VIOLATED("malformed"),
    LEVEL_COUNT_EXCEEDED("malformed"),
    // identidade do mercado / evento
    WRONG_SYMBOL("wrong_symbol"),
    WRONG_MARKET_TYPE("wrong_market_type"),
    UNKNOWN_EVENT_TYPE("unexpected_event"),
    UNEXPECTED_STREAM("unexpected_stream"),
    // limites e transporte
    FRAME_OVERSIZED("message_too_large"),
    RESPONSE_TOO_LARGE("response_too_large"),
    TRANSPORT_ERROR("transport_error"),
    REDIRECT_REFUSED("redirect_refused"),
    NOT_ALLOWLISTED("not_allowlisted"),
    INTERRUPTED("interrupted"),
    CONNECTION_CLOSED("closed"),
    CONNECT_FAILED("connect_failed"),
    HANDSHAKE_REFUSED("handshake_refused"),
    // estado do book de profundidade (lógica de sequência, não parsing)
    DEPTH_SEQUENCE_GAP("sequence_gap"),
    DEPTH_CROSSED_BOOK("crossed_book"),
    DEPTH_CROSSED_SNAPSHOT("crossed_snapshot"),
    DEPTH_BUFFER_OVERFLOW("buffer_overflow"),
    SNAPSHOT_BEHIND_STREAM("snapshot_behind_stream"),
    REJECTED_FRAME("rejected_event"),
    // invalidações esperadas do book (nunca logadas como erro)
    BOOK_CONNECTING("connecting"),
    BOOK_RECONNECT("reconnect"),
    BOOK_ROTATION("rotation"),
    BOOK_CONNECTION_LOST("connection_lost"),
    BOOK_START("start"),
    OTHER("other");

    final String code;

    MarketReason(String code) {
        this.code = code;
    }

    /** Código legado → razão (primeira que o usa); desconhecido = OTHER. */
    static MarketReason fromCode(String code) {
        for (MarketReason r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        return OTHER;
    }
}
