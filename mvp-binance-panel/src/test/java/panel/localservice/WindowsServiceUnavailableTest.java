package panel.localservice;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real DEFAULT denial, no socket server, fake authority, credentials or pairing token. */
@EnabledOnOs(OS.WINDOWS)
class WindowsServiceUnavailableTest {
    @TempDir Path home;
    @Test void defaultClientReportsUnsupportedServiceWithoutCreatingPairingState() throws Exception {
        LocalServiceClient client = new LocalServiceClient(home);
        LocalServiceStatus status = client.once(false);
        assertEquals(LocalServiceStatus.State.UNAVAILABLE, status.state());
        assertEquals("native_service_unsupported", status.code());
        try (var files = Files.list(home)) { assertEquals(0, files.count()); }
        assertEquals(panel.identity.IdentityPolicy.Mode.DEVELOPMENT_UNVERIFIED, client.identityMode());
    }
    @Test void authorityCannotAcquireSessionOrAuthorizePrivateOperations() {
        try (AuthorityClient client = new AuthorityClient(home)) {
            assertFalse(client.hasSession()); assertFalse(client.ensureService());
            assertFalse(client.sessionStatus().ok()); assertFalse(client.hasSession());
            assertEquals("AUTH_REQUIRED", client.sessionStatus().code());
            LocalServiceClient transport = new LocalServiceClient(home);
            LocalServiceClient.Fail refusal = assertThrows(LocalServiceClient.Fail.class, () -> transport.openPaired(ch -> fail("IPC must not open")));
            assertEquals("native_service_unsupported", refusal.code);
        }
    }
    @Test void windowsCannotResolveOrStartTheMacHelper() {
        assertNull(ServiceLauncher.helperOf(Path.of("arbitrary/Contents/MacOS/BYX")));
        assertFalse(new ServiceLauncher(home, ServiceLauncher.currentExecutable()).available());
    }
    @Test void unqualifiedFixturesRefuseBeforeWritingAnyTokenOrOpeningASocket() throws Exception {
        var serviceError = assertThrows(java.io.IOException.class, () -> new FakeService(home, FakeService.Mode.GOOD));
        var marketError = assertThrows(java.io.IOException.class, () -> new FakeMarketService(home, FakeMarketService.Mode.GOOD));
        assertTrue(serviceError.getMessage().startsWith("native_ipc_fixture_unqualified"));
        assertEquals(serviceError.getMessage(), marketError.getMessage());
        try (var files = Files.list(home)) { assertEquals(0, files.count()); }
    }
    @Test void strictIdentityCannotBeReplacedByAUserNameOrAClaimedPeer() throws Exception {
        var strict = panel.identity.IdentityPolicy.strict(channel -> {
            fail("unqualified Windows transport must not reach an invented verifier");
            return panel.identity.PeerVerifier.Verdict.no("unqualified");
        });
        LocalServiceStatus status = new LocalServiceClient(home, strict).once(false);
        assertEquals(LocalServiceStatus.State.UNAVAILABLE, status.state());
        assertEquals("native_service_unsupported", status.code());
        assertFalse(status.connected());
        for (String feature : java.util.List.of("accountData", "adminOperations", "notifications", "marketData"))
            assertFalse(status.feature(feature));
        try (var files = Files.list(home)) { assertEquals(0, files.count()); }
    }
    @Test void allowedTypedChainCallsStillRefuseAndNeverReturnFabricatedData() throws Exception {
        LocalServiceClient client = new LocalServiceClient(home);
        for (String operation : LocalServiceClient.CHAIN_OPERATIONS) {
            var refusal = assertThrows(LocalServiceClient.Fail.class, () -> client.chainCall(operation));
            assertEquals("native_service_unsupported", refusal.code);
        }
        var status = new ChainStatusClient(client).read();
        assertEquals("ERROR", status.state());
        assertEquals("SERVICE_UNAVAILABLE", status.reason());
        assertNull(status.chainId()); assertNull(status.latestHeight());
        try (var files = Files.list(home)) { assertEquals(0, files.count()); }
    }
    @Test void marketLifecycleCannotTurnUnsupportedIpcIntoLiveData() throws Exception {
        var lost = new java.util.concurrent.CountDownLatch(1);
        var current = new java.util.concurrent.atomic.AtomicReference<MarketFeedClient>();
        try (MarketFeedClient market = new MarketFeedClient(new LocalServiceClient(home), () -> {
            if (current.get().snapshot().link() == MarketData.Link.LOST) lost.countDown();
        })) {
            current.set(market); market.start(); market.start();
            assertTrue(lost.await(3, java.util.concurrent.TimeUnit.SECONDS), "bounded unsupported result");
            assertFalse(market.snapshot().hasData()); assertNull(market.snapshot().last());
            assertEquals("DISCONNECTED", panel.adapter.ResearchModeTradingProvider.feedName(market.snapshot()));
            market.stop(); market.stop();
            assertFalse(market.running()); assertEquals(MarketData.Link.IDLE, market.snapshot().link());
            assertFalse(market.snapshot().hasData());
        }
        try (var files = Files.list(home)) { assertEquals(0, files.count()); }
    }
}
