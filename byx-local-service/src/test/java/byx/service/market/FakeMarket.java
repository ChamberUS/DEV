package byx.service.market;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Dublês de rede dos testes + construtores de mensagens no formato real da Binance (conferido ao vivo em 2026-10-06). */
public final class FakeMarket {
    private FakeMarket() {
    }

    /** WebSocket falso: guarda o ouvinte de cada conexão para o teste empurrar mensagens, fechar ou falhar. */
    public static final class Ws implements WsTransport {
        public final List<URI> connects = new CopyOnWriteArrayList<>();
        public final Map<URI, Listener> current = new ConcurrentHashMap<>();
        public final Map<URI, AtomicInteger> counts = new ConcurrentHashMap<>();
        public final Map<URI, AtomicInteger> closes = new ConcurrentHashMap<>();
        public volatile boolean autoOpen = true;
        /** Falha o connect (síncrono) de qualquer URI. */
        public volatile boolean failConnect;
        /** Status de handshake recusado enviado em vez de abrir (429/418). */
        public volatile int refuseWith;

        @Override
        public Handle connect(URI uri, Listener l) throws MarketException {
            connects.add(uri);
            counts.computeIfAbsent(uri, k -> new AtomicInteger()).incrementAndGet();
            if (failConnect) {
                throw new MarketException("connect_failed");
            }
            if (refuseWith != 0) {
                int st = refuseWith;
                new Thread(() -> l.onError("handshake_refused", st)).start();
                return () -> { };
            }
            current.put(uri, l);
            if (autoOpen) {
                l.onOpen(); // só enfileira no feed; chega antes de qualquer mensagem empurrada depois, como na rede real
            }
            return () -> closes.computeIfAbsent(uri, k -> new AtomicInteger()).incrementAndGet();
        }

        public int count(URI u) {
            AtomicInteger c = counts.get(u);
            return c == null ? 0 : c.get();
        }

        public int closed(URI u) {
            AtomicInteger c = closes.get(u);
            return c == null ? 0 : c.get();
        }

        public void push(URI u, String message) {
            Listener l = current.get(u);
            if (l != null) {
                l.onText(message);
            }
        }
    }

    public static final class Http implements HttpGet {
        public final List<URI> calls = new CopyOnWriteArrayList<>();
        public volatile Supplier<Response> depth = () -> ok(depthSnapshot(100, "2700.00", "2700.01"));
        public volatile Supplier<Response> klines = () -> ok(klineRows(Allowlist.KLINE_LIMIT));
        public volatile IOException fail;

        @Override
        public Response get(URI uri) throws IOException {
            calls.add(uri);
            if (fail != null) {
                throw fail;
            }
            if (uri.equals(Allowlist.REST_DEPTH)) {
                return depth.get();
            }
            if (uri.equals(Allowlist.REST_KLINES)) {
                return klines.get();
            }
            throw new MarketException("not_allowlisted");
        }

        public int calls(URI u) {
            return (int) calls.stream().filter(u::equals).count();
        }
    }

    public static HttpGet.Response ok(String body) {
        return new HttpGet.Response(200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 5, -1);
    }

    public static HttpGet.Response status(int s, long retryAfter) {
        return new HttpGet.Response(s, new byte[0], -1, retryAfter);
    }

    public static String depthSnapshot(long lastUpdateId, String bid, String ask) {
        java.math.BigDecimal b = new java.math.BigDecimal(bid);
        java.math.BigDecimal a = new java.math.BigDecimal(ask);
        return "{\"lastUpdateId\":" + lastUpdateId + ",\"E\":1,\"T\":1,\"bids\":[[\"" + bid + "\",\"5.000\"],[\"" + b.subtract(java.math.BigDecimal.ONE) + "\",\"2.000\"]],\"asks\":[[\"" + ask
                + "\",\"4.000\"],[\"" + a.add(java.math.BigDecimal.ONE) + "\",\"1.000\"]]}";
    }

    /** n klines de 1 min terminando no minuto atual do relógio de parede (último = em andamento). */
    public static String klineRows(int n) {
        long end = System.currentTimeMillis() / 60_000 * 60_000;
        StringBuilder sb = new StringBuilder("[");
        for (int i = n - 1; i >= 0; i--) {
            long t = end - i * 60_000L;
            if (i != n - 1) {
                sb.append(',');
            }
            sb.append("[").append(t).append(",\"2700.00\",\"2701.00\",\"2699.00\",\"2700.50\",\"10.5\",").append(t + 59_999).append(",\"28000\",5,\"4\",\"10000\",\"0\"]");
        }
        return sb.append(']').toString();
    }

    public static long currentMinute() {
        return System.currentTimeMillis() / 60_000 * 60_000;
    }

    public static String envelope(String stream, String data) {
        return "{\"stream\":\"" + stream + "\",\"data\":" + data + "}";
    }

    public static String depth(long first, long last, long prev, String bids, String asks) {
        return envelope("ethusdt@depth@100ms", "{\"e\":\"depthUpdate\",\"E\":1,\"T\":1,\"s\":\"ETHUSDT\",\"ps\":\"ETHUSDT\",\"U\":" + first + ",\"u\":" + last + ",\"pu\":" + prev
                + ",\"b\":" + bids + ",\"a\":" + asks + "}");
    }

    public static String aggTrade(long id, String price, String qty, boolean buyerIsMaker) {
        return envelope("ethusdt@aggTrade", "{\"e\":\"aggTrade\",\"E\":1,\"a\":" + id + ",\"s\":\"ETHUSDT\",\"p\":\"" + price + "\",\"q\":\"" + qty + "\",\"nq\":\"" + qty
                + "\",\"f\":1,\"l\":1,\"T\":1791265933318,\"m\":" + buyerIsMaker + ",\"st\":1}");
    }

    public static String markPrice(String mark, String index) {
        return envelope("ethusdt@markPrice@1s", "{\"e\":\"markPriceUpdate\",\"E\":1,\"s\":\"ETHUSDT\",\"p\":\"" + mark + "\",\"ap\":\"" + mark + "\",\"P\":\"2702.6\",\"i\":\"" + index
                + "\",\"r\":\"-0.00002264\",\"T\":1791273600000,\"st\":1}");
    }

    public static String ticker(String change, String high, String low, String vol, String quote) {
        return envelope("ethusdt@ticker", "{\"e\":\"24hrTicker\",\"E\":1,\"s\":\"ETHUSDT\",\"ps\":\"ETHUSDT\",\"p\":\"-4.5\",\"P\":\"" + change + "\",\"w\":\"2708.6\",\"c\":\"2699.28\",\"Q\":\"0.1\",\"o\":\"2703.8\",\"h\":\""
                + high + "\",\"l\":\"" + low + "\",\"v\":\"" + vol + "\",\"q\":\"" + quote + "\",\"O\":1,\"C\":2,\"F\":1,\"L\":2,\"n\":3,\"st\":1}");
    }

    public static String kline(long openMs, String o, String h, String l, String c, boolean closed) {
        return envelope("ethusdt@kline_1m", "{\"e\":\"kline\",\"E\":1,\"s\":\"ETHUSDT\",\"k\":{\"t\":" + openMs + ",\"T\":" + (openMs + 59_999) + ",\"s\":\"ETHUSDT\",\"i\":\"1m\",\"f\":1,\"L\":2,\"o\":\"" + o
                + "\",\"c\":\"" + c + "\",\"h\":\"" + h + "\",\"l\":\"" + l + "\",\"v\":\"11.0\",\"n\":9,\"x\":" + closed + ",\"q\":\"1\",\"V\":\"1\",\"Q\":\"1\",\"B\":\"0\"}}");
    }

    public static MarketFeed.Config fast() {
        return new MarketFeed.Config(40, 320, 3_600_000, 0, 30_000, 10_000, 200, 20, 20, 20, 160, 100, 400, 4_000);
    }

    /** Espera uma condição (os testes são assíncronos por natureza: o feed roda em threads próprias). */
    public static void await(java.util.function.BooleanSupplier c, long ms, String what) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what);
            }
        }
        throw new AssertionError("timeout waiting for " + what);
    }

    /** Leva o feed até LIVE alimentando depth/ticker/trades/mark pelas duas conexões falsas. */
    public static void driveToLive(MarketFeed feed, Ws ws) {
        await(() -> ws.current.containsKey(Allowlist.WS_PUBLIC) && ws.current.containsKey(Allowlist.WS_MARKET), 2_000, "both connections");
        ws.push(Allowlist.WS_PUBLIC, depth(90, 99, 80, "[]", "[]")); // antes do snapshot: u < lastUpdateId → descartado
        ws.push(Allowlist.WS_PUBLIC, depth(95, 105, 99, "[[\"2700.00\",\"6.000\"]]", "[]")); // cobre L = 100
        ws.push(Allowlist.WS_MARKET, ticker("-0.114", "2734.00", "2676.97", "2346638.290", "6356094948.14"));
        ws.push(Allowlist.WS_MARKET, markPrice("2699.41", "2700.91"));
        ws.push(Allowlist.WS_MARKET, aggTrade(10, "2699.50", "0.5", false));
        await(() -> feed.view().feed() == MarketView.Feed.LIVE, 3_000, "LIVE");
    }

    public static List<String> logs() {
        return new ArrayList<>();
    }
}
