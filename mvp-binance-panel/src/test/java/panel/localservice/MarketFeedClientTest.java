package panel.localservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panel.localservice.FakeMarketService.Mode;
import panel.localservice.MarketData.Link;

/** O cliente de mercado diante do serviço local: contrato tipado, limites, falha fechada, reconexão sem duplicar, cancelamento. */
class MarketFeedClientTest {
    private Path home;
    private FakeMarketService service;
    private MarketFeedClient client;
    private final AtomicInteger notifications = new AtomicInteger();

    @BeforeEach
    void up() throws Exception {
        home = IpcTestFiles.home("mc");
    }

    @AfterEach
    void down() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (service != null) {
            service.close();
        }
        if (home == null) return;
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void start(Mode mode) throws Exception {
        service = new FakeMarketService(home, mode);
        client = new MarketFeedClient(new LocalServiceClient(home), notifications::incrementAndGet);
        client.start();
    }

    private void await(BooleanSupplier c, long ms, String what) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timeout waiting for " + what + " (link=" + (client == null ? "-" : client.snapshot().link()) + ")");
    }

    @Test
    void streamsRealShapedEventsIntoTypedData() throws Exception {
        start(Mode.GOOD);
        await(() -> client.snapshot().candles().size() == 5 && !client.snapshot().trades().isEmpty() && !client.snapshot().bids().isEmpty(), 5_000, "full data");
        MarketData d = client.snapshot();
        assertEquals(Link.STREAMING, d.link());
        assertEquals("LIVE", d.feed());
        assertEquals(2699.5, d.last());
        assertEquals(2699.41, d.mark(), "mark price is its own value, not the last trade");
        assertEquals(2700.91, d.index());
        assertEquals(-0.114, d.ticker().changePct());
        assertEquals(6356094948.14, d.ticker().volumeQuote());
        assertEquals(2699.5, d.bids().get(0).price());
        assertEquals(2699.6, d.asks().get(0).price());
        assertTrue(d.trades().get(0).buy() && !d.trades().get(1).buy());
        assertEquals(java.time.Instant.ofEpochMilli(1791265933318L), d.trades().get(0).time());
        assertEquals(1791265800000L, d.candles().get(0).openTimeMs(), "real candle timestamps are preserved");
        assertNotNull(d.updatedAt());
        await(() -> notifications.get() > 0, 2_000, "coalesced change notification");
    }

    @Test
    void subscribeCarriesNoArgumentsAndNothingElseIsEverRequested() throws Exception {
        start(Mode.GOOD);
        await(() -> client.snapshot().link() == Link.STREAMING && client.snapshot().feed() != null, 5_000, "streaming");
        Thread.sleep(300);
        assertEquals(List.of("{\"v\":1,\"id\":\"m-sub\",\"op\":\"market.subscribe\"}"), service.requests,
                "the panel never picks a host, path, symbol or channel: one fixed typed operation");
    }

    @Test
    void aServiceWithoutTheMarketCapabilityIsUnsupportedNotLive() throws Exception {
        start(Mode.UNSUPPORTED);
        await(() -> client.snapshot().link() == Link.UNSUPPORTED, 5_000, "unsupported");
        assertNull(client.snapshot().last());
        assertFalse(client.snapshot().hasData());
    }

    @Test
    void everyContractViolationDropsTheSessionAndNeverBecomesData() throws Exception {
        for (Mode m : List.of(Mode.BAD_TOPIC, Mode.CROSSED_BOOK, Mode.TOO_MANY_LEVELS, Mode.UNSORTED_BOOK, Mode.NON_INCREASING_SEQ, Mode.BOOK_WITH_LEVELS_WHILE_NOT_LIVE,
                Mode.NAN_PRICE, Mode.WRONG_SYMBOL, Mode.UNKNOWN_FEED, Mode.CANDLE_GAP_ORDER)) {
            down();
            up();
            start(m);
            await(() -> service.accepted.get() >= 1 && client.snapshot().link() == Link.LOST, 5_000, m + " → LOST");
            MarketData d = client.snapshot();
            assertTrue(d.bids().isEmpty() && d.asks().isEmpty(), m + ": a bad/crossed/unsorted book never reaches the UI");
            if (m == Mode.NAN_PRICE || m == Mode.WRONG_SYMBOL || m == Mode.UNKNOWN_FEED) {
                assertNull(d.last(), m + ": the invalid state frame was not applied");
            }
            if (m == Mode.CANDLE_GAP_ORDER) {
                assertTrue(d.candles().isEmpty());
            }
        }
    }

    @Test
    void anOversizedDeclaredEventIsRefusedBeforeAllocating() throws Exception {
        start(Mode.OVERSIZE_EVENT);
        await(() -> service.accepted.get() >= 1 && client.snapshot().link() == Link.LOST, 5_000, "oversize → LOST");
        assertFalse(client.snapshot().hasData());
    }

    @Test
    void lostLinkKeepsLastDataAndNeverClaimsLive() throws Exception {
        start(Mode.DROP_AFTER_FIRST_STATE);
        await(() -> client.snapshot().last() != null, 5_000, "first state");
        await(() -> client.snapshot().link() == Link.LOST, 5_000, "service dropped");
        MarketData d = client.snapshot();
        assertEquals(2699.5, d.last(), "last known value stays available");
        assertTrue(d.hasData());
        assertEquals("RECONNECTING", panel.adapter.ResearchModeTradingProvider.feedName(d), "never LIVE after the link is gone");
    }

    @Test
    void reconnectsWithBackoffAndNeverHoldsTwoSessions() throws Exception {
        start(Mode.DROP_AFTER_FIRST_STATE);
        await(() -> service.accepted.get() >= 3, 15_000, "three sessions (1 s, ~2 s backoff)");
        assertEquals(1, service.maxOpen.get(), "a local reconnect never leaves a duplicate subscriber behind");
        assertTrue(service.accepted.get() <= 6, "bounded reconnect, no tight loop: " + service.accepted.get());
    }

    @Test
    void stopCancelsTheSubscriptionClearsDataAndIsIdempotent() throws Exception {
        start(Mode.GOOD);
        await(() -> client.snapshot().candles().size() == 5, 5_000, "data");
        client.stop();
        assertEquals(Link.IDLE, client.snapshot().link());
        assertFalse(client.snapshot().hasData(), "no data survives logout");
        await(() -> service.open.get() == 0, 3_000, "service saw the disconnect (subscriber released)");
        client.stop();
        int accepted = service.accepted.get();
        Thread.sleep(1_600);
        assertEquals(accepted, service.accepted.get(), "nothing reconnects after stop");
        client.start();
        await(() -> client.snapshot().candles().size() == 5, 5_000, "fresh session after restart");
        assertEquals(1, service.maxOpen.get());
    }

    @Test
    void anEventAfterStopNeverChangesTheState() throws Exception {
        start(Mode.GOOD);
        await(() -> client.snapshot().candles().size() == 5, 5_000, "data");
        var late = service.push;
        client.stop();
        late.accept("{\"v\":1,\"type\":\"event\",\"topic\":\"state\",\"seq\":9999,\"symbol\":\"ETHUSDT\",\"market\":\"USD-M\",\"feed\":\"LIVE\",\"reason\":\"ok\",\"book\":\"LIVE\",\"updatedAtMs\":1,\"nowMs\":1,\"last\":1.5}");
        Thread.sleep(300);
        assertEquals(Link.IDLE, client.snapshot().link());
        assertNull(client.snapshot().last());
    }

    @Test
    void silentServiceIsDetectedAndTheLinkIsDropped() throws Exception {
        start(Mode.SILENT_AFTER_ACK);
        await(() -> client.snapshot().link() == Link.STREAMING, 5_000, "acked");
        await(() -> client.snapshot().link() == Link.LOST, 12_000, "silence watchdog (6 s)");
    }

    @Test
    void absentServiceIsLostNotLive() throws Exception {
        client = new MarketFeedClient(new LocalServiceClient(home), notifications::incrementAndGet);
        client.start();
        await(() -> client.snapshot().link() == Link.LOST, 5_000, "no service");
        assertEquals("DISCONNECTED", panel.adapter.ResearchModeTradingProvider.feedName(client.snapshot()));
    }

    @Test
    void panelSourceHasNoDirectBinanceAccess() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String s = Files.readString(f).toLowerCase();
                if (s.contains("fstream.binance") || s.contains("fapi.binance") || s.contains("binancefuture") || s.contains("wss://")) {
                    offenders.add(f.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "the panel only talks to the local service; the market feed belongs to byx-local-service");
    }
}
