package byx.service.market;

import java.io.IOException;

/** Falha do feed público. O código é fixo e curto: nunca carrega corpo de resposta, URL, cabeçalho ou texto vindo da rede. */
public final class MarketException extends IOException {
    public final String code;

    public MarketException(String code) {
        super(code);
        this.code = code;
    }

    public MarketException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }
}
