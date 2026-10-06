package byx.service.market;

import static byx.service.market.FakeMarket.aggTrade;
import static byx.service.market.FakeMarket.await;
import static byx.service.market.FakeMarket.depth;
import static byx.service.market.FakeMarket.driveToLive;
import static byx.service.market.FakeMarket.kline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.market.MarketView.BookState;
import byx.service.market.MarketView.Feed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketFeedTest {
    private FakeMarket.Ws ws;
    private FakeMarket.Http http;
    private MarketFeed feed;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() {
        Log.redirect(logs::add);
        ws = new FakeMarket.Ws();
        http = new FakeMarket.Http();
    }

    @AfterEach
    void down() {
        if (feed != null) {
            feed.close();
        }
        Log.redirect(null);
    }

    private MarketFeed start(MarketFeed.Config cfg) {
        feed = new MarketFeed(ws, http, cfg);
        feed.acquire();
        return feed;
    }

    @Test
    void idleFeedOpensNothing() throws Exception {
        feed = new MarketFeed(ws, http, FakeMarket.fast());
        Thread.sleep(150);
        assertEquals(Feed.DISCONNECTED, feed.view().feed());
        assertTrue(ws.connects.isEmpty() && http.calls.isEmpty(), "no subscriber, no network");
    }

    @Test
    void usesExactlyTwoRoutedConnectionsAndTheTwoRestBootstraps() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        assertEquals(List.of(Allowlist.WS_PUBLIC, Allowlist.WS_MARKET), ws.connects.stream().sorted(java.util.Comparator.comparing(java.net.URI::toString).reversed()).toList());
        assertEquals(1, http.calls(Allowlist.REST_DEPTH));
        assertEquals(1, http.calls(Allowlist.REST_KLINES));
        assertTrue(http.calls.stream().allMatch(u -> u.equals(Allowlist.REST_DEPTH) || u.equals(Allowlist.REST_KLINES)));
    }

    @Test
    void goesLiveOnlyWhenEverythingIsConsistentAndExposesRealValues() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        MarketView v = feed.view();
        assertEquals(BookState.LIVE, v.book());
        assertEquals(2699.50, v.last());
        assertEquals(2699.41, v.mark());
        assertEquals(2700.91, v.index());
        assertEquals(-0.114, v.ticker().changePct());
        assertEquals(2734.00, v.ticker().high());
        assertEquals(2676.97, v.ticker().low());
        assertEquals(6356094948.14, v.ticker().volumeQuote());
        assertEquals(2700.0, v.bids().get(0)[0]);
        assertEquals(6.0, v.bids().get(0)[1], "the update that aligned with the snapshot was applied");
        assertEquals(2700.01, v.asks().get(0)[0]);
        assertEquals(Allowlist.KLINE_LIMIT, v.candles().size());
        assertTrue(v.candles().get(0).openMs() < v.candles().get(1).openMs(), "real, ordered timestamps");
        assertEquals(1, v.trades().size());
        assertTrue(v.trades().get(0).buy(), "m=false → the buyer was the aggressor");
        assertTrue(v.updatedAtMs() > 0);
    }

    @Test
    void notLiveBeforeTheBookIsAligned() throws Exception {
        start(FakeMarket.fast());
        await(() -> ws.current.size() == 2, 2_000, "connections");
        ws.push(Allowlist.WS_MARKET, FakeMarket.ticker("1", "2", "1", "1", "1"));
        Thread.sleep(200);
        assertEquals(Feed.CONNECTING, feed.view().feed());
        assertTrue(feed.view().bids().isEmpty());
    }

    @Test
    void sequenceGapInvalidatesBookThenResyncsWithANewSnapshot() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(200, "2701.00", "2701.01"));
        ws.push(Allowlist.WS_PUBLIC, depth(106, 110, 105, "[]", "[]")); // contínuo
        ws.push(Allowlist.WS_PUBLIC, depth(120, 130, 115, "[[\"2700.00\",\"99\"]]", "[]")); // gap: pu 115 != 110
        await(() -> feed.view().book() == BookState.RESYNCING, 2_000, "RESYNCING");
        assertTrue(feed.view().bids().isEmpty() && feed.view().asks().isEmpty(), "an inconsistent book is never shown");
        assertEquals(Feed.LIVE, feed.view().feed(), "the rest of the market keeps flowing");
        await(() -> http.calls(Allowlist.REST_DEPTH) == 2, 2_000, "second snapshot");
        ws.push(Allowlist.WS_PUBLIC, depth(190, 205, 130, "[]", "[]"));
        await(() -> feed.view().book() == BookState.LIVE, 2_000, "book rebuilt");
        assertEquals(2701.00, feed.view().bids().get(0)[0], "rebuilt from the NEW snapshot, not from the broken stream");
        assertTrue(feed.view().resyncs() >= 1);
    }

    @Test
    void wrongSymbolOrMarketTypeOnDepthInvalidatesTheBook() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        ws.push(Allowlist.WS_PUBLIC, FakeMarket.envelope("ethusdt@depth@100ms", "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"st\":2,\"U\":106,\"u\":107,\"pu\":105,\"b\":[],\"a\":[]}"));
        await(() -> feed.view().book() == BookState.RESYNCING, 2_000, "RESYNCING on wrong market type");
        assertTrue(feed.view().rejected() >= 1);
    }

    @Test
    void wrongStreamNameOnARouteIsRejected() throws Exception {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        long before = feed.view().rejected();
        ws.push(Allowlist.WS_MARKET, FakeMarket.envelope("btcusdt@aggTrade", "{}"));
        await(() -> feed.view().rejected() > before, 2_000, "rejection");
        assertEquals(2699.50, feed.view().last());
    }

    @Test
    void klineUpdatesIncrementallyAndAppendsOnRollover() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        long tail = feed.view().candles().get(feed.view().candles().size() - 1).openMs();
        long structBefore = feed.view().vCandlesStruct();
        ws.push(Allowlist.WS_MARKET, kline(tail, "2700.00", "2705.00", "2699.00", "2704.00", false));
        await(() -> feed.view().candles().get(feed.view().candles().size() - 1).close() == 2704.0, 2_000, "in-place update");
        assertEquals(structBefore, feed.view().vCandlesStruct(), "an in-place update is not a structural change");
        assertEquals(Allowlist.KLINE_LIMIT, feed.view().candles().size());
        ws.push(Allowlist.WS_MARKET, kline(tail + 60_000, "2704.00", "2706.00", "2703.00", "2705.00", false));
        await(() -> feed.view().candles().get(feed.view().candles().size() - 1).openMs() == tail + 60_000, 2_000, "append");
        assertEquals(Allowlist.KLINE_LIMIT, feed.view().candles().size(), "bounded");
        assertTrue(feed.view().vCandlesStruct() > structBefore);
    }

    @Test
    void candleGapTriggersANewBootstrapInsteadOfPatching() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        long tail = feed.view().candles().get(feed.view().candles().size() - 1).openMs();
        ws.push(Allowlist.WS_MARKET, kline(tail + 5 * 60_000, "1", "2", "1", "2", false));
        await(() -> http.calls(Allowlist.REST_KLINES) == 2, 2_000, "second klines bootstrap");
    }

    @Test
    void tradesAreDedupedBoundedAndDirectional() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        for (int i = 11; i < 11 + MarketFeed.MAX_TRADES + 20; i++) {
            ws.push(Allowlist.WS_MARKET, aggTrade(i, "2700.00", "1", i % 2 == 0));
        }
        ws.push(Allowlist.WS_MARKET, aggTrade(15, "9999", "1", true)); // id antigo: ignorado
        await(() -> feed.view().last() != null && feed.view().trades().size() == MarketFeed.MAX_TRADES, 2_000, "trades");
        assertEquals(MarketFeed.MAX_TRADES, feed.view().trades().size());
        assertTrue(feed.view().trades().stream().noneMatch(t -> t.price() == 9999));
        assertFalse(feed.view().trades().get(0).buy(), "newest first; id 80 even → buyer is maker → seller aggressed");
    }

    @Test
    void reconnectLoopIsBoundedByBackoff() throws Exception {
        ws.failConnect = true;
        start(FakeMarket.fast()); // base 40 ms, teto 320 ms
        Thread.sleep(2_000);
        int attempts = ws.connects.size();
        // sem recuo seriam ~200 tentativas por conexão; com recuo (40,80,160,320,320…, com jitter ≥ metade) cabem no máximo ~14 por conexão
        assertTrue(attempts <= 30, "bounded reconnect attempts: " + attempts);
        assertTrue(attempts >= 4, "but it does keep trying: " + attempts);
        assertTrue(feed.view().feed() == Feed.CONNECTING, "never claims LIVE: " + feed.view().feed());
    }

    @Test
    void backoffGrowsWithJitterAndStaysCapped() {
        MarketFeed f = new MarketFeed(ws, http, new MarketFeed.Config(1_000, 60_000, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), System::currentTimeMillis, new java.util.Random(7));
        long prevCeil = 0;
        for (int n = 1; n <= 12; n++) {
            long d = f.backoff(n);
            long ceil = Math.min(60_000, 1_000L << (n - 1));
            assertTrue(d >= ceil / 2 && d <= ceil, "attempt " + n + " delay " + d + " within [" + ceil / 2 + "," + ceil + "]");
            assertTrue(ceil >= prevCeil);
            prevCeil = ceil;
        }
        f.close();
    }

    @Test
    void connectionLossReconnectsAndOnlyBecomesLiveAgainAfterResync() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(500, "2710.00", "2710.01"));
        ws.current.get(Allowlist.WS_PUBLIC).onClosed(1006);
        await(() -> feed.view().feed() == Feed.RECONNECTING, 2_000, "RECONNECTING");
        assertTrue(feed.view().bids().isEmpty(), "book dropped with the connection");
        assertNotNull(feed.view().last(), "last known price is retained (the panel marks it stale)");
        await(() -> ws.count(Allowlist.WS_PUBLIC) == 2, 2_000, "public reconnect");
        assertNotEquals(Feed.LIVE, feed.view().feed());
        await(() -> http.calls(Allowlist.REST_DEPTH) == 2, 2_000, "new snapshot");
        ws.push(Allowlist.WS_PUBLIC, depth(495, 505, 400, "[]", "[]"));
        await(() -> feed.view().feed() == Feed.LIVE && feed.view().book() == BookState.LIVE, 3_000, "LIVE again");
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(a.equals(b));
    }

    @Test
    void marketReconnectRefetchesCandlesAndWaitsForTicker() {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        ws.current.get(Allowlist.WS_MARKET).onClosed(1006);
        await(() -> feed.view().feed() == Feed.RECONNECTING, 2_000, "RECONNECTING");
        await(() -> http.calls(Allowlist.REST_KLINES) == 2, 2_000, "klines refetched");
        assertNotEquals(Feed.LIVE, feed.view().feed()); // ticker ainda não chegou
        await(() -> ws.count(Allowlist.WS_MARKET) == 2, 2_000, "market reconnect");
        ws.push(Allowlist.WS_MARKET, FakeMarket.ticker("1", "2", "1", "1", "1"));
        await(() -> feed.view().feed() == Feed.LIVE, 3_000, "LIVE again");
    }

    @Test
    void scheduledRotationIsNotAFailure() {
        MarketFeed.Config c = new MarketFeed.Config(40, 320, 400, 0, 30_000, 10_000, 200, 20, 20, 20, 160, 100, 400, 4_000);
        start(c);
        driveToLive(feed, ws);
        await(() -> feed.view().rotations() >= 1, 3_000, "rotation");
        await(() -> ws.count(Allowlist.WS_PUBLIC) >= 2 && ws.count(Allowlist.WS_MARKET) >= 2, 2_000, "both rotated");
        assertTrue(logs.stream().noneMatch(l -> l.contains("market_connection_lost")), "rotation is expected, never logged as a lost connection");
        assertNotEquals(Feed.ERROR, feed.view().feed());
        assertNotEquals(Feed.DISCONNECTED, feed.view().feed());
    }

    @Test
    void serverClosingAfterTheDocumentedLifetimeCountsAsExpected() {
        MarketFeed.Config c = new MarketFeed.Config(10_000, 60_000, 300, 0, 30_000, 10_000, 200, 20, 20, 20, 160, 100, 400, 4_000);
        start(c);
        await(() -> ws.current.size() == 2, 2_000, "connections");
        try {
            Thread.sleep(350);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        ws.current.get(Allowlist.WS_MARKET).onClosed(1001);
        // com recuo de falha de 10 s o teste não veria reconexão em 2 s; o fechamento "aos 24 h" reconecta já
        await(() -> ws.count(Allowlist.WS_MARKET) >= 2, 2_000, "immediate reconnect after expected close");
    }

    @Test
    void rateLimitOnRestPausesAllActivityAndFailsClosed() throws Exception {
        http.depth = () -> FakeMarket.status(429, 1);
        start(new MarketFeed.Config(40, 320, 3_600_000, 0, 30_000, 10_000, 200, 20, 20, 20, 160, 100, 600, 4_000));
        await(() -> feed.view().feed() == Feed.ERROR, 3_000, "ERROR rate_limited");
        assertEquals("rate_limited", feed.view().reason());
        int depthCalls = http.calls(Allowlist.REST_DEPTH);
        int connects = ws.connects.size();
        Thread.sleep(300); // dentro da janela de bloqueio (mínimo 600 ms): nada de insistir
        assertEquals(depthCalls, http.calls(Allowlist.REST_DEPTH), "no REST while rate limited");
        assertEquals(connects, ws.connects.size(), "no new connection attempts while rate limited");
        assertNotEquals(Feed.LIVE, feed.view().feed());
    }

    @Test
    void handshakeRefusedWith418BansActivityToo() throws Exception {
        ws.refuseWith = 418;
        start(new MarketFeed.Config(40, 320, 3_600_000, 0, 30_000, 10_000, 200, 20, 20, 20, 160, 100, 800, 4_000));
        await(() -> feed.view().feed() == Feed.ERROR, 3_000, "ERROR");
        int connects = ws.connects.size();
        Thread.sleep(300);
        assertEquals(connects, ws.connects.size(), "no reconnect during the ban window");
    }

    @Test
    void hugeOrBrokenRestResponsesDoNotCrashAndAreRetriedWithDelay() throws Exception {
        http.depth = () -> {
            throw new RuntimeException("boom");
        };
        http.klines = () -> FakeMarket.ok("[[not json");
        start(FakeMarket.fast());
        Thread.sleep(800);
        assertNotEquals(Feed.LIVE, feed.view().feed());
        assertTrue(http.calls(Allowlist.REST_DEPTH) >= 2 && http.calls(Allowlist.REST_DEPTH) < 40, "retries with backoff: " + http.calls(Allowlist.REST_DEPTH));
        assertTrue(http.calls(Allowlist.REST_KLINES) < 40);
    }

    @Test
    void stalesWhenNothingArrives() {
        MarketFeed.Config c = new MarketFeed.Config(40, 320, 3_600_000, 0, 30_000, 250, 200, 20, 20, 20, 160, 100, 400, 4_000);
        start(c);
        driveToLive(feed, ws);
        await(() -> feed.view().feed() == Feed.STALE, 3_000, "STALE");
        assertEquals("no_data", feed.view().reason());
        assertNotNull(feed.view().last(), "last values stay available, marked stale by the feed state");
        ws.push(Allowlist.WS_MARKET, aggTrade(50, "2700", "1", false));
        await(() -> feed.view().feed() == Feed.LIVE, 2_000, "back to LIVE with fresh data");
    }

    @Test
    void silentConnectionIsRecycled() {
        MarketFeed.Config c = new MarketFeed.Config(40, 320, 3_600_000, 0, 250, 100, 200, 20, 20, 20, 160, 100, 400, 4_000);
        start(c);
        driveToLive(feed, ws);
        await(() -> ws.count(Allowlist.WS_PUBLIC) >= 2, 3_000, "silent connection replaced");
    }

    @Test
    void messageFloodOverflowsTheBoundedQueueAndReconnectsInsteadOfGrowing() throws Exception {
        List<String> sink = Collections.synchronizedList(new ArrayList<>());
        Log.redirect(m -> {
            sink.add(m);
            try {
                Thread.sleep(1); // log lento: o trabalhador não acompanha a rajada
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start(FakeMarket.fast());
        await(() -> ws.current.containsKey(Allowlist.WS_MARKET), 2_000, "connection");
        var l = ws.current.get(Allowlist.WS_MARKET);
        long start = System.nanoTime();
        for (int i = 0; i < 30_000; i++) {
            l.onText("garbage"); // cada mensagem recusada gera um log
        }
        assertTrue(System.nanoTime() - start < 20_000_000_000L, "producer is never blocked by the consumer");
        await(() -> ws.closed(Allowlist.WS_MARKET) >= 1, 3_000, "overloaded connection aborted");
        await(() -> ws.count(Allowlist.WS_MARKET) >= 2, 5_000, "and reconnected");
    }

    @Test
    void canaryInNetworkContentNeverReachesTheLog() throws Exception {
        String canary = "CANARY_SECRET_9f3a7c";
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        ws.push(Allowlist.WS_MARKET, "{\"stream\":\"" + canary + "\",\"data\":{\"x\":\"" + canary + "\"}}");
        ws.push(Allowlist.WS_MARKET, "not json " + canary);
        ws.push(Allowlist.WS_MARKET, FakeMarket.envelope("ethusdt@aggTrade", "{\"e\":\"aggTrade\",\"s\":\"" + canary + "\",\"a\":99,\"p\":\"1\",\"q\":\"1\",\"T\":1,\"m\":true}"));
        ws.push(Allowlist.WS_PUBLIC, FakeMarket.envelope("ethusdt@depth@100ms", "{\"e\":\"depthUpdate\",\"s\":\"" + canary + "\"}"));
        http.klines = () -> FakeMarket.ok("{\"msg\":\"" + canary + "\"}");
        http.fail = new MarketException("transport_error", new RuntimeException(canary));
        ws.current.get(Allowlist.WS_MARKET).onError("transport_error", -1);
        Thread.sleep(400);
        assertFalse(logs.isEmpty(), "there are events to inspect");
        assertTrue(logs.stream().noneMatch(l -> l.contains(canary)), "log only carries fixed codes: " + logs);
        assertFalse(feed.view().toString().contains(canary));
    }

    @Test
    void feedStopsAfterGraceWhenLastSubscriberLeaves() throws Exception {
        start(FakeMarket.fast()); // graça 100 ms
        driveToLive(feed, ws);
        feed.release();
        await(() -> feed.view().feed() == Feed.DISCONNECTED, 3_000, "idle stop");
        assertTrue(ws.closed(Allowlist.WS_PUBLIC) >= 1 && ws.closed(Allowlist.WS_MARKET) >= 1, "sockets closed");
        assertNull(feed.view().last(), "data is not carried over to a later session");
        int c = ws.connects.size();
        Thread.sleep(300);
        assertEquals(c, ws.connects.size(), "nothing reconnects while idle");
    }

    @Test
    void acquireDuringGraceCancelsTheStop() throws Exception {
        start(FakeMarket.fast());
        driveToLive(feed, ws);
        feed.release();
        feed.acquire();
        Thread.sleep(400);
        assertEquals(Feed.LIVE, feed.view().feed());
        assertEquals(1, feed.subscribers());
    }

    private static void assertNull(Object o, String m) {
        assertTrue(o == null, m);
    }
}
