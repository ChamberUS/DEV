package byx.service.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Provas de FONTE: o produto não tem como habilitar transações por ambiente/propriedade/arquivo, não tem assinante nem transporte reais e não tem operação genérica. */
class TxNoBypassGuardTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noSwitchNamesAnywhereInProductionSources() throws IOException {
        for (Path p : sources()) {
            String src = Files.readString(p);
            for (String forbidden : new String[] {"enableTx", "allowTx", "skipTxSecurity", "txTestMode", "fakeSignerMode", "broadcastAnyway", "FakeSigner", "FakeChainTxTransport",
                    "DRAFT_TESTNET", "DraftTestnet", "fake-tx", "--fake"}) {
                assertFalse(src.contains(forbidden), p + " must not contain " + forbidden);
            }
        }
    }

    @Test
    void theTxPackageReadsNoEnvironmentPropertyOrFile() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN.resolve("byx/service/tx"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                for (String forbidden : new String[] {"System.getenv", "System.getProperty", "Boolean.getBoolean", "Integer.getInteger", "Long.getLong", "java.io.File", "Files.read",
                        "Files.write", "Files.newInput", "Properties", "ResourceBundle", "getResourceAsStream"}) {
                    assertFalse(src.contains(forbidden), p + " must not use " + forbidden + ": there is no external way to enable or configure TX");
                }
            }
        }
    }

    @Test
    void theOnlyProductionImplementationsAreDisabledAbsentOrDenied() throws IOException {
        int signers = 0;
        int transports = 0;
        int gates = 0;
        int policies = 0;
        for (Path p : sources()) {
            String src = Files.readString(p);
            signers += count(src, "implements TxSigner") + count(src, "new TxSigner()");
            transports += count(src, "implements ChainTxTransport") + count(src, "new ChainTxTransport()");
            policies += count(src, "implements TxPolicy") + count(src, "new TxPolicy()");
            gates += count(src, "implements TxGate");
        }
        assertEquals(1, signers, "only the UNAVAILABLE signer exists in production");
        assertEquals(1, transports, "only the ABSENT transport exists in production (no HTTP/gRPC TX transport)");
        assertEquals(1, policies, "only TX_DISABLED exists in production");
        assertEquals(0, gates, "the master gate is a constant; no class can implement a different one in production");
        String gate = Files.readString(MAIN.resolve("byx/service/tx/TxGate.java"));
        assertTrue(gate.contains("boolean TX_MUTATIONS_ALLOWED = false;"));
        String ports = Files.readString(MAIN.resolve("byx/service/tx/TxPorts.java"));
        assertTrue(ports.contains("TxSigner UNAVAILABLE") && ports.contains("ChainTxTransport ABSENT") && ports.contains("TxKeys UNAVAILABLE") && ports.contains("DENY_ALL"));
    }

    @Test
    void productionCompositionIsTheDisabledOneAndNeverBuildsAnEnabledService() throws IOException {
        String main = Files.readString(MAIN.resolve("byx/service/ServiceMain.java"));
        assertTrue(main.contains("TxProduction.disabled("));
        assertFalse(main.contains("new TxService") || main.contains("TxGate") || main.contains("TxPolicy"));
        String prod = Files.readString(MAIN.resolve("byx/service/tx/TxProduction.java"));
        assertFalse(prod.contains("new TxService("));
        // nenhum ponto de produção constrói TxService com portas diferentes das desabilitadas, exceto o próprio TxService.disabled
        int constructions = 0;
        for (Path p : sources()) {
            constructions += count(Files.readString(p), "new TxService(");
        }
        assertEquals(1, constructions, "TxService is constructed only inside TxService.disabled(...)");
    }

    @Test
    void noGenericSigningOrRawBroadcastOrRoutingSurfaceExists() throws IOException {
        Pattern bad = Pattern.compile("(?i)\"tx\\.(sign|signBytes|broadcast|broadcastRawTx|sendAnyMessage|protobufAny|genericCosmosRequest|rawHttp|rawGrpc|rawTx)\"");
        for (Path p : sources()) {
            Matcher m = bad.matcher(Files.readString(p));
            assertFalse(m.find(), p + " defines a generic tx operation");
        }
        String ports = Files.readString(MAIN.resolve("byx/service/tx/TxPorts.java"));
        assertFalse(ports.contains("byte[] bytes) throws TxSignerException"), "the signer never signs raw bytes from callers");
        assertTrue(ports.contains("SignedTx sign(TxSignRequest request)"));
    }

    @Test
    void onlyTheTransactionEngineConstructsTheRestrictedSignRequest() throws IOException {
        int constructions = 0;
        for (Path path : sources()) {
            String source = Files.readString(path);
            int count = count(source, "new TxSignRequest(");
            if (count != 0) assertEquals("TxService.java", path.getFileName().toString());
            constructions += count;
        }
        assertEquals(1, constructions);
        String ports = Files.readString(MAIN.resolve("byx/service/tx/TxPorts.java"));
        assertTrue(ports.contains("public static final class TxSignRequest"));
        assertTrue(ports.contains("        TxSignRequest(TxKey key, TxQuote quote)"));
        assertFalse(ports.contains("public TxSignRequest(") || ports.contains("protected TxSignRequest("));
    }

    private static int count(String src, String needle) {
        int n = 0;
        int i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
