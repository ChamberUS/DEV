package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.chain.ChainConnector;
import byx.service.chain.ChainConnectorTestAccess;
import byx.service.chain.FakeNode;
import byx.service.chain.LoopbackHttp;
import byx.service.identity.IdentityPolicy;
import byx.service.market.FakeMarket;
import byx.service.market.MarketFeed;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** V2.1L: a leitura PÚBLICA da chain pelo IPC tipado do serviço (nó falso em loopback). Separada do gate privado; sem proxy, sem escrita, sem argumento do painel. */
class ChainIpcTest {
    private static final List<String> PRIVATE = List.of("accountData", "notifications", "adminOperations", "secretIntegrations");
    private Path home;
    private ServiceInstance service;
    private FakeNode node;

    @AfterEach
    void down() throws Exception {
        if (service != null) service.close();
        if (node != null) node.close();
        if (home != null) {
            try (var walk = Files.walk(home)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void start(ChainConnector chain) throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "ch");
        service = ServiceInstance.start(home, new ServiceInstance.Limits(4, 600, 600, 2_000, 600), new MarketFeed(new FakeMarket.Ws(), new FakeMarket.Http(), FakeMarket.fast()),
                IdentityPolicy.development(), null, null, chain);
    }

    private TestClient paired() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        return c;
    }

    private ChainConnector live() throws Exception {
        node = new FakeNode();
        return new ChainConnector(Optional.of(node.config()), new LoopbackHttp(Duration.ofMillis(400), Duration.ofMillis(500)), new ChainConnector.Timing(10, 60_000, 30_000, 600_000), System::currentTimeMillis);
    }

    private JsonNode until(TestClient c, String op, String field, String expected) throws Exception {
        long end = System.currentTimeMillis() + 4_000;
        JsonNode r;
        do {
            r = c.call(op).path("result");
            if (expected.equals(r.path(field).asText())) return r;
            Thread.sleep(25);
        } while (System.currentTimeMillis() < end);
        return r;
    }

    private void assertPrivateStillOff(TestClient c) throws Exception {
        JsonNode caps = c.call("capabilities").path("result");
        for (String f : PRIVATE) assertFalse(caps.path("features").path(f).asBoolean(true), f);
        assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        assertFalse(caps.path("privateGate").path("privateMasterAllowed").asBoolean(true));
        for (String f : PRIVATE) assertFalse(caps.path("privateGate").path("capabilities").path(f).path("allowed").asBoolean(true), f);
    }

    @Test
    void productionServiceReportsNotConfiguredAndTheCapabilityIsPublicAndSeparateFromThePrivateGate() throws Exception {
        start(ChainConnector.notConfigured());
        try (TestClient c = paired()) {
            JsonNode s = c.call("byx.status").path("result");
            assertEquals("NOT_CONFIGURED", s.path("state").asText());
            assertFalse(s.path("configured").asBoolean(true) || s.path("reachable").asBoolean(true) || s.path("networkMatch").asBoolean(true));
            assertFalse(s.has("latestHeight") || s.has("chainId") || s.has("catchingUp") || s.has("blockTimeMs"), "nothing about a chain that is not configured");
            assertFalse(c.call("byx.denomMetadata").path("result").path("available").asBoolean(true));
            assertFalse(c.call("byx.supply").path("result").path("available").asBoolean(true));
            JsonNode caps = c.call("capabilities").path("result");
            Set<String> ops = new HashSet<>();
            caps.path("operations").forEach(o -> ops.add(o.asText()));
            assertTrue(ops.containsAll(Protocol.CHAIN_OPERATIONS));
            assertTrue(caps.path("features").path("chainRead").asBoolean());
            assertFalse(caps.path("features").path("chainConfigured").asBoolean(true));
            assertPrivateStillOff(c);
        }
    }

    @Test
    void aLiveFakeNodeIsServedAsMinimalTypedDtosAndNeverTouchesThePrivateGate() throws Exception {
        start(live());
        try (TestClient c = paired()) {
            JsonNode s = until(c, "byx.status", "state", "LIVE");
            assertEquals("LIVE", s.path("state").asText());
            assertTrue(s.path("configured").asBoolean() && s.path("reachable").asBoolean() && s.path("networkMatch").asBoolean());
            assertEquals("byx", s.path("chainId").asText());
            assertEquals(100, s.path("latestHeight").asLong());
            assertFalse(s.path("catchingUp").asBoolean(true));
            assertEquals(Set.of("state", "configured", "reachable", "networkMatch", "reason", "generation", "updatedAtMs", "nowMs", "chainId", "latestHeight", "catchingUp", "blockTimeMs", "blockAgeMs", "blockFreshness", "blockHash"),
                    fieldNames(s), "only the minimal typed fields: no raw JSON, no headers, no node internals");
            JsonNode d = c.call("byx.denomMetadata").path("result");
            assertTrue(d.path("available").asBoolean());
            assertEquals("ubyx", d.path("base").asText());
            assertEquals("BYX", d.path("display").asText());
            assertEquals(6, d.path("exponent").asInt());
            JsonNode sup = c.call("byx.supply").path("result");
            assertTrue(sup.path("available").asBoolean());
            assertEquals("1500000", sup.path("baseUnits").asText());
            assertEquals("1.500000 BYX", sup.path("display").asText());
            assertTrue(c.call("capabilities").path("result").path("features").path("chainConfigured").asBoolean());
            assertPrivateStillOff(c); // a chain pública LIVE não muda NADA do gate privado
        }
    }

    @Test
    void aWrongNetworkNeverShowsHealthyStateOverIpc() throws Exception {
        node = new FakeNode();
        node.chainId = "not-byx";
        start(new ChainConnector(Optional.of(node.config()), new LoopbackHttp(Duration.ofMillis(400), Duration.ofMillis(500)), new ChainConnector.Timing(10, 60_000, 30_000, 600_000), System::currentTimeMillis));
        try (TestClient c = paired()) {
            JsonNode s = until(c, "byx.status", "state", "NETWORK_MISMATCH");
            assertEquals("NETWORK_MISMATCH", s.path("state").asText());
            assertFalse(s.path("networkMatch").asBoolean(true));
            assertFalse(s.has("latestHeight"), "no height of the wrong network");
            assertFalse(c.call("byx.denomMetadata").path("result").path("available").asBoolean(true));
            assertFalse(c.call("byx.supply").path("result").path("available").asBoolean(true));
        }
    }

    @Test
    void thePanelCannotSendHostPortDenomUrlOrAnyArgumentAndThereIsNoGenericProxyOrWriteOperation() throws Exception {
        start(live());
        try (TestClient c = paired()) {
            for (String extra : List.of("\"url\":\"http://127.0.0.1:1/x\"", "\"host\":\"0.0.0.0\"", "\"port\":26657", "\"denom\":\"ubyx\"", "\"endpoint\":\"http://evil.example\"", "\"path\":\"/status\"", "\"chainId\":\"x\"")) {
                try (TestClient fresh = paired()) {
                    fresh.sendJson("{\"v\":1,\"id\":\"x\",\"op\":\"byx.status\"," + extra + "}");
                    assertEquals("bad_request", fresh.readJson().path("code").asText(), extra);
                }
            }
            for (String op : List.of("http.request", "rpc.call", "grpc.call", "chain.queryRaw", "fetchUrl", "proxy", "execute", "byx.send", "byx.broadcast", "byx.sign", "byx.wallet", "byx.tx", "byx.query",
                    "byx.grantGas", "byx.payment", "byx.merchant", "byx.governance", "byx.rawQuery", "byx.status.raw")) {
                assertEquals("unsupported_operation", c.call(op).path("error").path("code").asText(), op);
            }
        }
        assertEquals(0, node.paths.stream().filter(p -> !p.startsWith("GET ")).count(), "the node only ever received GET");
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> out = new HashSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---- guardas de código ---------------------------------------------------------------------------------------------------------------

    @Test
    void theChainPackageIsPublicReadOnlyAndNothingElseDependsOnIt() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/byx/service/chain"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                String stripped = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
                assertFalse(src.contains("byx.service.auth") || src.contains("byx.service.secrets") || src.contains("PrivateCapab"), f.getFileName() + " must not depend on auth, secrets or the private gate");
                assertFalse(stripped.matches("(?s).*\\b(double|float|Double|Float)\\b.*"), f.getFileName() + " must not use floating point (amounts are integers/BigInteger)");
                assertFalse(stripped.contains("getenv") || stripped.contains("getProperty") || stripped.contains("getBoolean"), f.getFileName() + " reads no environment or property");
                assertFalse(stripped.matches("(?s).*\\b(POST|PUT|DELETE|PATCH)\\b.*"), f.getFileName() + " is GET-only");
                if (!f.getFileName().toString().equals("LoopbackHttp.java")) {
                    assertFalse(stripped.contains("HttpClient") || stripped.contains("HttpURLConnection") || stripped.contains("Socket"), f.getFileName() + " must not open connections");
                }
            }
        }
        // o gate privado e a autoridade não conhecem a chain (isolamento nos dois sentidos)
        for (String f : List.of("PrivateCapabilityGate.java", "PrivateCapability.java", "PrivateOperation.java", "auth/AuthService.java", "auth/AuthPolicy.java")) {
            assertFalse(Files.readString(Path.of("src/main/java/byx/service/" + f)).contains("byx.service.chain"), f);
        }
        // só ChainConfig/ChainEndpoint/ChainProfile constroem configuração/origem; o artefato só compõe ChainConnector.production()
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Path.of("src/main/java/byx/service").relativize(f).toString();
                String src = Files.readString(f);
                if (!rel.equals("chain/ChainConfig.java") && !rel.equals("chain/ChainEndpoint.java") && !rel.equals("chain/ChainProfile.java")) {
                    assertFalse(src.contains("ChainConfig.of(") || src.contains("new ChainConfig(") || src.contains("ChainEndpoint.parse(") || src.contains("new ChainEndpoint("), rel + " must not build a node configuration");
                }
                if (!rel.startsWith("chain/") && !rel.equals("ServiceMain.java") && !rel.equals("ServiceInstance.java") && !rel.equals("Operations.java") && !rel.equals("ChainReadIpc.java")) {
                    assertFalse(src.contains("ChainConnector") || src.contains("LoopbackHttp"), rel + " must not use the chain connector");
                }
            }
        }
        String main = Files.readString(Path.of("src/main/java/byx/service/ServiceMain.java"));
        assertTrue(main.contains("ChainConnector.production()") && !main.contains("LoopbackHttp") && !main.contains("ChainConfig") && !main.contains("ChainEndpoint"), "production composes only the typed-profile connector");
        String ops = Files.readString(Path.of("src/main/java/byx/service/Operations.java"));
        assertFalse(ops.contains("getenv") || ops.contains("getProperty"), "the answers read no environment or property");
        assertTrue(ChainConnectorTestAccess.productionProfileMatchesTheBuildChoice());
    }
}
