package panel.localservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V2.1L (painel): o painel lê o status da chain SÓ pelo serviço (IPC tipado, sem argumentos) e trata qualquer desvio do contrato como ERROR. Servidor de mentira real no socket Unix; nenhum nó. */
class ChainStatusClientTest {
    private static final JsonMapper JSON = new JsonMapper();
    private Path home;
    private final List<FakeService> fakes = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "cs");
    }

    @AfterEach
    void down() throws Exception {
        fakes.forEach(FakeService::close);
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private FakeService fake(FakeService.Mode mode, String chainResult) throws Exception {
        FakeService f = new FakeService(home, mode);
        if (chainResult != null) f.chainResult = chainResult;
        fakes.add(f);
        return f;
    }

    private static ChainStatusClient.View parse(String json) throws Exception {
        return ChainStatusClient.parse(JSON.readTree(json));
    }

    private static final String LIVE = "{\"state\":\"LIVE\",\"configured\":true,\"reachable\":true,\"networkMatch\":true,\"reason\":\"NONE\",\"generation\":1,\"updatedAtMs\":1,\"nowMs\":2,"
            + "\"chainId\":\"byx\",\"latestHeight\":100,\"catchingUp\":false,\"blockTimeMs\":1791300000000}";

    @Test
    void aValidStatusIsReadOverTheTypedChannelWithNoArguments() throws Exception {
        fake(FakeService.Mode.GOOD, LIVE);
        var v = new ChainStatusClient(new LocalServiceClient(home)).read();
        assertEquals("LIVE", v.state());
        assertTrue(v.configured() && v.reachable() && v.networkMatch());
        assertEquals("byx", v.chainId());
        assertEquals(100L, v.latestHeight());
        assertEquals(Boolean.FALSE, v.catchingUp());
        assertEquals("NONE", v.reason());
    }

    @Test
    void notConfiguredIsTheProductionAnswerAndCarriesNothingAboutAChain() throws Exception {
        fake(FakeService.Mode.GOOD, null);
        var v = new ChainStatusClient(new LocalServiceClient(home)).read();
        assertEquals("NOT_CONFIGURED", v.state());
        assertFalse(v.configured() || v.reachable() || v.networkMatch());
        assertNull(v.latestHeight());
        assertNull(v.chainId());
    }

    @Test
    void anAbsentHostileOrBrokenServiceIsAnErrorNeverHealthy() throws Exception {
        assertEquals("ERROR", new ChainStatusClient(new LocalServiceClient(home)).read().state(), "no service running");
        assertEquals("SERVICE_UNAVAILABLE", new ChainStatusClient(new LocalServiceClient(home)).read().reason());
        for (FakeService.Mode mode : List.of(FakeService.Mode.IMPOSTOR, FakeService.Mode.GARBAGE, FakeService.Mode.REJECTS_CLIENT, FakeService.Mode.OVERSIZE)) {
            Path h = Files.createTempDirectory(Path.of("/tmp"), "cs");
            try {
                FakeService f = new FakeService(h, mode);
                try {
                    var v = new ChainStatusClient(new LocalServiceClient(h)).read();
                    assertEquals("ERROR", v.state(), mode.name());
                } finally {
                    f.close();
                }
            } finally {
                try (var walk = Files.walk(h)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    @Test
    void anyDeviationFromTheContractBecomesErrorWithAFixedReason() throws Exception {
        String ok = "\"configured\":true,\"reachable\":true,\"networkMatch\":true,\"reason\":\"NONE\"";
        for (String bad : new String[] {
                "{\"state\":\"HEALTHY\"," + ok + "}", // estado fora da lista fechada
                "{\"state\":\"live\"," + ok + "}",
                "{\"state\":\"LIVE\",\"configured\":\"yes\",\"reachable\":true,\"networkMatch\":true,\"reason\":\"NONE\"}",
                "{\"state\":\"LIVE\"," + ok + "}", // LIVE sem altura nem bloco
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":0,\"blockTimeMs\":5}",
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":1000000000000000,\"blockTimeMs\":5}",
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":\"7\",\"blockTimeMs\":5}",
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":7,\"blockTimeMs\":-1}",
                "{\"state\":\"LIVE\",\"configured\":true,\"reachable\":true,\"networkMatch\":false,\"reason\":\"NONE\",\"latestHeight\":7,\"blockTimeMs\":5}", // saudável com rede divergente
                "{\"state\":\"NETWORK_MISMATCH\",\"configured\":true,\"reachable\":true,\"networkMatch\":false,\"reason\":\"NETWORK_MISMATCH\",\"latestHeight\":7,\"blockTimeMs\":5}", // altura da rede errada
                "{\"state\":\"OFFLINE\",\"configured\":true,\"reachable\":false,\"networkMatch\":false,\"reason\":\"TIMEOUT\",\"latestHeight\":7,\"blockTimeMs\":5}",
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":7,\"blockTimeMs\":5,\"chainId\":\"by x!\"}",
                "{\"state\":\"LIVE\"," + ok + ",\"latestHeight\":7,\"blockTimeMs\":5,\"catchingUp\":\"no\"}",
                "{\"state\":\"ERROR\",\"configured\":true,\"reachable\":true,\"networkMatch\":false,\"reason\":\"bad reason\"}",
                "{\"state\":\"ERROR\",\"configured\":true,\"reachable\":true,\"networkMatch\":false}",
                "{}"}) {
            var v = parse(bad);
            assertEquals("ERROR", v.state(), bad);
            assertEquals("CONTRACT_VIOLATION", v.reason(), bad);
        }
        for (String state : ChainStatusClient.STATES) {
            assertFalse(state.equals("HEALTHY"));
        }
        assertEquals(Set.of("NOT_CONFIGURED", "CONNECTING", "OFFLINE", "SYNCING", "LIVE", "STALE", "NETWORK_MISMATCH", "ERROR"), ChainStatusClient.STATES);
    }

    @Test
    void onlyTheThreePublicChainOperationsExistAndNothingGenericCanBeSent() {
        assertEquals(Set.of("byx.status", "byx.denomMetadata", "byx.supply"), LocalServiceClient.CHAIN_OPERATIONS);
        LocalServiceClient client = new LocalServiceClient(home);
        for (String op : List.of("http.request", "rpc.call", "grpc.call", "chain.queryRaw", "fetchUrl", "proxy", "execute", "byx.send", "byx.broadcast", "byx.sign", "byx.status\",\"host\":\"0.0.0.0", "")) {
            assertThrows(IllegalArgumentException.class, () -> client.chainCall(op), op);
        }
    }
}
