package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import panel.adapter.*;
import panel.model.*;
import panel.process.ProcessRunner;
import panel.service.*;

class BackendGatewayTest {
    static final String ONLINE = """
        {"schema_version":1,"system":{"online":true,"version":"test"},
         "capture":{"running":true,"symbol":"ETHUSDT","market":"USD-M Futures"},
         "research":{"frozen_spec_status":"FROZEN"}}
        """;

    @Test void realContractUsesBackendMarketAndNeverFabricatesPrices() {
        Snapshot s = new LocalBackendGateway(x -> new Snapshot(), (x, h) -> ONLINE).load(new Settings());
        TraderSnapshot t = new ResearchModeTradingProvider().load(s);
        assertTrue(s.backendOnline);
        assertEquals("ETHUSDT", t.symbol);
        assertEquals("USD-M Futures", t.market);
        assertEquals("RUNNING", t.recorder);
        assertNull(t.price);
        assertNull(t.account);
        assertNull(t.dailyPnl);
        assertEquals(0, t.orders);
        assertEquals(0, t.positions);
        assertEquals("DISABLED", t.trading);
        assertEquals("FROZEN", s.frozenSpecStatus);
        assertEquals("LOCKED", s.validationStatus);
        assertEquals("SEALED", s.finalHoldout);
    }

    @Test void missingFieldsAndMalformedContractsAreSafe() {
        Snapshot partial = new LocalBackendGateway(x -> new Snapshot(), (x,h) ->
                "{\"schema_version\":1,\"system\":{\"online\":true}}").load(new Settings());
        assertTrue(partial.backendOnline);
        assertNull(new ResearchModeTradingProvider().load(partial).symbol);
        assertFalse(partial.warnings.isEmpty());
        for (String bad : List.of("{}", "broken", "{\"schema_version\":2}")) {
            assertFalse(new LocalBackendGateway(x -> new Snapshot(), (x,h) -> bad).load(new Settings()).backendOnline);
        }
    }

    @Test void offlineReconnectsAndResearchIsCachedWithManualRefresh() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        var gateway = new LocalBackendGateway(x -> { summaries.incrementAndGet(); return new Snapshot(); },
                (x,h) -> { if (requests.getAndIncrement() == 0) throw new IOException(); return ONLINE; });
        var settings = new Settings();
        assertFalse(gateway.load(settings).backendOnline);
        assertTrue(gateway.load(settings).backendOnline);
        Snapshot first = gateway.load(settings);
        Snapshot second = gateway.load(settings);
        assertNotSame(first, second);
        assertEquals(1, summaries.get());
        gateway.invalidate();
        gateway.load(settings);
        assertEquals(2, summaries.get());
    }

    @Test void processTimeoutAndExitFailureAreBounded() throws Exception {
        var runner = new ProcessRunner();
        assertThrows(IOException.class, () -> runner.capture(List.of("/bin/sh", "-c", "exec sleep 10"), Path.of("."), 1));
        assertThrows(IOException.class, () -> runner.capture(List.of("/bin/sh", "-c", "exit 7"), Path.of("."), 2));
        assertEquals("{}", runner.capture(List.of("/bin/sh", "-c", "printf '{}'"), Path.of("."), 2));
    }

    @Test void pollingKeepsFxResponsiveCoalescesAndReconnects() throws Exception {
        FxSupport.start();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var offline = new CountDownLatch(1);
        var online = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var gateway = new LocalBackendGateway(x -> new Snapshot(), (x,h) -> {
            assertFalse(Platform.isFxApplicationThread());
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    throw new IOException();
                }
                return ONLINE;
            } finally { active.decrementAndGet(); }
        });
        Settings settings = new Settings(); settings.pollSeconds = 2;
        var cli = new AdaptiveTraderCli(() -> "/missing");
        var jobs = new JobManager(cli, () -> Path.of("."), () -> {}, () -> {});
        var service = new ResearchService(settings, gateway, new MockResearchBackend(), jobs);
        FxSupport.fx(() -> {
            service.snapshot.addListener((o,a,s) -> {
                assertTrue(Platform.isFxApplicationThread());
                if (s.backendOnline) online.countDown(); else offline.countDown();
            });
            service.start();
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FxSupport.fx(() -> { for (int i = 0; i < 50; i++) service.refresh(); });
            release.countDown();
            assertTrue(offline.await(5, TimeUnit.SECONDS));
            assertTrue(online.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximum.get());
            assertTrue(calls.get() <= 3);
        } finally { release.countDown(); service.close(); }
    }
}
