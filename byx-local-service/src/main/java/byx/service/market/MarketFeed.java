package byx.service.market;

import byx.service.Log;
import byx.service.market.MarketEvents.AggTrade;
import byx.service.market.MarketEvents.Depth;
import byx.service.market.MarketEvents.Envelope;
import byx.service.market.MarketEvents.Kline;
import byx.service.market.MarketView.BookState;
import byx.service.market.MarketView.Candle;
import byx.service.market.MarketView.Feed;
import byx.service.market.MarketView.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Dono único do feed público ETHUSDT (USDⓈ-M). Duas conexões no mínimo, porque a Binance separa as rotas: /public (depth) e /market
 * (aggTrade, markPrice, ticker, kline). Todo estado mutável vive em UMA thread de trabalho com fila limitada; callbacks de rede só
 * enfileiram. Não compartilha nada com a captura científica nem com o painel: o painel só vê {@link MarketView}.
 * Fail-closed: mensagem fora do contrato é recusada, lacuna de sequência invalida o book, 418/429 pausam toda atividade.
 */
public final class MarketFeed implements AutoCloseable {
    public static final int BOOK_LEVELS_OUT = 20;
    public static final int MAX_TRADES = 50;
    public static final int MAX_CANDLES = Allowlist.KLINE_LIMIT;
    static final int QUEUE_CAPACITY = 4_096;
    static final String PUB_STREAM = "ethusdt@depth@100ms";

    /** Tempos (ms). Os de produção seguem a documentação; testes passam valores curtos. */
    public record Config(long backoffBaseMs, long backoffCapMs, long rotateMs, long rotateJitterMs, long silenceMs, long staleAfterMs, long stableMs,
            long tickMs, long publishMs, long snapshotMinMs, long snapshotMaxMs, long graceMs, long rateBlockMinMs, long rateBlockMaxMs) {
        public static Config production() {
            return new Config(1_000, 60_000, TimeUnit.HOURS.toMillis(23), TimeUnit.MINUTES.toMillis(10), 30_000, 10_000, 30_000, 1_000, 100, 1_000, 60_000,
                    30_000, 60_000, TimeUnit.HOURS.toMillis(2));
        }
    }

    private final WsTransport ws;
    private final HttpGet http;
    private final Config cfg;
    private final LongSupplier clock;
    private final Random random;

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
        Thread t = new Thread(r, "byx-market");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "byx-market-timer");
        t.setDaemon(true);
        return t;
    });
    private final ThreadPoolExecutor rest = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
        Thread t = new Thread(r, "byx-market-rest");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    private volatile MarketView view;
    private int refs;
    private java.util.concurrent.ScheduledFuture<?> pendingStop;
    private java.util.concurrent.ScheduledFuture<?> ticking;
    private volatile boolean closed;

    // ---- estado do worker (só a thread "byx-market" toca daqui para baixo) ---------------------------------------------------------
    private boolean running;
    private final Conn pub;
    private final Conn mkt;
    private final DepthBook book = new DepthBook();
    private boolean bookLiveOnce;
    private boolean snapshotInflight;
    private boolean wantSnapshot;
    private long snapshotNotBefore;
    private int resyncStreak;
    private long bookLiveSince;
    private boolean klinesInflight;
    private boolean wantKlines;
    private long klinesNotBefore;
    private int klinesFailures;
    private boolean candlesLoaded;
    private boolean tickerSeen;
    private boolean wasLive;
    private long blockedUntil;
    private int blockStreak;
    private long lastRestWeight = -1;

    private Double last;
    private Double mark;
    private Double index;
    private Double funding;
    private MarketView.Ticker ticker;
    private long lastAggId = -1;
    private final ArrayDeque<Trade> trades = new ArrayDeque<>();
    private final ArrayList<Candle> candles = new ArrayList<>();
    private long vState;
    private long vBook;
    private long vTrades;
    private long vStruct;
    private long vTail;
    private long reconnects;
    private long rotations;
    private long resyncs;
    private long rejected;
    private boolean dirty;
    private Feed shownFeed = Feed.DISCONNECTED;
    private String shownReason = "idle";
    private BookState shownBook = BookState.SYNCING;
    private long lastPublish;

    private final class Conn {
        final String name;
        final URI uri;
        volatile int gen;
        volatile WsTransport.Handle handle;
        boolean open;
        boolean planned;
        boolean wasStable;
        long openedAt;
        long lastMsg;
        long retryAt;
        int failures;
        long rotateAfterMs;
        volatile boolean forceReconnect;
        /** Fila estourou: o que ainda está enfileirado desta conexão é descartado sem processar (a reconexão não espera a fila esvaziar). */
        volatile boolean poisoned;

        Conn(String name, URI uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    public MarketFeed(WsTransport ws, HttpGet http, Config cfg) {
        this(ws, http, cfg, System::currentTimeMillis, new Random());
    }

    MarketFeed(WsTransport ws, HttpGet http, Config cfg, LongSupplier clock, Random random) {
        this.ws = ws;
        this.http = http;
        this.cfg = cfg;
        this.clock = clock;
        this.random = random;
        this.pub = new Conn("public", Allowlist.WS_PUBLIC);
        this.mkt = new Conn("market", Allowlist.WS_MARKET);
        this.view = MarketView.idle(clock.getAsLong());
    }

    public MarketView view() {
        return view;
    }

    // ---- ciclo de vida: o feed só roda enquanto há assinante (e por um tempo de graça depois) -----------------------------------------

    public synchronized void acquire() {
        if (closed) {
            return;
        }
        refs++;
        if (pendingStop != null) {
            pendingStop.cancel(false);
            pendingStop = null;
        }
        if (refs == 1) {
            post(this::startOnWorker); // idempotente na thread de trabalho
            if (ticking == null) {
                ticking = timer.scheduleWithFixedDelay(() -> post(this::tick), cfg.tickMs(), cfg.tickMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    public synchronized void release() {
        if (refs > 0) {
            refs--;
        }
        if (refs == 0 && !closed) {
            pendingStop = timer.schedule(() -> {
                synchronized (MarketFeed.this) {
                    if (refs == 0) {
                        if (ticking != null) {
                            ticking.cancel(false);
                            ticking = null;
                        }
                        post(this::stopOnWorker);
                    }
                }
            }, cfg.graceMs(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized int subscribers() {
        return refs;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (this) {
            if (ticking != null) {
                ticking.cancel(false);
            }
        }
        post(this::stopOnWorker);
        timer.shutdown();
        worker.shutdown();
        rest.shutdownNow();
    }

    private boolean post(Runnable r) {
        try {
            worker.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Log.event("market_internal_error", e.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // ---- worker ---------------------------------------------------------------------------------------------------------------------

    private void startOnWorker() {
        if (running) {
            return;
        }
        running = true;
        resetData();
        for (Conn c : List.of(pub, mkt)) {
            c.failures = 0;
            c.retryAt = 0;
            c.rotateAfterMs = cfg.rotateMs() + (cfg.rotateJitterMs() <= 0 ? 0 : (long) (random.nextDouble() * cfg.rotateJitterMs()));
            connect(c);
        }
        Log.event("market_started", null);
        dirty = true;
        publish(true);
    }

    private void stopOnWorker() {
        if (!running) {
            return;
        }
        running = false;
        for (Conn c : List.of(pub, mkt)) {
            c.gen++;
            closeHandle(c);
            c.open = false;
        }
        resetData();
        publish(true);
        Log.event("market_stopped", null);
    }

    private void resetData() {
        book.invalidate(MarketReason.BOOK_START);
        bookLiveOnce = false;
        snapshotInflight = false;
        wantSnapshot = false;
        klinesInflight = false;
        wantKlines = false;
        candlesLoaded = false;
        tickerSeen = false;
        wasLive = false;
        resyncStreak = 0;
        klinesFailures = 0;
        snapshotNotBefore = 0;
        klinesNotBefore = 0;
        last = mark = index = funding = null;
        ticker = null;
        lastAggId = -1;
        trades.clear();
        candles.clear();
        vState++;
        vBook++;
        vTrades++;
        vStruct++;
    }

    private static void closeHandle(Conn c) {
        WsTransport.Handle h = c.handle;
        c.handle = null;
        if (h != null) {
            h.close();
        }
    }

    private void connect(Conn c) {
        long now = clock.getAsLong();
        if (now < blockedUntil) {
            c.retryAt = blockedUntil;
            return;
        }
        c.retryAt = 0;
        c.poisoned = false;
        int gen = ++c.gen;
        c.open = false;
        try {
            c.handle = ws.connect(c.uri, new WsTransport.Listener() {
                @Override
                public void onOpen() {
                    enqueue(c, gen, () -> opened(c));
                }

                @Override
                public void onText(String message) {
                    enqueue(c, gen, () -> message(c, message));
                }

                @Override
                public void onClosed(int code) {
                    enqueue(c, gen, () -> lost(c, "closed", code, -1));
                }

                @Override
                public void onError(String code, int status) {
                    enqueue(c, gen, () -> lost(c, code, -1, status));
                }
            });
        } catch (MarketException e) {
            lost(c, e.code, -1, -1);
        }
    }

    /** Callback de rede → fila limitada. Se a fila encheu, a conexão é abortada e reconectada (nunca cresce sem limite). */
    private void enqueue(Conn c, int gen, Runnable r) {
        boolean ok = post(() -> {
            if (c.gen == gen && running && !c.poisoned) {
                r.run();
            }
        });
        if (!ok) {
            c.poisoned = true;
            c.forceReconnect = true;
            WsTransport.Handle h = c.handle;
            if (h != null) {
                h.close();
            }
        }
    }

    private void opened(Conn c) {
        long now = clock.getAsLong();
        c.open = true;
        c.openedAt = now;
        c.lastMsg = now;
        c.wasStable = false;
        if (c == pub) {
            book.invalidate(MarketReason.BOOK_CONNECTING);
            bookLiveOnce = false;
            wantSnapshot = true;
            snapshotNotBefore = Math.max(snapshotNotBefore, now);
        } else {
            candlesLoaded = false;
            tickerSeen = false;
            wantKlines = true;
        }
        dirty = true;
        pump();
    }

    /** Razão tipada da perda (códigos de transporte conhecidos; qualquer outro vira OTHER, nunca o texto recebido). */
    private static MarketReason lostReason(String code) {
        MarketReason r = MarketReason.fromCode(code);
        return r == MarketReason.OTHER ? MarketReason.TRANSPORT_ERROR : r;
    }

    private void lost(Conn c, String code, int closeCode, int status) {
        long now = clock.getAsLong();
        boolean wasOpen = c.open;
        c.open = false;
        closeHandle(c);
        if (c == pub) {
            book.invalidate(MarketReason.BOOK_CONNECTION_LOST);
            snapshotInflight = false;
        }
        boolean aged = wasOpen && now - c.openedAt >= cfg.rotateMs() - cfg.rotateJitterMs();
        reconnects++;
        if (status == 429 || status == 418) {
            rateBlock(-1);
            c.retryAt = blockedUntil;
        } else if (c.planned || aged) {
            // rotação programada ou o fechamento esperado de 24 h: não é falha, reconecta já e não aumenta o recuo
            c.planned = false;
            c.failures = 0;
            c.retryAt = now;
            rotations += aged ? 1 : 0;
        } else {
            c.failures++;
            c.retryAt = now + backoff(c.failures);
            Log.event("market_connection_lost", "code=" + code + " reason=" + lostReason(code) + " conn=" + c.name + " gen=" + c.gen + " close=" + closeCode + " status=" + status
                    + " open_s=" + (wasOpen ? (now - c.openedAt) / 1000 : -1));
        }
        dirty = true;
    }

    /** Recuo exponencial limitado com jitter ("equal jitter": metade fixa + metade aleatória), nunca zero. */
    long backoff(int failures) {
        long ceil = Math.min(cfg.backoffCapMs(), cfg.backoffBaseMs() << Math.min(20, Math.max(0, failures - 1)));
        long half = ceil / 2;
        return Math.max(1, half + (long) (random.nextDouble() * (ceil - half)));
    }

    private void rateBlock(long retryAfterSec) {
        long now = clock.getAsLong();
        blockStreak++;
        long dur = Math.min(cfg.rateBlockMaxMs(), cfg.rateBlockMinMs() << Math.min(10, blockStreak - 1));
        if (retryAfterSec > 0) {
            dur = Math.min(cfg.rateBlockMaxMs(), Math.max(dur, retryAfterSec * 1000));
        }
        blockedUntil = Math.max(blockedUntil, now + dur);
        Log.event("market_rate_limited", "streak=" + blockStreak);
        dirty = true;
    }

    // ---- mensagens ------------------------------------------------------------------------------------------------------------------

    private void message(Conn c, String text) {
        c.lastMsg = clock.getAsLong();
        MarketStream where = c == pub ? MarketStream.DEPTH : MarketStream.UNKNOWN_MARKET; // antes do envelope só a conexão é conhecida
        try {
            Envelope env = MarketEvents.envelope(text);
            JsonNode d = env.data();
            if (c != pub) {
                where = MarketStream.of(env.stream());
            }
            if (c == pub) {
                if (!PUB_STREAM.equals(env.stream())) {
                    throw new MarketException("unexpected_stream");
                }
                depth(d);
            } else {
                switch (env.stream()) {
                    case "ethusdt@aggTrade" -> aggTrade(MarketEvents.aggTrade(d));
                    case "ethusdt@markPrice@1s" -> {
                        var m = MarketEvents.markPrice(d);
                        mark = m.mark();
                        index = m.index();
                        funding = m.fundingRate();
                        vState++;
                    }
                    case "ethusdt@ticker" -> {
                        var t = MarketEvents.ticker(d);
                        ticker = new MarketView.Ticker(t.changePct(), t.high(), t.low(), t.volumeBase(), t.volumeQuote());
                        if (last == null) {
                            last = t.last();
                        }
                        tickerSeen = true;
                        vState++;
                    }
                    case "ethusdt@kline_1m" -> kline(MarketEvents.kline(d));
                    default -> throw new MarketException("unexpected_stream");
                }
            }
        } catch (MarketException e) {
            rejected++;
            // diagnóstico mínimo: classe de stream, razão tipada, conexão/geração opacas, geração do book e tamanho do frame; nunca o conteúdo
            Log.event("market_rejected", "code=" + e.code + " reason=" + e.reason + " stream=" + where + " conn=" + c.name + " gen=" + c.gen
                    + (c == pub ? " bookgen=" + book.generation() : "") + " bytes=" + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (c == pub) {
                invalidateBook(MarketReason.REJECTED_FRAME);
            }
        }
        dirty = true;
    }

    private void depth(JsonNode d) throws MarketException {
        Depth e = MarketEvents.depth(d);
        apply(book.onEvent(e));
        if (book.state() == DepthBook.State.NO_SNAPSHOT && !snapshotInflight) {
            wantSnapshot = true;
        }
        noteBook();
    }

    private void apply(DepthBook.Verdict v) {
        if (v == DepthBook.Verdict.NEED_RESYNC || v == DepthBook.Verdict.NEED_NEWER_SNAPSHOT) {
            resyncs++;
            resyncStreak++;
            snapshotNotBefore = Math.max(snapshotNotBefore, clock.getAsLong() + snapshotDelay());
            wantSnapshot = true;
            logResync();
        }
        vBook++;
    }

    /** Resync por lógica de SEQUÊNCIA/estado do book (nunca confundido com rejeição de parsing, que tem o próprio evento). */
    private void logResync() {
        Log.event("market_book_resync", "code=" + book.reason() + " reason=" + book.reasonEnum() + " stream=" + MarketStream.DEPTH + " conn=" + pub.name + " gen=" + pub.gen
                + " bookgen=" + book.generation() + (book.detail().isEmpty() ? "" : " " + book.detail()));
    }

    private void invalidateBook(MarketReason why) {
        book.invalidate(why);
        Log.event("market_book_resync", "code=" + why.code + " reason=" + why + " stream=" + MarketStream.DEPTH + " conn=" + pub.name + " gen=" + pub.gen + " bookgen=" + book.generation());
        resyncs++;
        resyncStreak++;
        snapshotNotBefore = Math.max(snapshotNotBefore, clock.getAsLong() + snapshotDelay());
        wantSnapshot = true;
        vBook++;
    }

    private long snapshotDelay() {
        return Math.min(cfg.snapshotMaxMs(), cfg.snapshotMinMs() << Math.min(10, Math.max(0, resyncStreak - 1)));
    }

    private void noteBook() {
        if (book.state() == DepthBook.State.LIVE) {
            if (!bookLiveOnce) {
                bookLiveOnce = true;
                bookLiveSince = clock.getAsLong();
            }
            vState++;
        }
    }

    private void aggTrade(AggTrade t) {
        if (t.id() <= lastAggId) {
            return; // repetido ou fora de ordem
        }
        lastAggId = t.id();
        last = t.price();
        trades.addFirst(new Trade(t.timeMs(), t.price(), t.qty(), !t.buyerIsMaker()));
        while (trades.size() > MAX_TRADES) {
            trades.pollLast();
        }
        vTrades++;
        vState++;
    }

    private void kline(Kline k) {
        if (!candlesLoaded) {
            return; // o bootstrap REST ainda não chegou; o próximo evento (250 ms) repõe
        }
        Candle c = new Candle(k.openMs(), k.open(), k.high(), k.low(), k.close(), k.volume());
        Candle tail = candles.get(candles.size() - 1);
        if (c.openMs() == tail.openMs()) {
            candles.set(candles.size() - 1, c);
            vTail++;
        } else if (c.openMs() == tail.openMs() + 60_000) {
            candles.add(c);
            while (candles.size() > MAX_CANDLES) {
                candles.remove(0);
            }
            vStruct++;
        } else if (c.openMs() > tail.openMs() + 60_000) {
            candlesLoaded = false; // lacuna: nada de remendar, busca de novo
            wantKlines = true;
            vStruct++;
        } else {
            for (int i = candles.size() - 2; i >= 0; i--) {
                if (candles.get(i).openMs() == c.openMs()) {
                    candles.set(i, c);
                    vStruct++;
                    break;
                }
            }
        }
    }

    // ---- REST (bootstrap e resincronização): uma requisição por vez, fora da thread de trabalho -------------------------------------

    private void pump() {
        long now = clock.getAsLong();
        if (now < blockedUntil) {
            return;
        }
        if (wantSnapshot && !snapshotInflight && pub.open && now >= snapshotNotBefore) {
            wantSnapshot = false;
            snapshotInflight = true;
            int gen = pub.gen;
            runRest(() -> http.get(Allowlist.REST_DEPTH), (resp, err) -> snapshotDone(gen, resp, err));
        }
        if (wantKlines && !klinesInflight && mkt.open && now >= klinesNotBefore) {
            wantKlines = false;
            klinesInflight = true;
            int gen = mkt.gen;
            runRest(() -> http.get(Allowlist.REST_KLINES), (resp, err) -> klinesDone(gen, resp, err));
        }
    }

    private interface Call {
        HttpGet.Response run() throws java.io.IOException;
    }

    private void runRest(Call call, java.util.function.BiConsumer<HttpGet.Response, String> done) {
        rest.execute(() -> {
            HttpGet.Response resp = null;
            String err = null;
            try {
                resp = call.run();
            } catch (MarketException e) {
                err = e.code;
            } catch (java.io.IOException | RuntimeException e) {
                err = "transport_error";
            }
            HttpGet.Response r = resp;
            String er = err;
            if (!post(() -> done.accept(r, er))) {
                post(() -> { }); // fila cheia: o tick seguinte rearma (flags abaixo)
            }
        });
    }

    private boolean restOk(HttpGet.Response resp, String err) {
        if (err != null) {
            Log.event("market_rest_failed", err);
            return false;
        }
        if (resp.usedWeight() >= 0) {
            lastRestWeight = resp.usedWeight();
        }
        if (resp.status() == 429 || resp.status() == 418) {
            rateBlock(resp.retryAfterSec());
            return false;
        }
        if (resp.status() != 200) {
            Log.event("market_rest_failed", "status_" + resp.status());
            return false;
        }
        return true;
    }

    private void snapshotDone(int gen, HttpGet.Response resp, String err) {
        snapshotInflight = false;
        if (!running || gen != pub.gen) {
            wantSnapshot = pub.open;
            return;
        }
        boolean ok = restOk(resp, err);
        if (ok) {
            try {
                apply(book.onSnapshot(MarketEvents.depthSnapshot(resp.body())));
                noteBook();
                dirty = true;
                return;
            } catch (MarketException e) {
                Log.event("market_rest_rejected", "code=" + e.code + " reason=" + e.reason + " stream=" + MarketStream.REST_DEPTH + " bytes=" + resp.body().length);
            }
        }
        resyncStreak++;
        snapshotNotBefore = clock.getAsLong() + snapshotDelay();
        wantSnapshot = true;
    }

    private void klinesDone(int gen, HttpGet.Response resp, String err) {
        klinesInflight = false;
        if (!running || gen != mkt.gen) {
            wantKlines = mkt.open;
            return;
        }
        if (restOk(resp, err)) {
            try {
                List<Kline> ks = MarketEvents.klines(resp.body(), clock.getAsLong());
                candles.clear();
                for (Kline k : ks) {
                    candles.add(new Candle(k.openMs(), k.open(), k.high(), k.low(), k.close(), k.volume()));
                }
                candlesLoaded = true;
                klinesFailures = 0;
                vStruct++;
                dirty = true;
                return;
            } catch (MarketException e) {
                Log.event("market_rest_rejected", "code=" + e.code + " reason=" + e.reason + " stream=" + MarketStream.REST_KLINES + " bytes=" + resp.body().length);
            }
        }
        klinesFailures++;
        klinesNotBefore = clock.getAsLong() + backoff(klinesFailures);
        wantKlines = true;
    }

    // ---- relógio de 1 tick: reconexão, rotação, silêncio, frescor ---------------------------------------------------------------------

    private void tick() {
        if (!running) {
            return;
        }
        long now = clock.getAsLong();
        for (Conn c : List.of(pub, mkt)) {
            if (!c.open) {
                if (c.retryAt != 0 && now >= c.retryAt && now >= blockedUntil) {
                    connect(c);
                } else if (c.retryAt == 0 && c.handle == null) {
                    c.retryAt = now; // nunca fica sem ninguém para reconectar
                }
                continue;
            }
            if (c.forceReconnect) {
                c.forceReconnect = false;
                dropForReconnect(c, false);
            } else if (now - c.lastMsg > cfg.silenceMs()) {
                Log.event("market_silence", c.name);
                dropForReconnect(c, false);
            } else if (now - c.openedAt >= c.rotateAfterMs) {
                Log.event("market_rotation", c.name);
                rotations++;
                dropForReconnect(c, true);
            } else if (!c.wasStable && now - c.openedAt >= cfg.stableMs()) {
                c.wasStable = true;
                c.failures = 0;
            }
        }
        if (blockStreak > 0 && now > blockedUntil + cfg.stableMs() * 4 && ready()) {
            blockStreak = 0;
        }
        if (book.state() == DepthBook.State.LIVE && now - bookLiveSince >= cfg.stableMs()) {
            resyncStreak = 0;
        }
        pump();
        publish(false);
    }

    private void dropForReconnect(Conn c, boolean planned) {
        c.gen++; // ignora tudo o que ainda estiver em voo desta conexão
        closeHandle(c);
        c.open = false;
        c.planned = false;
        if (c == pub) {
            book.invalidate(planned ? MarketReason.BOOK_ROTATION : MarketReason.BOOK_RECONNECT);
            snapshotInflight = false;
        }
        long now = clock.getAsLong();
        if (planned) {
            c.failures = 0;
            c.retryAt = now;
        } else {
            c.failures++;
            c.retryAt = now + backoff(c.failures);
        }
        reconnects++;
        dirty = true;
    }

    private boolean ready() {
        return pub.open && mkt.open && candlesLoaded && tickerSeen && bookLiveOnce;
    }

    // ---- publicação ---------------------------------------------------------------------------------------------------------------

    private void publish(boolean force) {
        long now = clock.getAsLong();
        long lastMsg = Math.max(pub.lastMsg, mkt.lastMsg);
        Feed feed;
        String reason;
        if (!running) {
            feed = Feed.DISCONNECTED;
            reason = "idle";
        } else if (now < blockedUntil) {
            feed = Feed.ERROR;
            reason = "rate_limited";
        } else if (ready()) {
            wasLive = true;
            boolean stale = now - lastMsg > cfg.staleAfterMs();
            feed = stale ? Feed.STALE : Feed.LIVE;
            reason = stale ? "no_data" : "ok";
        } else if (wasLive) {
            feed = Feed.RECONNECTING;
            reason = !pub.open || !mkt.open ? "connection_lost" : "resyncing";
        } else {
            feed = Feed.CONNECTING;
            reason = "connecting";
        }
        BookState bs = book.state() == DepthBook.State.LIVE ? BookState.LIVE : bookLiveOnce || wasLive ? BookState.RESYNCING : BookState.SYNCING;
        if (feed != shownFeed || !reason.equals(shownReason) || bs != shownBook) {
            shownFeed = feed;
            shownReason = reason;
            shownBook = bs;
            vState++;
            vBook++;
            dirty = true;
        }
        if (!force && (!dirty || now - lastPublish < cfg.publishMs())) {
            return;
        }
        lastPublish = now;
        dirty = false;
        List<double[]> bids = book.topBids(BOOK_LEVELS_OUT);
        List<double[]> asks = book.topAsks(BOOK_LEVELS_OUT);
        view = new MarketView(feed, reason, bs, lastMsg, now, last, mark, index, funding, ticker, bids, asks,
                List.copyOf(candles), List.copyOf(trades), vState, vBook, vTrades, vStruct, vTail, reconnects, rotations, resyncs, rejected);
    }

    // ---- inspeção (testes) ---------------------------------------------------------------------------------------------------------

    long lastRestWeight() {
        return lastRestWeight;
    }

    boolean rateBlocked() {
        return clock.getAsLong() < blockedUntil;
    }
}
