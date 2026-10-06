package panel.adapter;

import java.util.function.Supplier;
import panel.localservice.MarketData;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.model.TraderSnapshot;

/**
 * Estado real nesta fase: sem conta, trading desabilitado. Deriva o status da estratégia da pesquisa e, quando o serviço local entrega,
 * o mercado PÚBLICO ETHUSDT (preço, mark, 24h, candles, book, trades). Nenhuma conexão com a Binance parte daqui nem do painel.
 */
public class ResearchModeTradingProvider implements TradingProvider {
    private final Supplier<MarketData> market;

    public ResearchModeTradingProvider() {
        this(() -> null);
    }

    public ResearchModeTradingProvider(Supplier<MarketData> market) {
        this.market = market;
    }

    @Override
    public TraderSnapshot load(Snapshot research) {
        TraderSnapshot t = new TraderSnapshot();
        t.loading = research.loading;
        t.backendOnline = research.backendOnline;
        t.feed = "NOT_CONFIGURED";
        t.recorder = research.capture.recorder();
        t.symbol = research.capture.symbol();
        t.market = research.capture.market();
        long approved = research.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        t.strategy = "None approved";
        t.strategyStatus = "IN RESEARCH · " + approved + " / " + research.hypotheses.size() + " hypotheses completed";
        t.strategyRows.add(new String[] {"Microstructure Alpha", "v1", "IN RESEARCH", "Not approved"});
        applyMarket(t, market.get());
        return t;
    }

    /** Nome do feed que o Desk entende. Falha de canal local com dado antigo vira RECONNECTING (visível, marcado STALE), nunca LIVE. */
    public static String feedName(MarketData m) {
        return switch (m.link()) {
            case IDLE -> "NOT_CONFIGURED";
            case UNSUPPORTED -> "UNAVAILABLE";
            case CONNECTING -> "CONNECTING";
            case LOST -> m.hasData() ? "RECONNECTING" : "DISCONNECTED";
            case STREAMING -> m.feed() == null ? "CONNECTING" : m.feed();
        };
    }

    public static void applyMarket(TraderSnapshot t, MarketData m) {
        if (m == null || m.link() == MarketData.Link.IDLE) {
            return; // nada configurado: continua NOT_CONFIGURED
        }
        t.feed = feedName(m);
        t.symbol = "ETHUSDT";
        t.market = "USD-M Futures";
        t.feedUpdatedAt = m.updatedAt();
        t.bookState = m.book();
        t.price = m.last(); // último preço NEGOCIADO
        t.markPrice = m.mark(); // mark price: significado distinto
        t.indexPrice = m.index();
        if (m.ticker() != null) { // janela móvel de 24 h
            t.change24hPct = m.ticker().changePct();
            t.high24h = m.ticker().high();
            t.low24h = m.ticker().low();
            t.volume24h = m.ticker().volumeQuote();
        }
        t.asks.addAll(m.asks());
        t.bids.addAll(m.bids());
        t.candles.addAll(m.candles());
        t.marketTrades.addAll(m.trades());
    }
}
