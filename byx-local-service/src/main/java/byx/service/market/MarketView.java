package byx.service.market;

import java.util.List;

/** Visão imutável do feed publicada para os assinantes. Cada parte tem um contador de versão: o assinante só reenvia o que mudou. */
public record MarketView(
        Feed feed, String reason, BookState book, long updatedAtMs, long nowMs,
        Double last, Double mark, Double index, Double fundingRate, Ticker ticker,
        List<double[]> bids, List<double[]> asks, List<Candle> candles, List<Trade> trades,
        long vState, long vBook, long vTrades, long vCandlesStruct, long vCandleTail,
        long reconnects, long rotations, long resyncs, long rejected) {

    public enum Feed { CONNECTING, LIVE, STALE, RECONNECTING, DISCONNECTED, ERROR }

    public enum BookState { SYNCING, LIVE, RESYNCING }

    public record Ticker(double changePct, double high, double low, double volumeBase, double volumeQuote) {
    }

    public record Candle(long openMs, double open, double high, double low, double close, double volume) {
    }

    /** buy = o lado agressor comprou (m == false em aggTrade). */
    public record Trade(long timeMs, double price, double qty, boolean buy) {
    }

    public static MarketView idle(long nowMs) {
        return new MarketView(Feed.DISCONNECTED, "idle", BookState.SYNCING, 0, nowMs, null, null, null, null, null, List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
