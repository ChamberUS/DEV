package byx.service.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.ServiceInstance;
import byx.service.TestClient;
import byx.service.chain.ChainConnector;
import byx.service.identity.IdentityPolicy;
import byx.service.market.FakeMarket;
import byx.service.market.MarketFeed;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** IPC de transação sobre o serviço REAL (socket, handshake): produção = TX_DISABLED sem UI; com portas de teste = superfície fechada e estritamente validada. */
class TxIpcTest {
    private ServiceInstance service;
    private Path home;

    @AfterEach
    void down() throws Exception {
        if (service != null) {
            service.close();
        }
        if (home != null) {
            try (var walk = Files.walk(home)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void start(TxIpc tx) throws Exception {
        start(tx, null);
    }

    /** peerKeys: o provedor de chave de peer do kernel (teste injeta um valor fixo; null = sem peer verificado, chave 0). */
    private void start(TxIpc tx, byx.service.identity.PeerKeys.Provider peerKeys) throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "tx");
        service = ServiceInstance.start(home, new ServiceInstance.Limits(4, 600, 600, 2_000, 600), new MarketFeed(new FakeMarket.Ws(), new FakeMarket.Http(), FakeMarket.fast()),
                IdentityPolicy.development(), null, peerKeys, ChainConnector.notConfigured(), tx);
    }

    private TestClient paired() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        return c;
    }

    private static JsonNode call(TestClient c, String json) throws Exception {
        c.sendJson(json);
        return c.readJson();
    }

    private static String req(String op, String... kv) {
        StringBuilder b = new StringBuilder("{\"v\":1,\"id\":\"r1\",\"op\":\"" + op + "\"");
        for (int i = 0; i < kv.length; i += 2) {
            b.append(",\"").append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return b.append('}').toString();
    }

    private static String prepare(String opId, String amount, String memo, String mode, String recipient) {
        return req("tx.prepareBankSend", "session", TxFx.TOKEN, "operation", opId, "sender", "primary", "recipient", recipient, "amountUbyx", amount, "memo", memo, "feeMode", mode);
    }

    private TxIpc enabled(TxFx fx) {
        return new TxIpc(fx.svc);
    }

    // ---- produção -------------------------------------------------------------------------------------------------------------------

    @Test
    void productionCompositionAnswersTxDisabledToEveryTxOperationWithoutAnyUi() throws Exception {
        start(TxProduction.disabled(null, ServiceInstance.txChain(ChainConnector.notConfigured())));
        try (TestClient c = paired()) {
            for (String op : TxIpc.OPERATIONS) {
                JsonNode r = call(c, req(op, "session", TxFx.TOKEN, "operation", "0".repeat(32)));
                assertFalse(r.path("ok").asBoolean(true), op);
                assertEquals("TX_DISABLED", r.path("error").path("code").asText(), op);
            }
            // mesmo um pedido bem formado e completo, e pedidos malformados: TX_DISABLED primeiro (nada vaza sobre campos, sessão ou política)
            assertEquals("TX_DISABLED", call(c, prepare("1".repeat(32), "1000", "m", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("TX_DISABLED", call(c, "{\"v\":1,\"id\":\"r1\",\"op\":\"tx.prepareBankSend\",\"garbage\":1}").path("error").path("code").asText());
            assertEquals("TX_DISABLED", call(c, req("tx.sign", "bytes", "AAAA")).path("error").path("code").asText());
            JsonNode caps = c.call("capabilities").path("result");
            assertFalse(caps.path("features").path("txMutations").asBoolean(true));
            assertFalse(caps.path("tx").path("mutationsAllowed").asBoolean(true));
            assertEquals("TX_DISABLED", caps.path("tx").path("policy").asText());
            assertEquals("UNAVAILABLE", caps.path("tx").path("signer").asText());
            assertEquals("ABSENT", caps.path("tx").path("transport").asText());
            assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        }
    }

    @Test
    void theDefaultStartOverloadsAlsoComposeTheDisabledTxSurface() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "tx");
        service = ServiceInstance.start(home);
        try (TestClient c = paired()) {
            assertEquals("TX_DISABLED", call(c, prepare("2".repeat(32), "5", "", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
        }
    }

    @Test
    void thereIsNoSignOrBroadcastOrGenericOperation() throws Exception {
        Set<String> expected = Set.of("tx.prepareBankSend", "tx.getQuote", "tx.confirm", "tx.getStatus", "tx.cancel");
        assertEquals(expected, TxIpc.OPERATIONS);
        for (String forbidden : new String[] {"sign", "signBytes", "broadcast", "broadcastRawTx", "sendAnyMessage", "protobufAny", "genericCosmosRequest", "rawHttp", "rawGrpc", "rawTx"}) {
            assertFalse(TxIpc.OPERATIONS.contains("tx." + forbidden), forbidden);
        }
        start(new TxIpc(new TxFx().svc), ch -> TxFx.PEER);
        try (TestClient c = paired()) {
            for (String forbidden : new String[] {"sign", "signBytes", "broadcast", "broadcastRawTx", "sendAnyMessage", "protobufAny", "genericCosmosRequest", "rawHttp", "rawGrpc", "rawTx"}) {
                JsonNode r = call(c, req("tx." + forbidden, "session", TxFx.TOKEN, "operation", "0".repeat(32), "bytes", "AAAA"));
                assertFalse(r.path("ok").asBoolean(true), forbidden);
                assertEquals("BAD_REQUEST", r.path("error").path("code").asText(), forbidden);
            }
            Set<String> ops = new HashSet<>();
            c.call("capabilities").path("result").path("operations").forEach(o -> ops.add(o.asText()));
            assertTrue(ops.containsAll(expected));
            assertTrue(ops.stream().filter(o -> o.startsWith("tx.")).allMatch(expected::contains));
        }
    }

    // ---- com portas de teste habilitadas -------------------------------------------------------------------------------------------

    @Test
    void closedTypedSurfaceRunsTheFakePipelineEndToEndOverTheRealSocket() throws Exception {
        TxFx fx = new TxFx();
        start(enabled(fx), ch -> TxFx.PEER); // peer "verificado pelo kernel" injetado pelo teste
        try (TestClient c = paired()) {
            JsonNode p = call(c, prepare("a".repeat(32), "1000000", "hello", "STANDARD", TxFx.RECIPIENT));
            assertTrue(p.path("ok").asBoolean(), p.toString());
            JsonNode q = p.path("result").path("quote");
            assertEquals("AWAITING_CONFIRMATION", p.path("result").path("state").asText());
            assertEquals("1000000", q.path("amountUbyx").asText());
            assertEquals("2750", q.path("feeUbyx").asText());
            assertEquals("1002750", q.path("totalDebitUbyx").asText());
            assertEquals("0.025", q.path("gasPrice").asText());
            String quoteId = q.path("quoteId").asText();
            JsonNode g = call(c, req("tx.getQuote", "session", TxFx.TOKEN, "operation", "a".repeat(32), "quote", quoteId));
            assertEquals(quoteId, g.path("result").path("quote").path("quoteId").asText());
            JsonNode done = call(c, req("tx.confirm", "session", TxFx.TOKEN, "operation", "a".repeat(32), "quote", quoteId));
            assertEquals("SUBMITTED", done.path("result").path("state").asText(), done.toString());
            JsonNode again = call(c, req("tx.confirm", "session", TxFx.TOKEN, "operation", "a".repeat(32), "quote", quoteId));
            assertEquals(done.path("result").path("txHash").asText(), again.path("result").path("txHash").asText());
            assertEquals(1, fx.signer.signed.get());
            assertFalse(done.toString().contains("bytes"), "no signed bytes ever reach the panel");
            // outra sessão (outro peer/conta) não vê a operação
            JsonNode other = call(c, req("tx.getStatus", "session", TxFx.TOKEN_B, "operation", "a".repeat(32)));
            assertEquals("UNAUTHORIZED", other.path("error").path("code").asText(), "token B is bound to another peer");
        }
    }

    @Test
    void peerlessOrUnauthenticatedCallersGetUnauthorizedWhenEnabled() throws Exception {
        TxFx fx = new TxFx();
        start(enabled(fx));
        try (TestClient c = paired()) {
            JsonNode r = call(c, prepare("b".repeat(32), "1000", "", "LOW", TxFx.RECIPIENT));
            assertFalse(r.path("ok").asBoolean(true));
            assertEquals("UNAUTHORIZED", r.path("error").path("code").asText());
            assertEquals(0, fx.transport.simulateCalls.get());
        }
    }

    @Test
    void strictValidationRejectsExtraFieldsWrongTypesAndBadValues() throws Exception {
        TxFx fx = new TxFx();
        start(enabled(fx), ch -> TxFx.PEER);
        try (TestClient c = paired()) {
            String ok = prepare("c".repeat(32), "1000", "m", "LOW", TxFx.RECIPIENT);
            // campo a mais
            assertEquals("BAD_REQUEST", call(c, ok.replace("}", ",\"extra\":\"x\"}")).path("error").path("code").asText());
            // tipo errado (número / objeto aninhado / array)
            assertEquals("BAD_REQUEST", call(c, ok.replace("\"amountUbyx\":\"1000\"", "\"amountUbyx\":1000")).path("error").path("code").asText());
            assertEquals("BAD_REQUEST", call(c, ok.replace("\"memo\":\"m\"", "\"memo\":{\"a\":1}")).path("error").path("code").asText());
            assertEquals("BAD_REQUEST", call(c, ok.replace("\"memo\":\"m\"", "\"memo\":[\"a\"]")).path("error").path("code").asText());
            // campo ausente
            assertEquals("BAD_REQUEST", call(c, req("tx.prepareBankSend", "session", TxFx.TOKEN, "operation", "c".repeat(32))).path("error").path("code").asText());
            // valores
            assertEquals("INVALID_AMOUNT", call(c, prepare("c".repeat(32), "0", "m", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("INVALID_AMOUNT", call(c, prepare("c".repeat(32), "-1", "m", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("INVALID_AMOUNT", call(c, prepare("c".repeat(32), "1.5", "m", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("INVALID_ADDRESS", call(c, prepare("c".repeat(32), "10", "m", "LOW", "cosmos1xyz")).path("error").path("code").asText());
            assertEquals("INVALID_FEE_MODE", call(c, prepare("c".repeat(32), "10", "m", "TURBO", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("MEMO_TOO_LARGE", call(c, prepare("c".repeat(32), "10", "m".repeat(300), "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("BAD_REQUEST", call(c, prepare("short", "10", "m", "LOW", TxFx.RECIPIENT)).path("error").path("code").asText());
            assertEquals("BAD_REQUEST", call(c, ok.replace(TxFx.TOKEN, "tooshort")).path("error").path("code").asText());
            assertEquals(0, fx.transport.simulateCalls.get(), "nothing invalid ever reaches the chain transport");
        }
    }
}
