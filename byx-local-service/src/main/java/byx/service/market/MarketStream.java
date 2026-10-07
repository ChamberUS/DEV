package byx.service.market;

/** Classe do stream/rota onde algo aconteceu. Fechada; nunca o nome vindo da rede. UNKNOWN_* = falhou antes de o stream poder ser identificado. */
enum MarketStream {
    DEPTH, AGG_TRADE, MARK_PRICE, TICKER, KLINE, REST_DEPTH, REST_KLINES, UNKNOWN_PUBLIC, UNKNOWN_MARKET;

    /** Nome de stream da Binance (do envelope) → classe; qualquer outro nome = UNKNOWN_MARKET (o texto não é copiado). */
    static MarketStream of(String streamName) {
        if (streamName == null) {
            return UNKNOWN_MARKET;
        }
        return switch (streamName) {
            case MarketFeed.PUB_STREAM -> DEPTH;
            case "ethusdt@aggTrade" -> AGG_TRADE;
            case "ethusdt@markPrice@1s" -> MARK_PRICE;
            case "ethusdt@ticker" -> TICKER;
            case "ethusdt@kline_1m" -> KLINE;
            default -> UNKNOWN_MARKET;
        };
    }
}
