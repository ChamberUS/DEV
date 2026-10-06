package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import panel.model.TraderSnapshot.Candle;
import panel.model.TraderSnapshot.Level;
import panel.model.TraderSnapshot.MarketTrade;

/**
 * Assinatura do feed público de mercado no serviço local. O painel NÃO conversa com a Binance: abre o canal local pareado (a mesma prova
 * mútua da sondagem), pede a operação tipada {@code market.subscribe} (sem argumento algum) e lê eventos tipados. Cada evento é validado
 * (tipo, tópico, tamanhos, números finitos, ordem do book e dos candles) e qualquer desvio derruba a sessão (falha fechada) em vez de
 * virar dado. Quadro acima de 16 KiB é recusado antes de alocar. Silêncio do serviço por mais de 6 s encerra a sessão (o serviço envia
 * batimento a cada 2 s). Reconexão com recuo exponencial e jitter; start/stop abrem uma nova geração e nada de sessão antiga sobrevive.
 * Fora da thread FX; as notificações são coalescidas (no máximo 4 por segundo).
 */
public final class MarketFeedClient implements AutoCloseable {
    public static final int MAX_EVENT_FRAME = 16 * 1024;
    public static final int MAX_BOOK_LEVELS = 20;
    public static final int MAX_TRADES = 50;
    public static final int MAX_CANDLES = 120;
    static final long HANDSHAKE_MS = 3_000;
    static final long SILENCE_MS = 6_000;
    static final long NOTIFY_MS = 250;
    static final long BACKOFF_BASE_MS = 1_000;
    static final long BACKOFF_CAP_MS = 15_000;
    static final long UNSUPPORTED_RETRY_MS = 30_000;

    private static final Set<String> FEEDS = Set.of("CONNECTING", "LIVE", "STALE", "RECONNECTING", "DISCONNECTED", "ERROR");
    private static final Set<String> BOOKS = Set.of("SYNCING", "LIVE", "RESYNCING");
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "market-feed-timer");
        t.setDaemon(true);
        return t;
    });

    private final LocalServiceClient client;
    private final Runnable onChange;
    private final Random random = new Random();
    private volatile MarketData current = MarketData.idle();
    private volatile boolean dirty;
    private long generation;
    private Thread reader;
    private SocketChannel channel;
    private ScheduledFuture<?> notifier;

    public MarketFeedClient(LocalServiceClient client, Runnable onChange) {
        this.client = client;
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    public MarketData snapshot() {
        return current;
    }

    public synchronized boolean running() {
        return reader != null;
    }

    public synchronized void start() {
        if (reader != null) {
            return;
        }
        generation++;
        long g = generation;
        current = MarketData.idle().withLink(MarketData.Link.CONNECTING);
        dirty = true;
        reader = new Thread(() -> loop(g), "market-feed-client");
        reader.setDaemon(true);
        reader.start();
        notifier = TIMER.scheduleWithFixedDelay(() -> {
            if (dirty) {
                dirty = false;
                onChange.run();
            }
        }, NOTIFY_MS, NOTIFY_MS, TimeUnit.MILLISECONDS);
    }

    /** Cancela a assinatura (logout, troca de usuário, saída): fecha o canal, descarta o dado e não deixa thread viva. */
    public void stop() {
        Thread t;
        synchronized (this) {
            if (reader == null) {
                return;
            }
            generation++;
            t = reader;
            reader = null;
            if (notifier != null) {
                notifier.cancel(false);
                notifier = null;
            }
            closeChannel();
        }
        t.interrupt();
        current = MarketData.idle();
        dirty = false;
        onChange.run(); // a UI volta ao estado "não configurado" já, sem esperar o relógio
    }

    @Override
    public void close() {
        stop();
    }

    private synchronized boolean alive(long g) {
        return g == generation && reader != null;
    }

    private synchronized void closeChannel() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nada
            }
            channel = null;
        }
    }

    private void publish(long g, MarketData next) {
        synchronized (this) {
            if (g != generation) {
                return; // sessão antiga: nunca vira estado
            }
            current = next;
        }
        dirty = true;
    }

    // ---- laço de sessões ------------------------------------------------------------------------------------------------------------

    private void loop(long g) {
        int failures = 0;
        while (alive(g)) {
            long wait;
            try {
                session(g);
                failures = 0;
                wait = BACKOFF_BASE_MS; // sessão que terminou normalmente (serviço reiniciou): recomeça cedo
            } catch (LocalServiceClient.Fail f) {
                boolean unsupported = "unsupported_operation".equals(f.code);
                publish(g, current.withLink(unsupported ? MarketData.Link.UNSUPPORTED : MarketData.Link.LOST));
                failures++;
                wait = unsupported ? UNSUPPORTED_RETRY_MS : backoff(failures);
            } catch (IOException | RuntimeException e) {
                publish(g, current.withLink(MarketData.Link.LOST));
                failures++;
                wait = backoff(failures);
            }
            if (!alive(g)) {
                return;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    long backoff(int failures) {
        long ceil = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(10, Math.max(0, failures - 1)));
        return ceil / 2 + (long) (random.nextDouble() * (ceil - ceil / 2));
    }

    private void session(long g) throws IOException, LocalServiceClient.Fail {
        ScheduledFuture<?> guard = null;
        SocketChannel[] mine = new SocketChannel[1];
        try {
            LocalServiceClient.Paired p = client.openPaired(ch -> {
                synchronized (this) {
                    if (g != generation) {
                        try {
                            ch.close();
                        } catch (IOException ignored) {
                            // nada
                        }
                        return;
                    }
                    channel = ch;
                }
                mine[0] = ch;
            });
            // prazo do handshake + ack da assinatura: o canal é fechado se o serviço travar
            guard = TIMER.schedule(() -> closeQuietly(mine[0]), HANDSHAKE_MS, TimeUnit.MILLISECONDS);
            // o pedido não leva argumento: o painel não escolhe host, caminho, símbolo nem canal
            LocalServiceClient.send(p.out(), "{\"v\":1,\"id\":\"m-sub\",\"op\":\"market.subscribe\"}");
            JsonNode ack = LocalServiceClient.read(p.in(), LocalServiceClient.MAX_FRAME);
            if (!"m-sub".equals(ack.path("id").asText(null))) {
                throw new LocalServiceClient.Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
            }
            if (!ack.path("ok").asBoolean(false)) {
                String code = ack.path("error").path("code").asText("");
                throw new LocalServiceClient.Fail(LocalServiceStatus.State.INCOMPATIBLE, "unsupported_operation".equals(code) ? code : "subscribe_refused");
            }
            if (!ack.path("result").path("streaming").asBoolean(false)) {
                throw new LocalServiceClient.Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
            }
            guard.cancel(false);
            guard = null;
            // sessão aberta: o que veio de uma sessão anterior continua visível mas NÃO vale como vivo até o primeiro estado novo
            MarketData prev = current;
            publish(g, new MarketData(MarketData.Link.STREAMING, prev.hasData() ? "RECONNECTING" : "CONNECTING", "resubscribing", prev.book(), prev.updatedAt(), prev.last(),
                    prev.mark(), prev.index(), prev.ticker(), List.of(), List.of(), prev.candles(), prev.trades()));
            long[] lastFrame = {System.nanoTime()};
            ScheduledFuture<?> silence = TIMER.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastFrame[0] > TimeUnit.MILLISECONDS.toNanos(SILENCE_MS)) {
                    closeQuietly(mine[0]); // sem quadro nem batimento: o serviço sumiu
                }
            }, 1_000, 1_000, TimeUnit.MILLISECONDS);
            try {
                read(g, p.in(), lastFrame);
            } finally {
                silence.cancel(false);
            }
        } finally {
            if (guard != null) {
                guard.cancel(false);
            }
            closeQuietly(mine[0]);
            synchronized (this) {
                if (channel == mine[0]) {
                    channel = null;
                }
            }
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    private void read(long g, InputStream in, long[] lastFrame) throws IOException, LocalServiceClient.Fail {
        long seq = 0;
        while (alive(g)) {
            JsonNode e = LocalServiceClient.read(in, MAX_EVENT_FRAME);
            lastFrame[0] = System.nanoTime();
            long s = e.path("seq").asLong(-1);
            if (e.path("v").asInt(-1) != 1 || !"event".equals(e.path("type").asText(null)) || s <= seq) {
                throw violation();
            }
            seq = s;
            publish(g, apply(current, e));
        }
    }

    // ---- validação e aplicação de cada evento ---------------------------------------------------------------------------------------

    static LocalServiceClient.Fail violation() {
        return new LocalServiceClient.Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
    }

    /** Aplica UM evento sobre o dado atual. Qualquer desvio do contrato lança (a sessão é descartada). */
    static MarketData apply(MarketData d, JsonNode e) throws LocalServiceClient.Fail {
        String topic = e.path("topic").asText("");
        MarketData.Link link = MarketData.Link.STREAMING;
        switch (topic) {
            case "state" -> {
                String feed = e.path("feed").asText("");
                String book = e.path("book").asText("");
                String reason = e.path("reason").asText("");
                if (!FEEDS.contains(feed) || !BOOKS.contains(book) || !reason.matches("[a-z_]{1,32}") || !"ETHUSDT".equals(e.path("symbol").asText(null))
                        || !"USD-M".equals(e.path("market").asText(null))) {
                    throw violation();
                }
                long at = e.path("updatedAtMs").asLong(-1);
                if (at < 0 || !e.path("updatedAtMs").isIntegralNumber()) {
                    throw violation();
                }
                MarketData.Ticker tk = null;
                if (e.has("ticker24h")) {
                    JsonNode t = e.get("ticker24h");
                    tk = new MarketData.Ticker(num(t, "changePct", true), num(t, "high", false), num(t, "low", false), num(t, "volumeBase", true), num(t, "volumeQuote", true));
                }
                return new MarketData(link, feed, reason, book, at == 0 ? null : Instant.ofEpochMilli(at), optional(e, "last"), optional(e, "mark"), optional(e, "index"), tk,
                        d.bids(), d.asks(), d.candles(), d.trades());
            }
            case "book" -> {
                boolean live = e.path("live").asBoolean(false);
                List<Level> bids = levels(e.get("bids"), true);
                List<Level> asks = levels(e.get("asks"), false);
                if (!live && (!bids.isEmpty() || !asks.isEmpty())) {
                    throw violation(); // book não consistente nunca traz níveis
                }
                if (!bids.isEmpty() && !asks.isEmpty() && bids.get(0).price() >= asks.get(0).price()) {
                    throw violation(); // book cruzado
                }
                return new MarketData(link, d.feed(), d.reason(), d.book(), d.updatedAt(), d.last(), d.mark(), d.index(), d.ticker(), bids, asks, d.candles(), d.trades());
            }
            case "trades" -> {
                JsonNode a = e.get("trades");
                if (a == null || !a.isArray() || a.size() > MAX_TRADES) {
                    throw violation();
                }
                List<MarketTrade> out = new ArrayList<>(a.size());
                long prev = Long.MAX_VALUE;
                for (JsonNode r : a) {
                    if (!r.isArray() || r.size() != 4 || !r.get(0).isIntegralNumber() || !r.get(3).isIntegralNumber()) {
                        throw violation();
                    }
                    long t = r.get(0).asLong();
                    int side = r.get(3).asInt(-1);
                    if (t <= 0 || t > prev || (side != 0 && side != 1)) {
                        throw violation();
                    }
                    prev = t;
                    out.add(new MarketTrade(Instant.ofEpochMilli(t), pos(r.get(1)), pos(r.get(2)), side == 1));
                }
                return new MarketData(link, d.feed(), d.reason(), d.book(), d.updatedAt(), d.last(), d.mark(), d.index(), d.ticker(), d.bids(), d.asks(), d.candles(), List.copyOf(out));
            }
            case "candles" -> {
                JsonNode a = e.get("candles");
                if (!"1m".equals(e.path("interval").asText(null)) || a == null || !a.isArray() || a.size() > MAX_CANDLES) {
                    throw violation();
                }
                List<Candle> out = new ArrayList<>(a.size());
                long prev = 0;
                for (JsonNode r : a) {
                    Candle c = candle(r);
                    if (c.openTimeMs() <= prev) {
                        throw violation();
                    }
                    prev = c.openTimeMs();
                    out.add(c);
                }
                return new MarketData(link, d.feed(), d.reason(), d.book(), d.updatedAt(), d.last(), d.mark(), d.index(), d.ticker(), d.bids(), d.asks(), List.copyOf(out), d.trades());
            }
            case "candle" -> {
                Candle c = candle(e.get("c"));
                List<Candle> now = new ArrayList<>(d.candles());
                if (!now.isEmpty() && now.get(now.size() - 1).openTimeMs() == c.openTimeMs()) {
                    now.set(now.size() - 1, c); // só a vela em andamento é atualizada no lugar
                    return new MarketData(link, d.feed(), d.reason(), d.book(), d.updatedAt(), d.last(), d.mark(), d.index(), d.ticker(), d.bids(), d.asks(), List.copyOf(now), d.trades());
                }
                return d; // vela fora do lugar: espera o conjunto completo (o serviço o reenvia quando a estrutura muda)
            }
            default -> throw violation();
        }
    }

    private static Candle candle(JsonNode r) throws LocalServiceClient.Fail {
        if (r == null || !r.isArray() || r.size() != 6 || !r.get(0).isIntegralNumber()) {
            throw violation();
        }
        double o = pos(r.get(1));
        double h = pos(r.get(2));
        double l = pos(r.get(3));
        double c = pos(r.get(4));
        double v = r.get(5).isNumber() ? r.get(5).asDouble(-1) : -1;
        if (r.get(0).asLong() <= 0 || h < l || h < Math.max(o, c) || l > Math.min(o, c) || v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
            throw violation();
        }
        return new Candle(o, h, l, c, r.get(0).asLong());
    }

    private static List<Level> levels(JsonNode a, boolean bids) throws LocalServiceClient.Fail {
        if (a == null || !a.isArray() || a.size() > MAX_BOOK_LEVELS) {
            throw violation();
        }
        List<Level> out = new ArrayList<>(a.size());
        double prev = bids ? Double.MAX_VALUE : 0;
        for (JsonNode r : a) {
            if (!r.isArray() || r.size() != 2) {
                throw violation();
            }
            double p = pos(r.get(0));
            double q = pos(r.get(1));
            if (bids ? p >= prev : p <= prev) {
                throw violation(); // fora de ordem ou nível repetido
            }
            prev = p;
            out.add(new Level(p, q));
        }
        return List.copyOf(out);
    }

    private static double pos(JsonNode n) throws LocalServiceClient.Fail {
        if (n == null || !n.isNumber()) {
            throw violation();
        }
        double v = n.asDouble();
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0 || v > 1e12) {
            throw violation();
        }
        return v;
    }

    private static Double optional(JsonNode e, String f) throws LocalServiceClient.Fail {
        return e.has(f) ? Double.valueOf(pos(e.get(f))) : null;
    }

    private static double num(JsonNode t, String f, boolean mayBeZeroOrNegative) throws LocalServiceClient.Fail {
        JsonNode n = t.get(f);
        if (n == null || !n.isNumber()) {
            throw violation();
        }
        double v = n.asDouble();
        if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1e15 || (!mayBeZeroOrNegative && v <= 0)) {
            throw violation();
        }
        return v;
    }
}
