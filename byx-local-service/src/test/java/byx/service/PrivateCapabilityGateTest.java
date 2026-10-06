package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.market.FakeMarket;
import byx.service.market.MarketFeed;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V2.1C: capacidades privadas ficam indisponíveis por decisão estática; nada enviado pela UI ou lido de configuração as liga. */
class PrivateCapabilityGateTest {
    private static final List<String> PRIVATE = List.of("accountData", "notifications", "adminOperations", "secretIntegrations");
    private Path home;
    private ServiceInstance service;

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "pg");
        service = ServiceInstance.start(home, new ServiceInstance.Limits(4, 600, 600, 2_000, 600),
                new MarketFeed(new FakeMarket.Ws(), new FakeMarket.Http(), FakeMarket.fast()));
    }

    @AfterEach
    void down() throws Exception {
        service.close();
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private TestClient paired() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        return c;
    }

    private void assertAllPrivateOff(JsonNode caps) {
        for (String f : PRIVATE) {
            assertFalse(caps.path("features").path(f).asBoolean(true), f);
            assertTrue(caps.path("features").has(f), f + " is declared (as blocked), not omitted");
        }
        assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        assertEquals(PrivateCapabilityGate.PREREQUISITES.size(), caps.path("privateGate").path("unmetPrerequisites").size());
    }

    @Test
    void theDecisionIsAStaticFalseAndMarketDataIsIndependentOfIt() throws Exception {
        assertFalse(PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED);
        assertFalse(PrivateCapabilityGate.allowed("accountData"));
        assertFalse(PrivateCapabilityGate.allowed(null));
        assertFalse(PrivateCapabilityGate.PREREQUISITES.isEmpty());
        assertFalse(PrivateCapabilityGate.PREREQUISITES.contains("verified_peer_identity"), "V2.1D satisfied the identity prerequisite...");
        assertFalse(PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED, "...and that alone opens nothing");
        try (TestClient c = paired()) {
            JsonNode caps = c.call("capabilities").path("result");
            assertAllPrivateOff(caps);
            assertTrue(caps.path("features").path("marketData").asBoolean(), "public market data stays available");
        }
    }

    @Test
    void noSystemPropertyOrFlagChangesTheDecision() throws Exception {
        List<String> keys = List.of("byx.private", "byx.privateCapabilities", "byx.private.allowed", "byx.accountData", "byx.service.private", "PRIVATE_CAPABILITIES_ALLOWED",
                "security.dev.mode", "dev.mode", "debug");
        try {
            keys.forEach(k -> System.setProperty(k, "true"));
            try (TestClient c = paired()) {
                assertAllPrivateOff(c.call("capabilities").path("result"));
            }
        } finally {
            keys.forEach(System::clearProperty);
        }
    }

    @Test
    void nothingTheUiSendsCanEnableThem() throws Exception {
        try (TestClient c = paired()) {
            for (String op : List.of("private.enable", "capabilities.set", "config.set", "feature.enable", "account.read", "account.balance", "notifications.read",
                    "admin.grant", "secrets.get", "binance.account", "userDataStream.start", "capability.private=true")) {
                JsonNode r = c.call(op);
                assertEquals("unsupported_operation", r.path("error").path("code").asText(), op);
            }
            for (String extra : List.of("\"privateCapabilitiesAllowed\":true", "\"features\":{\"accountData\":true}", "\"role\":\"admin\"", "\"userId\":1", "\"mfa\":true", "\"config\":{\"private\":true}")) {
                try (TestClient fresh = paired()) {
                    fresh.sendJson("{\"v\":1,\"id\":\"x\",\"op\":\"capabilities\"," + extra + "}");
                    assertEquals("bad_request", fresh.readJson().path("code").asText(), extra);
                }
            }
        }
        try (TestClient c = paired()) {
            assertAllPrivateOff(c.call("capabilities").path("result"));
        }
    }

    @Test
    void theServiceHasNoPrivateOrSecretBackedCode() throws Exception {
        String ops = String.join(",", Protocol.OPERATIONS) + "," + String.join(",", Protocol.MARKET_OPERATIONS);
        for (String banned : List.of("account", "order", "notification", "admin", "secret", "key", "private")) {
            assertFalse(ops.toLowerCase().contains(banned), "no operation name mentions " + banned + ": " + ops);
        }
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (String banned : List.of("X-MBX-APIKEY", "listenKey", "/fapi/v1/order", "/fapi/v2/account", "signature=", "SecKeychain", "SecItem", "api_key", "apiKey", "apiSecret")) {
                    assertFalse(src.contains(banned), f + " must not contain " + banned);
                }
            }
        }
        String ops2 = Files.readString(Path.of("src/main/java/byx/service/Operations.java"));
        assertFalse(ops2.contains("getenv") || ops2.contains("getProperty") || ops2.contains("getBoolean"), "the capability answer reads no environment or property");
        assertTrue(Files.readString(Path.of("src/main/java/byx/service/PrivateCapabilityGate.java")).contains("PRIVATE_CAPABILITIES_ALLOWED = false;"));
        String gate = Files.readString(Path.of("src/main/java/byx/service/PrivateCapabilityGate.java"));
        assertFalse(gate.replaceAll("(?s)/\\*.*?\\*/", "").contains("getenv"), "the gate reads no environment");
        assertFalse(gate.replaceAll("(?s)/\\*.*?\\*/", "").contains("getProperty"), "the gate reads no property");
    }
}
