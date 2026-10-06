package byx.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Negativos de rede PRIMEIRO: host, caminho, esquema, redirecionamento, rota privada/assinada, resposta gigante. Tudo dinâmico. */
class AllowlistTest {
    private final Allowlist allow = Allowlist.production();

    @Test
    void productionAllowlistIsExactlyTheFourPublicRoutes() {
        assertEquals(Set.of(
                "wss://fstream.binance.com/public/stream?streams=ethusdt@depth@100ms",
                "wss://fstream.binance.com/market/stream?streams=ethusdt@aggTrade/ethusdt@markPrice@1s/ethusdt@ticker/ethusdt@kline_1m",
                "https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000",
                "https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT&interval=1m&limit=120"), allow.entries());
        for (URI u : List.of(Allowlist.WS_PUBLIC, Allowlist.WS_MARKET, Allowlist.REST_DEPTH, Allowlist.REST_KLINES)) {
            assertTrue(allow.permits(u));
        }
    }

    @Test
    void differentHostIsDenied() {
        for (String u : List.of(
                "wss://evil.example.com/public/stream?streams=ethusdt@depth@100ms",
                "wss://fstream.binance.com.evil.com/public/stream?streams=ethusdt@depth@100ms",
                "wss://fstream.binance.com@evil.com/public/stream?streams=ethusdt@depth@100ms",
                "wss://evil.com@fstream.binance.com/public/stream?streams=ethusdt@depth@100ms",
                "wss://stream.binancefuture.com/public/stream?streams=ethusdt@depth@100ms",
                "https://api.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000",
                "https://testnet.binancefuture.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000",
                "https://fapi.binance.com.evil.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000",
                "https://127.0.0.1/fapi/v1/depth?symbol=ETHUSDT&limit=1000")) {
            assertFalse(allow.permits(URI.create(u)), u);
        }
    }

    @Test
    void differentPathIsDenied() {
        for (String u : List.of(
                "https://fapi.binance.com/fapi/v1/depth/../order?symbol=ETHUSDT&limit=1000",
                "https://fapi.binance.com/fapi/v1/exchangeInfo",
                "https://fapi.binance.com/fapi/v1/trades?symbol=ETHUSDT",
                "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=ETHUSDT",
                "wss://fstream.binance.com/public/ws/ethusdt@depth@100ms",
                "wss://fstream.binance.com/ws/ethusdt@depth@100ms", // rota legada sem /public, /market
                "wss://fstream.binance.com/market/stream?streams=ethusdt@depth@100ms", // depth pertence à rota pública
                "wss://fstream.binance.com/public/stream?streams=ethusdt@aggTrade")) {
            assertFalse(allow.permits(URI.create(u)), u);
        }
    }

    @Test
    void plainHttpAndPlainWsAreDenied() {
        assertFalse(allow.permits(URI.create("http://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000")));
        assertFalse(allow.permits(URI.create("ws://fstream.binance.com/public/stream?streams=ethusdt@depth@100ms")));
        assertFalse(allow.permits(URI.create("https://fapi.binance.com:8443/fapi/v1/depth?symbol=ETHUSDT&limit=1000")));
        assertFalse(allow.permits(URI.create("https://fapi.binance.com:443/fapi/v1/depth?symbol=ETHUSDT&limit=1000")));
    }

    @Test
    void privateAndSignedRoutesAreDenied() {
        for (String u : List.of(
                "wss://fstream.binance.com/private/ws/someListenKey",
                "wss://fstream.binance.com/private/stream?streams=listenKey",
                "wss://fstream.binance.com/ws/someListenKey",
                "https://fapi.binance.com/fapi/v1/listenKey",
                "https://fapi.binance.com/fapi/v1/order?symbol=ETHUSDT&side=BUY&type=MARKET&quantity=1&timestamp=1&signature=abc",
                "https://fapi.binance.com/fapi/v2/account?timestamp=1&signature=abc",
                "https://fapi.binance.com/fapi/v3/account",
                "https://fapi.binance.com/fapi/v2/positionRisk",
                "https://fapi.binance.com/fapi/v1/allOrders?symbol=ETHUSDT",
                "https://fapi.binance.com/fapi/v1/userTrades?symbol=ETHUSDT",
                "https://fapi.binance.com/fapi/v1/balance",
                "https://fapi.binance.com/fapi/v1/leverage",
                "https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000&signature=abc",
                "https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=1000&timestamp=1")) {
            assertFalse(allow.permits(URI.create(u)), u);
        }
    }

    @Test
    void wrongSymbolLimitOrExtraDecorationIsDenied() {
        for (String u : List.of(
                "https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000",
                "https://fapi.binance.com/fapi/v1/depth?symbol=ethusdt&limit=1000",
                "https://fapi.binance.com/fapi/v1/depth?symbol=ETHUSDT&limit=5000",
                "https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT&interval=1m&limit=1500",
                "https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT&interval=1m&limit=120#frag",
                "wss://fstream.binance.com/public/stream?streams=btcusdt@depth@100ms",
                "wss://fstream.binance.com/public/stream?streams=ethusdt@depth@100ms/btcusdt@depth@100ms")) {
            assertFalse(allow.permits(URI.create(u)), u);
        }
        assertFalse(allow.permits(null));
    }

    @Test
    void transportsRefuseBeforeTouchingTheNetwork() {
        BoundedHttp http = new BoundedHttp(allow);
        JdkWsTransport ws = new JdkWsTransport(allow);
        assertEquals("not_allowlisted", assertThrows(MarketException.class, () -> http.get(URI.create("https://fapi.binance.com/fapi/v1/order"))).code);
        assertEquals("not_allowlisted", assertThrows(MarketException.class, () -> http.get(URI.create("http://127.0.0.1:1/x"))).code);
        assertEquals("not_allowlisted", assertThrows(MarketException.class,
                () -> ws.connect(URI.create("wss://fstream.binance.com/private/ws/k"), new FakeListener())).code);
    }

    @Test
    void redirectIsNeverFollowed() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> {
            targetHits.incrementAndGet();
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes());
            ex.close();
        });
        server.start();
        try {
            URI start = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/start");
            BoundedHttp http = new BoundedHttp(Allowlist.onlyForTests(start), Duration.ofSeconds(3), 1024);
            assertEquals("redirect_refused", assertThrows(MarketException.class, () -> http.get(start)).code);
            assertEquals(0, targetHits.get(), "the redirect target is never requested");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedResponseIsRefusedByDeclaredAndByStreamedSize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/declared", ex -> {
            ex.sendResponseHeaders(200, 10_000_000);
            try {
                ex.getResponseBody().write(new byte[4096]);
            } catch (java.io.IOException ignored) {
                // o cliente fechou
            }
            ex.close();
        });
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0); // sem Content-Length
            try {
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 1000; i++) {
                    ex.getResponseBody().write(chunk);
                }
            } catch (java.io.IOException ignored) {
                // o cliente fechou
            }
            ex.close();
        });
        server.createContext("/limit", ex -> {
            ex.sendResponseHeaders(429, -1);
            ex.getResponseHeaders().add("Retry-After", "7");
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            URI declared = URI.create(base + "/declared");
            URI chunked = URI.create(base + "/chunked");
            BoundedHttp http = new BoundedHttp(Allowlist.onlyForTests(declared, chunked), Duration.ofSeconds(5), 64 * 1024);
            assertEquals("response_too_large", assertThrows(MarketException.class, () -> http.get(declared)).code);
            assertEquals("response_too_large", assertThrows(MarketException.class, () -> http.get(chunked)).code);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceHasNoCustomTrustOrHostnameBypass() throws Exception {
        // guarda secundária (a primária são os testes dinâmicos acima): nada de trust-all nem verificação de hostname própria
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/byx/service/market"))) {
            for (var f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = java.nio.file.Files.readString(f);
                for (String bad : List.of("TrustManager", "HostnameVerifier", "sslContext(", "SSLContext", "disableHostnameVerification", "followRedirects(HttpClient.Redirect.ALWAYS", "Redirect.NORMAL")) {
                    assertFalse(src.contains(bad), f + " contains " + bad);
                }
            }
        }
    }

    private static final class FakeListener implements WsTransport.Listener {
        public void onOpen() {
        }

        public void onText(String m) {
        }

        public void onClosed(int c) {
        }

        public void onError(String c, int s) {
        }
    }
}
