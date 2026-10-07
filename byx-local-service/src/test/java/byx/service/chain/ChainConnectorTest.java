package byx.service.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V2.1L: conector SOMENTE LEITURA da chain pública contra um nó FALSO em 127.0.0.1. Todo desvio falha fechado; só LIVE é saudável. Nenhum nó real. */
class ChainConnectorTest {
    private static final String CANARY = "CANARY-NODE-PAYLOAD-NEVER-LOGGED";
    private FakeNode node;
    private ChainConnector connector;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        node = new FakeNode();
        connector = new ChainConnector(Optional.of(node.config()), new LoopbackHttp(Duration.ofMillis(400), Duration.ofMillis(500)), new ChainConnector.Timing(10, 60_000, 30_000, 600_000),
                System::currentTimeMillis);
    }

    @AfterEach
    void down() {
        connector.close();
        node.close();
        Log.redirect(null);
    }

    private ChainStatus cycle() {
        connector.cycle();
        return connector.status();
    }

    private List<String> chainLogs() {
        synchronized (logs) {
            return logs.stream().filter(l -> l.contains(" byx-local-service chain_")).toList();
        }
    }

    // ---- produção e nó saudável ------------------------------------------------------------------------------------------------------------

    @Test
    void theProfileIsATypedCompileTimeChoiceWithFixedLoopbackEndpointsAndNoRuntimeSwitch() throws Exception {
        assertEquals(ChainProfile.LOCAL_QA, ChainProfile.ACTIVE);
        assertTrue(ChainProfile.PRODUCTION_DISABLED.config().isEmpty(), "the disabled profile has no endpoint");
        ChainConfig qa = ChainProfile.LOCAL_QA.config().orElseThrow();
        assertEquals(new ChainEndpoint("127.0.0.1", 28657), qa.rpc());
        assertEquals(new ChainEndpoint("127.0.0.1", 28317), qa.rest());
        assertEquals("byx", qa.expectedChainId());
        assertEquals(new DenomModel("ubyx", "BYX", 6), qa.denom());
        assertEquals(Optional.of(qa), ChainConfig.production());
        assertTrue(ChainConnectorTestAccess.productionProfileIsTheTypedLocalQa());
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/byx/service/chain/ChainProfile.java")).replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(src.contains("getenv") || src.contains("getProperty") || src.contains("getBoolean") || src.contains("Files."), "the profile reads no environment, property or file");
    }

    @Test
    void explicitlyDisabledIsNotConfiguredAndNeverTouchesTheNetwork() {
        try (ChainConnector c = ChainConnector.notConfigured()) {
            ChainStatus s = c.status();
            assertEquals(ChainState.NOT_CONFIGURED, s.state());
            assertFalse(s.configured() || s.reachable() || s.networkMatch());
            assertNull(s.latestHeight());
            assertEquals(ChainReason.NOT_CONFIGURED, s.reason());
            assertFalse(c.configured());
            assertTrue(c.denomMetadata().isEmpty() && c.supply().isEmpty());
        }
        assertEquals(0, node.requests.get());
    }

    @Test
    void aHealthyByxNodeIsLiveWithTypedFieldsAndExactAmounts() {
        ChainStatus s = cycle();
        assertEquals(ChainState.LIVE, s.state());
        assertTrue(s.configured() && s.reachable() && s.networkMatch());
        assertEquals("byx", s.chainId());
        assertEquals(100L, s.latestHeight());
        assertEquals(Boolean.FALSE, s.catchingUp());
        assertEquals(ChainReason.NONE, s.reason());
        assertEquals(new DenomModel("ubyx", "BYX", 6), connector.denomMetadata().orElseThrow());
        BigInteger units = connector.supply().orElseThrow();
        assertEquals(new BigInteger("1500000"), units);
        assertEquals("1.500000", connector.denomMetadata().orElseThrow().format(units));
        // status() também dispara a atualização por demanda em segundo plano: pode repetir uma rota, mas SÓ existem estas três rotas GET
        assertEquals(java.util.Set.of("GET /status", "GET /cosmos/bank/v1beta1/denoms_metadata/ubyx", "GET /cosmos/bank/v1beta1/supply/by_denom?denom=ubyx"), new java.util.HashSet<>(node.paths),
                "only the three fixed GET routes");
    }

    // ---- falhas fechadas -------------------------------------------------------------------------------------------------------------------

    @Test
    void aWrongChainIdIsANetworkMismatchAndNeverShownAsHealthy() {
        node.chainId = "some-other-chain";
        ChainStatus s = cycle();
        assertEquals(ChainState.NETWORK_MISMATCH, s.state());
        assertFalse(s.networkMatch());
        assertEquals(ChainReason.NETWORK_MISMATCH, s.reason());
        assertNull(s.latestHeight(), "the height of the wrong network is not exposed as healthy data");
        assertEquals("some-other-chain", s.chainId(), "the observed (pattern-validated) id is shown to explain the mismatch");
        assertTrue(connector.denomMetadata().isEmpty() && connector.supply().isEmpty());
        assertTrue(chainLogs().stream().anyMatch(l -> l.contains("chain_network_mismatch gen=1 reason=NETWORK_MISMATCH")), chainLogs().toString());
        assertTrue(logs.stream().noneMatch(l -> l.contains("some-other-chain")), "the log carries no observed text");
    }

    @Test
    void anOfflineNodeIsOfflineAndALostNodeIsLoggedAsDisconnect() {
        assertEquals(ChainState.LIVE, cycle().state());
        node.close();
        ChainStatus s = cycle();
        assertEquals(ChainState.OFFLINE, s.state());
        assertFalse(s.reachable());
        assertEquals(ChainReason.UNREACHABLE, s.reason());
        assertNull(s.latestHeight());
        assertTrue(connector.supply().isEmpty());
        assertTrue(chainLogs().stream().anyMatch(l -> l.contains("chain_connect gen=1")));
        assertTrue(chainLogs().stream().anyMatch(l -> l.contains("chain_disconnect gen=1 reason=UNREACHABLE")));
    }

    @Test
    void aSlowNodeTimesOutAndIsOffline() {
        node.override.put("/status", new FakeNode.Reply(200, node.status(), 1_500, null));
        ChainStatus s = cycle();
        assertEquals(ChainState.OFFLINE, s.state());
        assertEquals(ChainReason.TIMEOUT, s.reason());
    }

    @Test
    void malformedJsonAndMissingFieldsAreErrorsWithFixedReasonsAndNoPayloadInTheLog() {
        node.override.put("/status", FakeNode.Reply.ok("{\"result\":{\"node_info\":{\"network\":\"byx\"" + CANARY));
        ChainStatus s = cycle();
        assertEquals(ChainState.ERROR, s.state());
        assertEquals(ChainReason.JSON_INVALID, s.reason());
        assertTrue(s.reachable() && !s.networkMatch() && s.latestHeight() == null);
        node.override.put("/status", FakeNode.Reply.ok("{\"result\":{\"node_info\":{\"network\":\"byx\"},\"sync_info\":{\"latest_block_time\":\"2026-01-01T00:00:00Z\",\"catching_up\":false,\"note\":\"" + CANARY + "\"}}}"));
        s = cycle();
        assertEquals(ChainReason.MISSING_FIELD, s.reason());
        node.override.put("/status", FakeNode.Reply.ok("{\"result\":{\"node_info\":{\"network\":\"byx\"},\"sync_info\":{\"latest_block_height\":123,\"latest_block_time\":\"2026-01-01T00:00:00Z\",\"catching_up\":false}}}"));
        assertEquals(ChainReason.TYPE_MISMATCH, cycle().reason(), "height as a JSON number, not a string");
        node.override.put("/status", FakeNode.Reply.ok("{\"result\":{\"node_info\":{\"network\":\"by x!\"},\"sync_info\":{\"latest_block_height\":\"1\",\"latest_block_time\":\"2026-01-01T00:00:00Z\",\"catching_up\":false}}}"));
        assertEquals(ChainReason.VALUE_OUT_OF_RANGE, cycle().reason(), "chain id outside the safe pattern");
        assertTrue(chainLogs().stream().allMatch(l -> l.contains("gen=1")), chainLogs().toString());
        assertTrue(chainLogs().stream().anyMatch(l -> l.contains("chain_parse_rejected gen=1 reason=JSON_INVALID")));
        assertTrue(logs.stream().noneMatch(l -> l.contains(CANARY)), "no raw payload is ever logged");
    }

    @Test
    void wrongDenomMetadataIsRefusedAndNeverVerified() {
        node.display = "XYZ";
        ChainStatus s = cycle();
        assertEquals(ChainState.NETWORK_MISMATCH, s.state());
        assertEquals(ChainReason.DENOM_MISMATCH, s.reason());
        assertTrue(connector.denomMetadata().isEmpty());
        node.display = "BYX";
        node.displayExponent = 8;
        assertEquals(ChainReason.DENOM_MISMATCH, cycle().reason(), "wrong exponent");
        node.displayExponent = 6;
        node.base = "other";
        node.override.put("/cosmos/bank/v1beta1/denoms_metadata/ubyx", FakeNode.Reply.ok(node.metadata()));
        assertEquals(ChainState.NETWORK_MISMATCH, cycle().state(), "wrong base denom");
        assertTrue(connector.denomMetadata().isEmpty());
    }

    @Test
    void aNodeThatIsCatchingUpIsSyncingNotLive() {
        node.catchingUp = true;
        ChainStatus s = cycle();
        assertEquals(ChainState.SYNCING, s.state());
        assertEquals(ChainReason.CATCHING_UP, s.reason());
        assertEquals(Boolean.TRUE, s.catchingUp());
    }

    @Test
    void anOldBlockIsStaleAndAFutureBlockIsAnError() {
        node.blockTime = Instant.now().minusSeconds(300);
        assertEquals(ChainState.STALE, cycle().state());
        assertEquals(ChainReason.NODE_STALE, connector.status().reason());
        node.blockTime = Instant.now().plusSeconds(600);
        ChainStatus s = cycle();
        assertEquals(ChainState.ERROR, s.state());
        assertEquals(ChainReason.BLOCK_TIME_IN_FUTURE, s.reason());
    }

    @Test
    void heightNeverRegressesSilentlyWithinAGenerationAndAGenerationResetsIt() {
        node.height = 500;
        assertEquals(ChainState.LIVE, cycle().state());
        node.height = 450; // regressão
        ChainStatus s = cycle();
        assertEquals(ChainState.STALE, s.state(), "a regressing height is not LIVE");
        assertEquals(ChainReason.HEIGHT_REGRESSION, s.reason());
        node.height = 500; // igual ao máximo já visto: volta a ser aceitável
        assertEquals(ChainState.LIVE, cycle().state());
        node.height = 499;
        assertEquals(ChainState.STALE, cycle().state());
        // reinício / mudança de configuração = nova geração: o máximo é zerado e o estado recomeça
        connector.newGeneration();
        assertEquals(ChainState.CONNECTING, connector.status().state());
        assertEquals(2, connector.status().generation());
        node.height = 10;
        ChainStatus fresh = cycle();
        assertEquals(ChainState.LIVE, fresh.state(), "a new generation may legitimately start lower");
        assertEquals(2, fresh.generation());
    }

    @Test
    void oversizedResponsesAreRejectedBeforeBeingBuffered() {
        node.override.put("/status", FakeNode.Reply.ok("{\"result\":\"" + "x".repeat(ChainRoute.RPC_STATUS.maxBytes() + 100) + "\"}"));
        ChainStatus s = cycle();
        assertEquals(ChainState.ERROR, s.state());
        assertEquals(ChainReason.RESPONSE_TOO_LARGE, s.reason());
        node.override.put("/status", FakeNode.Reply.ok(node.status()));
        node.override.put("/cosmos/bank/v1beta1/supply/by_denom", FakeNode.Reply.ok("{\\\"amount\\\":{\\\"denom\\\":\\\"ubyx\\\",\\\"amount\\\":\\\"" + "9".repeat(100) + "\\\"}}"));
        assertTrue(cycle().state() == ChainState.ERROR, "an absurd amount is out of range");
    }

    @Test
    void redirectsAreRefusedAndNeverFollowed() throws Exception {
        try (FakeNode elsewhere = new FakeNode()) {
            node.override.put("/status", new FakeNode.Reply(302, "", 0, elsewhere.origin() + "/status"));
            ChainStatus s = cycle();
            assertEquals(ChainState.OFFLINE, s.state());
            assertEquals(ChainReason.REDIRECT_REFUSED, s.reason());
            assertEquals(0, elsewhere.requests.get(), "the redirect target was never contacted");
        }
        node.override.put("/status", new FakeNode.Reply(500, "{}", 0, null));
        assertEquals(ChainReason.HTTP_STATUS, cycle().reason());
    }

    @Test
    void statusIsPulledOnDemandWithAMinimumIntervalAndNeverBlocksTheCaller() throws Exception {
        long t0 = System.nanoTime();
        ChainStatus first = connector.status();
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 200, "never waits for the node");
        assertEquals(ChainState.CONNECTING, first.state());
        long end = System.currentTimeMillis() + 3_000;
        while (connector.status().state() != ChainState.LIVE && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertEquals(ChainState.LIVE, connector.status().state());
    }

    // ---- endpoint: só loopback aprovado ---------------------------------------------------------------------------------------------------------

    @Test
    void onlyApprovedLoopbackOriginsAreAccepted() {
        assertEquals(new ChainEndpoint("127.0.0.1", 26657), ChainEndpoint.parse("http://127.0.0.1:26657"));
        assertEquals(new ChainEndpoint("[::1]", 1317), ChainEndpoint.parse("http://[::1]:1317/"));
        for (String bad : new String[] {"http://0.0.0.0:26657", "http://192.168.1.10:26657", "http://10.0.0.5:26657", "http://8.8.8.8:26657", "http://localhost:26657", "http://example.com:26657",
                "http://127.0.0.2:26657", "https://127.0.0.1:26657", "file:///tmp/byx.sock", "unix:///tmp/byx.sock", "http://127.0.0.1", "http://127.0.0.1:80", "http://127.0.0.1:26657/status",
                "http://127.0.0.1:26657?x=1", "http://user:pw@127.0.0.1:26657", "http://127.0.0.1:26657#f", "http://[::ffff:192.168.0.1]:26657", "http://127.0.0.1:70000", "", " ", "127.0.0.1:26657",
                "http://127.0.0.1:26657/" + "a".repeat(80)}) {
            assertThrows(IllegalArgumentException.class, () -> ChainEndpoint.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ChainEndpoint.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new ChainEndpoint("0.0.0.0", 26657));
        assertThrows(IllegalArgumentException.class, () -> new ChainEndpoint("192.168.1.1", 26657));
    }

    @Test
    void theConfigurationIsTypedAndRejectsUnsafeChainIdsAndDenoms() {
        assertThrows(IllegalArgumentException.class, () -> ChainConfig.of(node.origin(), node.origin(), "by x", new DenomModel("ubyx", "BYX", 6)));
        assertThrows(IllegalArgumentException.class, () -> ChainConfig.of(node.origin(), node.origin(), "", new DenomModel("ubyx", "BYX", 6)));
        assertThrows(IllegalArgumentException.class, () -> ChainConfig.of(node.origin(), node.origin(), null, new DenomModel("ubyx", "BYX", 6)));
        for (String bad : new String[] {"ubyx/../x", "ubyx?x=1", "UBYX", "u", "ubyx ", "ubyx\n", "ubyx#"}) {
            assertThrows(IllegalArgumentException.class, () -> new DenomModel(bad, "BYX", 6), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new DenomModel("ubyx", "BYX", 19));
        assertThrows(IllegalArgumentException.class, () -> new DenomModel("ubyx", "BYX", -1));
    }

    // ---- denom: conversão exata ------------------------------------------------------------------------------------------------------------

    @Test
    void denomConversionIsExactIntegerArithmeticNeverFloatingPoint() {
        DenomModel d = new DenomModel("ubyx", "BYX", 6);
        assertEquals("0.000000", d.format(BigInteger.ZERO));
        assertEquals("0.000001", d.format(BigInteger.ONE));
        assertEquals("1.000000", d.format(BigInteger.valueOf(1_000_000)));
        assertEquals("1.500000", d.format(BigInteger.valueOf(1_500_000)));
        assertEquals("123456789012345678901234567890.123456", d.format(new BigInteger("123456789012345678901234567890123456")));
        assertEquals("5", new DenomModel("x1a", "X1", 0).format(BigInteger.valueOf(5)));
        assertEquals(BigInteger.valueOf(1_500_000), d.parse("1.5"));
        assertEquals(BigInteger.ONE, d.parse("0.000001"));
        assertEquals(new BigInteger("123456789012345678901234567890123456"), d.parse("123456789012345678901234567890.123456"));
        for (String bad : new String[] {"1.5000001", "-1", "1e3", "1,5", "", " 1", "1 ", ".5", "5.", "0x10", "NaN", "Infinity", "1_000"}) {
            assertThrows(IllegalArgumentException.class, () -> d.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> d.format(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> d.format(null));
        // 0.1 + 0.2 em ponto flutuante falharia; em unidades-base é exato
        assertEquals(d.parse("0.3"), d.parse("0.1").add(d.parse("0.2")));
    }

    // ---- fixação do contrato: operações e rotas ---------------------------------------------------------------------------------------------

    @Test
    void everyOperationHasAFixedGetRouteAndThereIsNoGenericOrWriteOperation() {
        for (ChainOperation op : ChainOperation.values()) {
            assertEquals("GET", op.method());
            assertTrue(op.wire().matches("byx\\.[A-Za-z]+"), op.wire());
            assertTrue(op.route().maxBytes() > 0 && op.route().maxBytes() <= 16 * 1024);
        }
        assertEquals(java.util.Set.of("byx.status", "byx.denomMetadata", "byx.supply"),
                java.util.Arrays.stream(ChainOperation.values()).filter(ChainOperation::ipc).map(ChainOperation::wire).collect(java.util.stream.Collectors.toSet()));
        for (ChainOperation op : ChainOperation.values()) {
            for (String banned : List.of("send", "broadcast", "sign", "wallet", "tx", "grant", "payment", "merchant", "governance", "proxy", "execute", "raw", "request", "fetch", "call")) {
                assertFalse(op.wire().toLowerCase().contains(banned), op.wire() + " mentions " + banned);
            }
        }
        assertEquals("/cosmos/bank/v1beta1/denoms_metadata/ubyx", ChainRoute.REST_DENOM_METADATA.path("ubyx"));
        assertEquals("/cosmos/bank/v1beta1/supply/by_denom?denom=ubyx", ChainRoute.REST_SUPPLY.path("ubyx"));
        assertEquals("/status", ChainRoute.RPC_STATUS.path("ubyx"));
    }
}
