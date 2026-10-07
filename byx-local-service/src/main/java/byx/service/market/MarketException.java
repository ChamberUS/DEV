package byx.service.market;

import java.io.IOException;

/**
 * Falha do feed público. O código é fixo e curto: nunca carrega corpo de resposta, URL, cabeçalho ou texto vindo da rede. {@link #reason} é a razão tipada (taxonomia
 * fechada) usada no diagnóstico; {@link #code} é o código legado grosso, mantido para os contratos existentes.
 */
public final class MarketException extends IOException {
    public final String code;
    final MarketReason reason;

    MarketException(MarketReason reason) {
        super(reason.code);
        this.code = reason.code;
        this.reason = reason;
    }

    public MarketException(String code) {
        super(code);
        this.code = code;
        this.reason = MarketReason.fromCode(code);
    }

    public MarketException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.reason = MarketReason.fromCode(code);
    }
}
