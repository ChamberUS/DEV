package byx.service.signer;

import static org.junit.jupiter.api.Assertions.*;

import byx.service.tx.SignerRequestFixture;
import byx.service.tx.TxPorts.TxSignRequest;
import byx.service.tx.TxPorts.TxSigner;
import byx.service.tx.TxPorts.TxSignerException;
import byx.service.tx.TxGate;
import byx.service.tx.TxState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignerClientTest {
    private static Path dir, binary, production;
    private static String sha;
    private static JsonNode vector;
    private static byte[] pub;

    @BeforeAll static void buildTestOnlyHarness() throws Exception {
        dir = Files.createTempDirectory(Path.of("/private/tmp"), "byx-sign-");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        binary = dir.resolve("synthetic-tests"); production = dir.resolve("unavailable-helper");
        compile("test", "-c", "-o", binary.toString(), "./internal/signer");
        compile("build", "-trimpath", "-o", production.toString(), "./cmd/byx-signer-helper");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(production, PosixFilePermissions.fromString("rwx------"));
        sha = CosmosBankSend.hash(Files.readAllBytes(binary));
        try (var in = SignerClientTest.class.getResourceAsStream("/tx/byx-direct-vector.json")) { vector = new ObjectMapper().readTree(in); }
        pub = CosmosBankSend.HEX.parseHex(vector.path("public_key").asText());
        System.out.println("SYNTHETIC_HELPER_SIZE=" + Files.size(binary) + " PRODUCTION_HELPER_SIZE=" + Files.size(production));
    }
    private static void compile(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>(List.of("/usr/bin/nice", "-n", "10", "/usr/local/bin/go"));
        cmd.addAll(List.of(args));
        var builder = new ProcessBuilder(cmd).directory(Path.of("signer-helper").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("GOMAXPROCS", "2"); builder.environment().put("GOFLAGS", "-mod=readonly -p=1"); builder.environment().put("GOMEMLIMIT", "256MiB");
        Process p = builder.start(); if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("bounded Go build timeout"); }
        assertEquals(0, p.exitValue());
    }
    @AfterAll static void cleanup() throws Exception {
        if (dir != null) { Files.deleteIfExists(binary); Files.deleteIfExists(production); Files.deleteIfExists(dir.resolve("alias")); Files.deleteIfExists(dir); }
    }
    private LegacyStdioSignerFixture client(String mode) { return new LegacyStdioSignerFixture(binary, sha, pub, List.of("-test.run=^TestProcessHarness$", "--", mode), Duration.ofSeconds(2)); }
    private TxSignRequest request() { return SignerRequestFixture.confirmed(vector).request(); }

    @Test void realProcessSyntheticSigningMatchesGoldenBytesAndNeverBroadcasts() throws Exception {
        var f = SignerRequestFixture.confirmed(vector);
        assertNotNull(f.request()); assertEquals(TxState.FAILED, f.engineState()); assertEquals(0, f.broadcasts());
        assertTrue(f.audit().contains("TX_CONFIRM")); assertTrue(f.audit().contains("TX_SIGN_REQUEST")); assertFalse(f.audit().contains("TX_BROADCAST_REQUEST"));
        long start = System.nanoTime(); var signed = client("synthetic").sign(f.request()); long elapsed = System.nanoTime() - start;
        assertEquals(vector.path("tx_raw_hex").asText(), CosmosBankSend.HEX.formatHex(signed.bytes()));
        assertEquals(vector.path("tx_hash").asText().toUpperCase(), signed.txHash());
        var again = client("synthetic").sign(request()); assertArrayEquals(signed.bytes(), again.bytes());
        var m = CosmosBankSend.material(f.request(), pub); byte[] sig = CosmosBankSend.HEX.parseHex(vector.path("signature").asText());
        start = System.nanoTime(); assertTrue(CosmosBankSend.verify(m, sig)); long verification = System.nanoTime() - start;
        assertEquals(vector.path("tx_body_hex").asText(), CosmosBankSend.HEX.formatHex(m.body()));
        assertEquals(vector.path("auth_info_hex").asText(), CosmosBankSend.HEX.formatHex(m.auth()));
        assertEquals(vector.path("sign_doc_hex").asText(), CosmosBankSend.HEX.formatHex(m.doc()));
        System.out.println("SYNTHETIC_READY_FOR_BROADCAST_NO_TRANSPORT elapsed_ms=" + elapsed / 1_000_000.0 + " verification_ms=" + verification / 1_000_000.0);
    }
    @ParameterizedTest @ValueSource(strings = {"exit", "partial", "oversized", "malformed", "wrong-id", "bad-signature", "bad-hash", "bad-intent", "bad-raw", "wrong-key", "replay", "trailing", "unknown-field", "missing-field", "duplicate-field", "wrong-version", "null-field"})
    void corruptedOrCrashingHelpersFailClosed(String mode) { assertThrows(TxSignerException.class, () -> client(mode).sign(request())); }
    @Test void timeoutIsBoundedAndChildDoesNotKeepServiceBlocked() {
        var c = new LegacyStdioSignerFixture(binary, sha, pub, List.of("-test.run=^TestProcessHarness$", "--", "hang"), Duration.ofMillis(250));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(TxSignerException.class, () -> c.sign(request())));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (ProcessHandle.current().children().anyMatch(p -> p.isAlive() && p.info().command().orElse("").equals(binary.toString()))) Thread.sleep(10);
        });
    }
    @Test void requestIdCannotBeRetriedOrUsedConcurrently() throws Exception {
        var r = request(); var c = client("synthetic"); c.sign(r);
        assertThrows(TxSignerException.class, () -> c.sign(r));
    }
    @Test void changedBinaryHashAndSymlinkFailClosed() throws Exception {
        var wrong = new LegacyStdioSignerFixture(binary, "0".repeat(64), pub, List.of(), Duration.ofSeconds(1));
        assertThrows(TxSignerException.class, () -> wrong.sign(request()));
        Path alias = dir.resolve("alias"); Files.createSymbolicLink(alias, binary);
        var linked = new LegacyStdioSignerFixture(alias, sha, pub, List.of(), Duration.ofSeconds(1));
        assertThrows(TxSignerException.class, () -> linked.sign(request()));
    }
    @Test void wrongPublicMetadataFailsBeforeSigning() {
        byte[] altered = pub.clone(); altered[0] = 3;
        var wrong = new LegacyStdioSignerFixture(binary, sha, altered, List.of("-test.run=^TestProcessHarness$", "--", "synthetic"), Duration.ofSeconds(1));
        assertThrows(TxSignerException.class, () -> wrong.sign(request()));
    }
    @Test void productionHelperAndServiceRemainUnavailable() {
        assertFalse(LegacyStdioSignerFixture.UNAVAILABLE.available()); assertFalse(TxSigner.UNAVAILABLE.available()); assertFalse(TxGate.TX_MUTATIONS_ALLOWED);
        assertThrows(TxSignerException.class, () -> LegacyStdioSignerFixture.UNAVAILABLE.sign(request()));
        var c = new LegacyStdioSignerFixture(production, CosmosBankSend.hash(readProduction()), pub, List.of(), Duration.ofSeconds(1));
        assertThrows(TxSignerException.class, () -> c.sign(request()));
    }
    private byte[] readProduction() { try { return Files.readAllBytes(production); } catch (java.io.IOException e) { throw new AssertionError(e); } }
    @Test void noGenericOrShellOrExternalEnablementSurface() throws Exception {
        String main = Files.readString(Path.of("src/test/java/byx/service/signer/LegacyStdioSignerFixture.java"));
        for (String prohibited : List.of("System.getenv", "System.getProperty", "sh -c", "bash -c", "zsh -c", "signHash(", "signBytes(", "sessionToken", "mfaToken")) assertFalse(main.contains(prohibited));
        assertTrue(main.contains("builder.environment().clear()")); assertTrue(main.contains("binary.isAbsolute()"));
        assertTrue(main.contains("public SignedTx sign(TxSignRequest request)"));
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path p : sources.filter(x -> x.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(p).contains("LegacyStdioSignerFixture"), p.toString());
                assertFalse(Files.readString(p).contains("UnixSystem"), p.toString());
            }
        }
        assertFalse(Files.exists(Path.of("src/main/java/byx/service/signer/SignerClient.java")));
        String verifier = Files.readString(Path.of("src/main/java/byx/service/signer/SignedResponseVerifier.java"));
        for (String prohibited : List.of("ProcessBuilder", "CodeIdentity", "Keychain", "UnixSystem", "System.getenv", "System.getProperty")) assertFalse(verifier.contains(prohibited));
        String go = Files.readString(Path.of("signer-helper/cmd/byx-signer-helper/main.go"));
        assertTrue(go.contains("UnavailableProvider{}")); assertFalse(go.contains("SyntheticKeyProvider"));
    }
}
