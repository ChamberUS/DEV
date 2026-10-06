package panel.localservice;

import java.time.Instant;
import java.util.List;
import panel.model.TraderSnapshot.Candle;
import panel.model.TraderSnapshot.Level;
import panel.model.TraderSnapshot.MarketTrade;

/**
 * Dado público de mercado (ETHUSDT USDⓈ-M) como o serviço local o entregou. Imutável. O painel não fala com a Binance: tudo aqui vem
 * do canal local tipado, já validado e limitado. Valores ausentes são null (nunca zero inventado).
 */
public record MarketData(Link link, String feed, String reason, String book, Instant updatedAt, Double last, Double mark, Double index, Ticker ticker,
        List<Level> bids, List<Level> asks, List<Candle> candles, List<MarketTrade> trades) {

    /** Estado do canal local (distinto do estado do feed, que é o que o serviço informa). */
    public enum Link { IDLE, CONNECTING, STREAMING, LOST, UNSUPPORTED }

    /** Janela móvel de 24 h (não o dia UTC). volumeQuote em USDT. */
    public record Ticker(double changePct, double high, double low, double volumeBase, double volumeQuote) {
    }

    public static MarketData idle() {
        return new MarketData(Link.IDLE, null, null, null, null, null, null, null, null, List.of(), List.of(), List.of(), List.of());
    }

    public MarketData withLink(Link next) {
        return new MarketData(next, feed, reason, book, updatedAt, last, mark, index, ticker, bids, asks, candles, trades);
    }

    public boolean hasData() {
        return last != null || ticker != null || !candles.isEmpty();
    }
}
