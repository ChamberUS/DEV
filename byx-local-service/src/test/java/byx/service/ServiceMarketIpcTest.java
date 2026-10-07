package byx.service;

import static byx.service.market.FakeMarket.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.market.Allowlist;
import byx.service.market.FakeMarket;
import byx.service.market.MarketFeed;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Protocolo local de mercado: operações tipadas, assinatura com estado mais novo, limites de quadro/assinantes, cliente lento. */
class ServiceMarketIpcTest {
    private Path home;
    private ServiceInstance service;
    private FakeMarket.Ws ws;
    private FakeMarket.Http http;
    private MarketFeed feed;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "bm");
        Log.redirect(logs::add);
        ws = new FakeMarket.Ws();
        http = new FakeMarket.Http();
        feed = new MarketFeed(ws, http, FakeMarket.fast());
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 600, 600, 2_000, 600), feed);
    }

    @AfterEach
    void down() throws Exception {
        service.close();
        Log.redirect(null);
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private TestClient paired() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        return c;
    }

    private static JsonNode untilTopic(TestClient c, String topic) throws IOException {
        for (int i = 0; i < 40; i++) {
            JsonNode e = c.readEvent();
            if (topic.equals(e.path("topic").asText())) {
                return e;
            }
        }
        throw new AssertionError("no " + topic + " event");
    }

    private void live() {
        FakeMarket.driveToLive(feed, ws);
    }

    @Test
    void capabilityIsSeparateFromFeedState() throws Exception {
        try (TestClient c = paired()) {
            JsonNode caps = c.call("capabilities").path("result");
            assertTrue(caps.path("features").path("marketData").asBoolean(), "capability supported");
            assertFalse(caps.path("features").path("accountData").asBoolean(true));
            assertFalse(caps.path("features").path("notifications").asBoolean(true));
            assertFalse(caps.path("features").path("adminOperations").asBoolean(true));
            List<?> ops = TestClient.JSON.convertValue(caps.path("operations"), List.class);
            assertEquals(List.of("byx.bank.balance", "byx.certificados.getCertificate", "byx.certificados.listByMerchant", "byx.denomMetadata", "byx.feesplit.params", "byx.lojas.getMerchant", "byx.lojas.listMerchants", "byx.moduleHealth", "byx.payments.getPayment", "byx.payments.listByStore", "byx.payments.params", "byx.status", "byx.supply", "capabilities", "health", "market.status", "market.subscribe", "market.unsubscribe", "version"), ops);
            JsonNode st = c.call("market.status").path("result");
            assertEquals("DISCONNECTED", st.path("feed").asText(), "marketData=true with feed=DISCONNECTED is valid");
            assertEquals(0, st.path("subscribers").asInt());
            assertTrue(ws.connects.isEmpty(), "asking for status never opens the feed");
        }
    }

    @Test
    void withoutAFeedTheMarketOperationsAreUnsupported() throws Exception {
        service.close();
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 600, 600, 2_000, 600));
        try (TestClient c = paired()) {
            assertFalse(c.call("capabilities").path("result").path("features").path("marketData").asBoolean(true));
            for (String op : List.of("market.status", "market.subscribe", "market.unsubscribe")) {
                JsonNode r = c.call(op);
                assertFalse(r.path("ok").asBoolean(true), op);
                assertEquals("unsupported_operation", r.path("error").path("code").asText());
            }
        }
    }

    @Test
    void anythingUntypedIsStillRefused() throws Exception {
        try (TestClient c = paired()) {
            for (String op : List.of("proxy", "request", "subscribe", "market", "market.", "market.raw", "market.subscribe.extra", "market.snapshot?url=x", "binance.order")) {
                JsonNode r = c.call(op);
                assertEquals("unsupported_operation", r.path("error").path("code").asText(), op);
            }
            c.sendJson("{\"v\":1,\"id\":\"x\",\"op\":\"market.subscribe\",\"url\":\"wss://evil/\"}");
            assertEquals("bad_request", c.readJson().path("code").asText(), "no argument is accepted: the panel cannot choose host, path or symbol");
        }
        assertEquals(0, feed.subscribers());
        assertTrue(ws.connects.isEmpty());
    }

    @Test
    void subscribeStreamsTypedEventsWithRealValuesWithinTheMarketFrameCap() throws Exception {
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"s1\",\"op\":\"market.subscribe\"}");
            JsonNode resp = c.readJson();
            assertTrue(resp.path("ok").asBoolean() && resp.path("result").path("streaming").asBoolean());
            live();
            JsonNode state = null;
            for (int i = 0; i < 40 && (state == null || !"LIVE".equals(state.path("feed").asText())); i++) {
                JsonNode e = untilTopic(c, "state");
                state = e;
            }
            assertEquals("LIVE", state.path("feed").asText());
            assertEquals("ETHUSDT", state.path("symbol").asText());
            assertEquals(2699.50, state.path("last").asDouble());
            assertEquals(2699.41, state.path("mark").asDouble());
            assertEquals(-0.114, state.path("ticker24h").path("changePct").asDouble());
            JsonNode book = untilTopic(c, "book");
            JsonNode trades = untilTopic(c, "trades");
            JsonNode candles = untilTopic(c, "candles");
            assertTrue(candles.path("candles").size() == Allowlist.KLINE_LIMIT);
            assertEquals(6, candles.path("candles").get(0).size());
            assertTrue(trades.path("trades").size() <= MarketFeed.MAX_TRADES);
            assertTrue(book.path("bids").size() <= MarketFeed.BOOK_LEVELS_OUT && book.path("asks").size() <= MarketFeed.BOOK_LEVELS_OUT);
            for (JsonNode e : List.of(state, book, trades, candles)) {
                assertEquals("event", e.path("type").asText());
                assertTrue(e.path("seq").asLong() > 0);
            }
            assertEquals(1, service.activeSubscribers());
        }
    }

    @Test
    void worstCaseEventsFitTheMarketFrameCapAndTheNormalCapStaysAtEightKiB() {
        assertEquals(8 * 1024, Protocol.MAX_FRAME);
        assertEquals(16 * 1024, Protocol.MAX_MARKET_FRAME);
        // pior caso: 120 candles com números longos, 50 trades, 20+20 níveis
        var m = Protocol.mapper();
        var arr = m.createArrayNode();
        for (int i = 0; i < 120; i++) {
            arr.addArray().add(1791265920000L + i * 60_000L).add(12345.678901234567).add(12345.678901234567).add(12345.678901234567).add(12345.678901234567).add(123456789.123456789);
        }
        var n = m.createObjectNode();
        n.put("v", 1).put("type", "event").put("topic", "candles").put("seq", 9_999_999_999L).put("interval", "1m").set("candles", arr);
        assertTrue(n.toString().getBytes().length < Protocol.MAX_MARKET_FRAME, "worst candles frame " + n.toString().length());
        var t = m.createArrayNode();
        for (int i = 0; i < 50; i++) {
            t.addArray().add(1791265933318L).add(12345.678901234567).add(123456789.123456789).add(1);
        }
        assertTrue(t.toString().length() < Protocol.MAX_MARKET_FRAME);
    }

    @Test
    void clientRejectsOversizedDeclaredEventWithoutAllocating() throws Exception {
        // o servidor nunca emite acima do teto; e o leitor recusa um cabeçalho acima dele antes de alocar o corpo
        var in = new java.io.ByteArrayInputStream(new byte[] {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        assertEquals("frame_size", assertThrows(Frames.FrameException.class, () -> Frames.read(in, Protocol.MAX_MARKET_FRAME)).code);
        var big = new java.io.ByteArrayOutputStream();
        assertThrows(Frames.FrameException.class, () -> Frames.write(big, new byte[Protocol.MAX_MARKET_FRAME + 1], Protocol.MAX_MARKET_FRAME));
    }

    @Test
    void secondSubscribeOnTheSameConnectionIsRefusedAndUnsubscribeIsIdempotent() throws Exception {
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"s1\",\"op\":\"market.subscribe\"}");
            assertTrue(c.readJson().path("ok").asBoolean());
            c.sendJson("{\"v\":1,\"id\":\"s2\",\"op\":\"market.subscribe\"}");
            JsonNode r = null;
            for (int i = 0; i < 40; i++) {
                JsonNode e = c.readEvent();
                if ("s2".equals(e.path("id").asText())) {
                    r = e;
                    break;
                }
            }
            assertEquals("already_subscribed", r.path("error").path("code").asText());
            c.sendJson("{\"v\":1,\"id\":\"u1\",\"op\":\"market.unsubscribe\"}");
            for (int i = 0; i < 40; i++) {
                JsonNode e = c.readEvent();
                if ("u1".equals(e.path("id").asText())) {
                    assertTrue(e.path("ok").asBoolean());
                    break;
                }
            }
            await(() -> service.activeSubscribers() == 0, 2_000, "unsubscribed");
            c.sendJson("{\"v\":1,\"id\":\"u2\",\"op\":\"market.unsubscribe\"}");
            JsonNode again = c.readEvent();
            while (!"u2".equals(again.path("id").asText())) {
                again = c.readEvent();
            }
            assertTrue(again.path("ok").asBoolean());
            assertEquals(0, service.activeSubscribers());
        }
    }

    @Test
    void subscriberCapHoldsAndReconnectingLocallyDoesNotAccumulate() throws Exception {
        List<TestClient> open = new ArrayList<>();
        try {
            for (int i = 0; i < ServiceInstance.MAX_SUBSCRIBERS; i++) {
                TestClient c = paired();
                open.add(c);
                c.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
                assertTrue(c.readJson().path("ok").asBoolean());
            }
            await(() -> service.activeSubscribers() == ServiceInstance.MAX_SUBSCRIBERS, 2_000, "cap reached");
            assertEquals(ServiceInstance.MAX_SUBSCRIBERS, feed.subscribers());
            try (TestClient extra = paired()) {
                extra.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
                assertEquals("too_many_subscribers", extra.readJson().path("error").path("code").asText());
            }
            // "reconectar" local: fecha uma e abre outra — o número de assinantes não cresce
            open.remove(0).close();
            await(() -> service.activeSubscribers() == ServiceInstance.MAX_SUBSCRIBERS - 1, 3_000, "old subscriber released on close");
            TestClient again = paired();
            open.add(again);
            again.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
            assertTrue(again.readJson().path("ok").asBoolean());
            await(() -> service.activeSubscribers() == ServiceInstance.MAX_SUBSCRIBERS, 2_000, "back to cap, not above");
            assertEquals(ServiceInstance.MAX_SUBSCRIBERS, feed.subscribers());
        } finally {
            open.forEach(TestClient::close);
        }
        await(() -> service.activeSubscribers() == 0 && feed.subscribers() == 0, 3_000, "all released");
    }

    @Test
    void disconnectingCancelsTheSubscriptionAndTheFeedStopsAfterGrace() throws Exception {
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
            assertTrue(c.readJson().path("ok").asBoolean());
            await(() -> ws.current.size() == 2, 2_000, "feed started by the subscription");
        }
        await(() -> service.activeSubscribers() == 0, 3_000, "subscription cancelled");
        await(() -> feed.view().feed() == byx.service.market.MarketView.Feed.DISCONNECTED, 3_000, "feed idle after grace");
    }

    @Test
    void slowSubscriberIsCutOffWithoutAffectingTheServiceOrTheFeed() throws Exception {
        try (TestClient slow = paired()) {
            slow.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
            assertTrue(slow.readJson().path("ok").asBoolean());
            live();
            // o cliente lento NUNCA lê; o feed segue produzindo trades de tamanho grande e o buffer do socket acaba enchendo
            var l = ws.current.get(Allowlist.WS_MARKET);
            long id = 1_000;
            long end = System.currentTimeMillis() + 25_000;
            while (service.activeSubscribers() > 0 && System.currentTimeMillis() < end) {
                for (int i = 0; i < 40; i++) {
                    l.onText(FakeMarket.aggTrade(id++, "2700.123456789", "123456.123456789", i % 2 == 0));
                }
                Thread.sleep(50);
            }
            assertEquals(0, service.activeSubscribers(), "the stalled subscriber was dropped by its write deadline");
        }
        // o serviço e o feed continuam saudáveis e atendendo um cliente normal
        try (TestClient ok = paired()) {
            assertTrue(ok.call("health").path("ok").asBoolean());
            assertTrue(ok.call("market.status").path("ok").asBoolean());
        }
        assertTrue(feed.view().trades().size() > 0, "the feed kept ingesting while the client was stalled");
    }

    @Test
    void noSecretAppearsInEventsOrLogs() throws Exception {
        String token = Pairing.encode(TestClient.readToken(home));
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"s\",\"op\":\"market.subscribe\"}");
            c.readJson();
            live();
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                all.append(c.readEvent());
            }
            assertFalse(all.toString().contains(token));
            assertFalse(all.toString().contains(home.toString()));
        }
        assertTrue(logs.stream().noneMatch(l -> l.contains(token)));
    }
}
