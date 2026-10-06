package byx.service.market;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * Assinatura local de UMA conexão. Não há fila de mensagens: o assinante só guarda "o que já enviei" (versões) e, a cada ciclo, compara
 * com a visão mais nova do feed e envia o que mudou. Por isso a memória é constante, dado intermediário de preço/trades é coalescido
 * (o estado mais novo sempre vence) e o book é SEMPRE um snapshot consistente de topo (nunca delta): perder um ciclo nunca deixa o
 * cliente com book inconsistente. O ritmo é limitado (um ciclo por {@link #CYCLE_MS}); um cliente lento só trava a PRÓPRIA escrita, que
 * tem prazo (o watchdog fecha a conexão) e nunca bloqueia o feed.
 */
public final class MarketSubscriber implements Runnable {
    public static final long CYCLE_MS = 250;
    public static final long HEARTBEAT_MS = 2_000;

    /** Destino dos quadros (a conexão); write respeita o prazo de escrita e o teto de quadro. */
    public interface Sink {
        void write(byte[] body) throws IOException;
    }

    private final MarketFeed feed;
    private final Sink sink;
    private final JsonMapper mapper;
    private volatile boolean cancelled;
    private long seq;
    private long sentState = -1;
    private long sentBook = -1;
    private long sentTrades = -1;
    private long sentStruct = -1;
    private long sentTail = -1;
    private long lastSend;

    public MarketSubscriber(MarketFeed feed, Sink sink, JsonMapper mapper) {
        this.feed = feed;
        this.sink = sink;
        this.mapper = mapper;
    }

    public void cancel() {
        cancelled = true;
    }

    @Override
    public void run() {
        try {
            while (!cancelled) {
                cycle();
                Thread.sleep(CYCLE_MS);
            }
        } catch (IOException | InterruptedException e) {
            // conexão caiu/prazo estourou ou serviço parando: o dono da conexão faz a limpeza
        } catch (RuntimeException e) {
            byx.service.Log.event("market_subscriber_error", e.getClass().getSimpleName());
        }
    }

    void cycle() throws IOException {
        MarketView v = feed.view();
        long now = System.currentTimeMillis();
        boolean first = sentState < 0;
        if (first || v.vState() != sentState || now - lastSend >= HEARTBEAT_MS) {
            send(state(v));
            sentState = v.vState();
        }
        if (first || v.vBook() != sentBook) {
            send(book(v));
            sentBook = v.vBook();
        }
        if (first || v.vTrades() != sentTrades) {
            send(trades(v));
            sentTrades = v.vTrades();
        }
        if (first || v.vCandlesStruct() != sentStruct) {
            send(candles(v));
            sentStruct = v.vCandlesStruct();
            sentTail = v.vCandleTail();
        } else if (v.vCandleTail() != sentTail && !v.candles().isEmpty()) {
            send(candle(v));
            sentTail = v.vCandleTail();
        }
    }

    private void send(ObjectNode n) throws IOException {
        sink.write(mapper.writeValueAsBytes(n));
        lastSend = System.currentTimeMillis();
    }

    private ObjectNode event(String topic) {
        ObjectNode n = mapper.createObjectNode();
        n.put("v", 1);
        n.put("type", "event");
        n.put("topic", topic);
        n.put("seq", ++seq);
        return n;
    }

    ObjectNode state(MarketView v) {
        ObjectNode n = event("state");
        n.put("symbol", Allowlist.SYMBOL);
        n.put("market", "USD-M");
        n.put("feed", v.feed().name());
        n.put("reason", v.reason());
        n.put("book", v.book().name());
        n.put("updatedAtMs", v.updatedAtMs());
        n.put("nowMs", v.nowMs());
        put(n, "last", v.last());
        put(n, "mark", v.mark());
        put(n, "index", v.index());
        put(n, "funding", v.fundingRate());
        if (v.ticker() != null) {
            ObjectNode t = n.putObject("ticker24h"); // janela móvel de 24 h, não dia UTC
            t.put("changePct", v.ticker().changePct());
            t.put("high", v.ticker().high());
            t.put("low", v.ticker().low());
            t.put("volumeBase", v.ticker().volumeBase());
            t.put("volumeQuote", v.ticker().volumeQuote());
        }
        return n;
    }

    private static void put(ObjectNode n, String k, Double v) {
        if (v != null) {
            n.put(k, v);
        }
    }

    ObjectNode book(MarketView v) {
        ObjectNode n = event("book");
        n.put("live", v.book() == MarketView.BookState.LIVE);
        levels(n.putArray("bids"), v.bids());
        levels(n.putArray("asks"), v.asks());
        return n;
    }

    private static void levels(ArrayNode a, java.util.List<double[]> ls) {
        for (double[] l : ls) {
            a.addArray().add(l[0]).add(l[1]);
        }
    }

    ObjectNode trades(MarketView v) {
        ObjectNode n = event("trades");
        ArrayNode a = n.putArray("trades");
        for (MarketView.Trade t : v.trades()) {
            a.addArray().add(t.timeMs()).add(t.price()).add(t.qty()).add(t.buy() ? 1 : 0);
        }
        return n;
    }

    ObjectNode candles(MarketView v) {
        ObjectNode n = event("candles");
        n.put("interval", "1m");
        ArrayNode a = n.putArray("candles");
        for (MarketView.Candle c : v.candles()) {
            row(a.addArray(), c);
        }
        return n;
    }

    ObjectNode candle(MarketView v) {
        ObjectNode n = event("candle");
        row(n.putArray("c"), v.candles().get(v.candles().size() - 1));
        return n;
    }

    private static void row(ArrayNode r, MarketView.Candle c) {
        r.add(c.openMs()).add(c.open()).add(c.high()).add(c.low()).add(c.close()).add(c.volume());
    }
}
