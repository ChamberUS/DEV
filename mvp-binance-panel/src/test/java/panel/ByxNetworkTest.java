package panel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import panel.adapter.*;
import panel.model.*;
import panel.service.ByxNetworkService;
import panel.security.AccessDeniedException;

/** All HTTP responses below are synthetic fixtures; no BYX node is contacted. */
class ByxNetworkTest {
    final AuthFixture.MutableClock clock = new AuthFixture.MutableClock();
    final Map<String, String> responses = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final String address = "byx1" + "q".repeat(38);
    final String genesis = "{\"chain_id\":\"byx-fixture-1\",\"app_state\":{}}";
    HttpServer server;
    URI endpoint;
    volatile long delay;
    @BeforeEach void fixtures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = responses.getOrDefault(exchange.getRequestURI().getPath(), "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        responses.put("/cosmos/base/tendermint/v1beta1/node_info", "{\"default_node_info\":{\"network\":\"byx-fixture-1\",\"default_node_id\":\"fixture-node\"}}");
        responses.put("/status", "{\"result\":{\"node_info\":{\"network\":\"byx-fixture-1\",\"id\":\"fixture-node\"}}}");
        responses.put("/genesis", "{\"result\":{\"genesis\":" + genesis + "}}");
        responses.put("/cosmos/bank/v1beta1/denoms_metadata/ubyx", """
                {"metadata":{"base":"ubyx","display":"BYX","denom_units":[{"denom":"ubyx","exponent":0},{"denom":"BYX","exponent":6}]}}
                """);
        responses.put("/cosmos/base/tendermint/v1beta1/blocks/latest", """
                {"sdk_block":{"header":{"chain_id":"byx-fixture-1","height":"123","time":"2026-10-01T12:00:00Z"}}}
                """);
        responses.put("/cosmos/base/tendermint/v1beta1/syncing", "{\"syncing\":false}");
        responses.put(balancePath(), "{\"balance\":{\"denom\":\"ubyx\",\"amount\":\"900719925474099312345678\"}}");
    }
    @AfterEach void stop() { server.stop(0); }
    String balancePath() { return "/cosmos/bank/v1beta1/balances/" + address + "/by_denom"; }
    ByxConfig config() throws Exception {
        return new ByxConfig(endpoint, endpoint, "LOCALNET", "byx-fixture-1",
                CosmosByxChainGateway.fingerprint(new ObjectMapper().readTree(genesis)), "ubyx", "BYX", 6, "BANK_METADATA", address);
    }
    CosmosByxChainGateway gateway() { return new CosmosByxChainGateway(clock); }
    ByxNetworkService service() { return new ByxNetworkService(gateway(), () -> null, clock); }
    @Test void exactBalanceAndReadAllowlist() throws Exception {
        var snapshot = gateway().read(config());
        assertEquals("VERIFIED", snapshot.identity()); assertEquals("ONLINE", snapshot.connection());
        assertEquals("FRESH", snapshot.freshness()); assertFalse(snapshot.syncing());
        assertEquals("900719925474099312.345678 BYX", snapshot.formattedBalance());
        assertEquals("DISABLED", snapshot.execution());
        assertEquals(7, requests.size()); assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")));
        assertTrue(requests.contains("GET " + balancePath() + "?denom=ubyx"));
        var trader = new ResearchModeTradingProvider().load(new Snapshot());
        assertNull(trader.equity); assertNull(trader.account); assertEquals("DISABLED", trader.trading);
    }
    @Test void wrongChainNeverReadsBalance() throws Exception {
        responses.put("/cosmos/base/tendermint/v1beta1/node_info", "{\"default_node_info\":{\"network\":\"mainnet\"}}");
        var s = gateway().read(config()); assertEquals("UNVERIFIED", s.identity()); assertNull(s.balance()); assertEquals(1, requests.size());
    }
    @Test void wrongGenesisNeverReadsBalance() throws Exception {
        responses.put("/genesis", "{\"result\":{\"genesis\":{\"chain_id\":\"other\"}}}");
        assertEquals("UNVERIFIED", gateway().read(config()).identity()); assertEquals(3, requests.size());
    }
    @Test void restAndRpcMustIdentifyTheSameNode() throws Exception {
        responses.put("/status", "{}");
        var s = gateway().read(config()); assertEquals("UNVERIFIED", s.identity()); assertNull(s.balance());
        assertEquals(2, requests.size());
    }
    @Test void metadataMissingOrWrongCannotVerifyAsset() throws Exception {
        responses.put("/cosmos/bank/v1beta1/denoms_metadata/ubyx", "{}");
        assertEquals("UNVERIFIED", gateway().read(config()).identity());
        responses.put("/cosmos/bank/v1beta1/denoms_metadata/ubyx", "{\"metadata\":{\"base\":\"byx\",\"display\":\"BYX\"}}");
        assertNull(gateway().read(config()).balance());
    }
    @Test void staleBlockAndMissingBalanceDoNotBecomeZero() throws Exception {
        clock.advance(Duration.ofMinutes(2)); responses.put(balancePath(), "{}");
        var s = gateway().read(config()); assertEquals("STALE", s.freshness()); assertEquals("UNKNOWN", s.formattedBalance());
    }
    @Test void malformedAmountAndMissingBlockFailClosed() throws Exception {
        responses.put(balancePath(), "{\"balance\":{\"denom\":\"ubyx\",\"amount\":1.5}}");
        try (var service = service()) {
            service.configure(config()); var s = service.refresh().get(5, TimeUnit.SECONDS);
            assertNull(s.balance()); assertEquals("UNVERIFIED", s.identity());
        }
        responses.put("/cosmos/base/tendermint/v1beta1/blocks/latest", "{}");
        assertEquals("UNVERIFIED", gateway().read(config()).identity());
    }
    @Test void stoppedNodeRetainsTimestampAndStaleCache() throws Exception {
        try (var service = service()) {
            service.configure(config()); var good = service.refresh().get(5, TimeUnit.SECONDS);
            server.stop(0); var stale = service.refresh().get(5, TimeUnit.SECONDS);
            assertEquals("OFFLINE", stale.connection()); assertEquals("STALE", stale.freshness());
            assertEquals(good.updatedAt(), stale.updatedAt()); assertEquals(good.balance(), stale.balance());
        }
    }
    @Test void timeoutIsAsyncAndUnknownNotZero() throws Exception {
        delay = 3500;
        try (var service = service()) {
            service.configure(config()); long start = System.nanoTime(); var future = service.refresh();
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500));
            var s = future.get(5, TimeUnit.SECONDS); assertEquals("OFFLINE", s.connection()); assertNull(s.balance());
        }
    }
    @Test void cacheAgesWithoutAnotherRequest() throws Exception {
        try (var service = service()) {
            service.configure(config()); service.refresh().get(5, TimeUnit.SECONDS); int reads = requests.size();
            clock.advance(Duration.ofSeconds(61)); assertEquals("STALE", service.snapshot().freshness()); assertEquals(reads, requests.size());
        }
    }
    @Test void localHostDoesNotImplyTestnetAndIdentityIsMandatory() throws Exception {
        var c = config();
        for (String network : List.of("TESTNET", "MAINNET", "")) assertThrows(IllegalArgumentException.class, () ->
                new ByxConfig(endpoint, endpoint, network, c.expectedChainId(), c.genesisFingerprint(), "ubyx", "BYX", 6, "BANK_METADATA", address));
        assertThrows(IllegalArgumentException.class, () -> new ByxConfig(endpoint, endpoint, "LOCALNET", "", "", "byx", "BYX", 18, "GUESS", address));
        assertThrows(IllegalArgumentException.class, () -> new ByxConfig(URI.create("http://example.com:1317"), endpoint,
                "LOCALNET", c.expectedChainId(), c.genesisFingerprint(), "ubyx", "BYX", 6, "BANK_METADATA", address));
    }
    @Test void adminGateRequiresSecondFactorAndExpires() throws Exception {
        var auth = AuthFixture.ready(); auth.seedAdmin(); auth.auth.login("boss", "correct-horse-1".toCharArray());
        try (var service = new ByxNetworkService(gateway(), auth.access, auth.clock)) {
            var c = config(); assertThrows(AccessDeniedException.class, () -> service.configure(c));
            auth.authorize(); service.configure(c); assertEquals(c, service.details());
            auth.clock.advance(Duration.ofMinutes(31)); assertThrows(AccessDeniedException.class, service::details);
            assertThrows(AccessDeniedException.class, () -> service.configure(c));
        }
    }
    @Test void userCanReadSummaryButCannotConfigureOrReadInfrastructure() throws Exception {
        var auth = AuthFixture.ready(); auth.seedUser(); auth.auth.login("alice", "temporary-pass-1".toCharArray());
        try (var service = new ByxNetworkService(gateway(), auth.access, auth.clock)) {
            assertNotNull(service.snapshot()); assertThrows(AccessDeniedException.class, service::details);
            var c = config(); assertThrows(AccessDeniedException.class, () -> service.configure(c));
        }
    }
    @Test void logoutDiscardsInflightResponseAndConfigurationSurvivesForNextUser() throws Exception {
        delay = 100;
        try (var service = service()) {
            var c = config(); service.configure(c); var pending = service.refresh(); service.pause();
            pending.get(5, TimeUnit.SECONDS); assertNull(service.snapshot().balance()); assertEquals(c, service.details());
            service.start(); assertEquals("VERIFIED", service.refresh().get(5, TimeUnit.SECONDS).identity());
        }
    }
    @Test void mockSourceIsExplicitAndBrandingDoesNotResetLogin() {
        var auth = AuthFixture.ready(); auth.seedAdmin(); auth.auth.login("boss", "correct-horse-1".toCharArray());
        var before = auth.sessions.user().orElseThrow();
        assertEquals("BYX-MVP — Login", panel.app.AppBranding.title("Login"));
        assertEquals("by Buynnex", panel.app.AppBranding.ATTRIBUTION); assertEquals(before, auth.sessions.user().orElseThrow());
        ByxChainGateway mock = new ByxChainGateway() {
            public String source() { return "MOCK"; }
            public ByxSnapshot read(ByxConfig config) { return ByxSnapshot.unknown("MOCK", "LOCALNET", "UNKNOWN", "Fixture"); }
        };
        try (var service = new ByxNetworkService(mock, () -> null, clock)) { assertEquals("MOCK", service.snapshot().source()); }
    }
}
