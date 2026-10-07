package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.chain.ChainConnector;
import byx.service.chain.FakeNode;
import byx.service.chain.LoopbackHttp;
import byx.service.identity.IdentityPolicy;
import byx.service.market.FakeMarket;
import byx.service.market.MarketFeed;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** V2.1N: leituras de módulo pelo IPC verificado: pedidos tipados e mínimos, DTOs sem JSON do nó, NOT_CONFIGURED sem rede, feesplit NOT_EXPOSED, independência do gate privado. */
class ChainReadIpcTest {
    private static final String ADDR = "byx14uzu3ja88cpf5fktzp9rwt5zqhppv0j59qev7z";
    private Path home;
    private ServiceInstance service;
    private FakeNode node;
    private int seq;

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
        home = Files.createTempDirectory(Path.of("/tmp"), "cr");
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

    private JsonNode raw(TestClient c, String json) throws Exception {
        c.sendJson(json.replace("$ID", "r" + (++seq)));
        return c.readJson();
    }

    private JsonNode op(TestClient c, String op, String args) throws Exception {
        return raw(c, "{\"v\":1,\"id\":\"$ID\",\"op\":\"" + op + "\"" + (args == null ? "" : ",\"args\":" + args) + "}").path("result");
    }

    private void waitLive(TestClient c) throws Exception {
        long end = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < end && !"LIVE".equals(c.call("byx.status").path("result").path("state").asText())) Thread.sleep(25);
    }

    private void assertGateClosed(TestClient c) throws Exception {
        JsonNode caps = c.call("capabilities").path("result");
        assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        for (String f : List.of("accountData", "notifications", "adminOperations", "secretIntegrations")) {
            assertFalse(caps.path("features").path(f).asBoolean(true), f);
            assertFalse(caps.path("privateGate").path("capabilities").path(f).path("allowed").asBoolean(true), f);
        }
    }

    @Test
    void notConfiguredAnswersNotConfiguredForEveryModuleReadAndNeverTouchesTheNetwork() throws Exception {
        start(ChainConnector.notConfigured());
        try (TestClient c = paired()) {
            assertEquals("NOT_CONFIGURED", op(c, "byx.lojas.getMerchant", "{\"id\":\"1\"}").path("failure").asText());
            assertEquals("NOT_CONFIGURED", op(c, "byx.lojas.listMerchants", null).path("failure").asText());
            assertEquals("NOT_CONFIGURED", op(c, "byx.payments.getPayment", "{\"id\":\"1\"}").path("failure").asText());
            assertEquals("NOT_CONFIGURED", op(c, "byx.certificados.getCertificate", "{\"id\":\"1\"}").path("failure").asText());
            assertEquals("NOT_CONFIGURED", op(c, "byx.bank.balance", "{\"address\":\"" + ADDR + "\"}").path("failure").asText());
            JsonNode fee = op(c, "byx.feesplit.params", null);
            assertEquals("NOT_EXPOSED", fee.path("status").asText());
            assertFalse(fee.path("exposed").asBoolean(true));
            assertEquals("DOCUMENTED_DEFAULT_NOT_QUERIED", fee.path("source").asText());
            assertEquals(6000, fee.path("allocationBps").path("distribution").asInt());
            assertEquals(3000, fee.path("allocationBps").path("treasury").asInt());
            assertEquals(1000, fee.path("allocationBps").path("burn").asInt());
            JsonNode health = op(c, "byx.moduleHealth", null);
            assertEquals(5, health.path("modules").size());
            assertEquals("NOT_CONFIGURED", health.path("node").asText());
            assertEquals(0, health.path("reads").path("fetches").asInt());
            assertGateClosed(c);
        }
    }

    @Test
    void aLiveFakeNodeIsServedAsTypedDtosWithFreshnessAndTheGateStaysClosed() throws Exception {
        start(live());
        node.override.put("/byx/payments/v1/payment_requests/9", new FakeNode.Reply(200,
                "{\"payment_request\":" + byx.service.chain.ModuleFixtures.payment("9", "2", "2500000", "PAYMENT_STATUS_EXPIRED") + "}", 0, null));
        try (TestClient c = paired()) {
            waitLive(c);
            assertGateClosed(c);
            JsonNode a = op(c, "byx.payments.getPayment", "{\"id\":\"9\"}");
            assertEquals("OK", a.path("status").asText());
            assertEquals("LIVE", a.path("freshness").asText());
            assertEquals("2500000", a.path("data").path("amountUbyx").asText());
            assertEquals("2.500000 BYX", a.path("data").path("amountDisplay").asText());
            assertEquals("EXPIRED", a.path("data").path("status").asText());
            assertEquals("byx", a.path("chainId").asText());
            JsonNode b = op(c, "byx.payments.getPayment", "{\"id\":\"9\"}");
            assertEquals("CACHED", b.path("freshness").asText());
            assertFalse(op(c, "byx.lojas.getMerchant", "{\"id\":\"404\"}").path("failure").asText().isEmpty(), "an unmapped route yields a typed failure");
            JsonNode health = op(c, "byx.moduleHealth", null);
            assertEquals("LIVE", health.path("node").asText());
            assertTrue(health.path("reads").path("cacheHits").asInt() >= 1);
            assertGateClosed(c);
            assertEquals("LIVE", c.call("byx.status").path("result").path("state").asText(), "module activity does not change chain health");
        }
        assertEquals(0, node.paths.stream().filter(p -> !p.startsWith("GET ")).count());
    }

    @Test
    void anythingButTheTypedArgumentsIsInvalidRequestWithNoNodeTraffic() throws Exception {
        start(live());
        try (TestClient c = paired()) {
            waitLive(c);
            String[] bad = {
                    "{\"id\":\"../1\"}", "{\"id\":\"1\",\"url\":\"http://x\"}", "{\"id\":1}", "{\"id\":\"1\",\"host\":\"evil\"}", "{}", "{\"path\":\"/x\"}", "[]", "\"1\"", "{\"id\":\"0\"}",
                    "{\"id\":\"" + "9".repeat(40) + "\"}"};
            for (String args : bad) {
                JsonNode r = op(c, "byx.lojas.getMerchant", args);
                assertEquals("FAILED", r.path("status").asText(), args);
                assertEquals("INVALID_REQUEST", r.path("failure").asText(), args);
            }
            assertEquals("INVALID_REQUEST", op(c, "byx.lojas.listMerchants", "{\"limit\":0}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.lojas.listMerchants", "{\"limit\":99}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.lojas.listMerchants", "{\"limit\":\"5\"}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.lojas.listMerchants", "{\"cursor\":\"v1.a.AAAA\"}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.bank.balance", "{\"address\":\"byx1badbadbadbadbadbadbadbadbadbadbadbadbad\"}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.payments.params", "{\"id\":\"1\"}").path("failure").asText());
            assertEquals("INVALID_REQUEST", op(c, "byx.moduleHealth", "{\"x\":1}").path("failure").asText());
            assertEquals("INVALID_REQUEST", raw(c, "{\"v\":1,\"id\":\"$ID\",\"op\":\"byx.lojas.getMerchant\",\"args\":{\"id\":\"1\"},\"extra\":1}").path("result").path("failure").asText());
            assertEquals(0, node.paths.stream().filter(p -> p.contains("/byx/") || p.contains("/balances/")).count(), "no module request left the service for invalid input");
        }
    }

    @Test
    void noGenericQueryProxyOrMutationOperationExistsOverIpc() throws Exception {
        start(live());
        try (TestClient c = paired()) {
            for (String op : List.of("byx.query", "byx.rest", "grpc.call", "rpc.call", "queryRaw", "fetch", "proxy", "byx.lojas.createMerchant", "byx.lojas.createMerchantV2", "byx.lojas.createLojista",
                    "byx.payments.createPayment", "byx.payments.pay", "byx.certificados.issue", "byx.certificados.revoke", "byx.bank.send", "byx.feesplit.update", "byx.broadcast", "byx.sign")) {
                assertEquals("unsupported_operation", c.call(op).path("error").path("code").asText(), op);
            }
            JsonNode caps = c.call("capabilities").path("result");
            for (String shipped : List.of("byx.lojas.getMerchant", "byx.lojas.listMerchants", "byx.payments.getPayment", "byx.payments.listByStore", "byx.payments.params", "byx.certificados.getCertificate",
                    "byx.certificados.listByMerchant", "byx.bank.balance", "byx.feesplit.params", "byx.moduleHealth")) {
                boolean found = false;
                for (JsonNode o : caps.path("operations")) found |= o.asText().equals(shipped);
                assertTrue(found, shipped);
            }
        }
    }
}
