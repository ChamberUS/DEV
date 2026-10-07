package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Testes DINÂMICOS de negação: cada caso conecta de verdade ao socket do serviço e confere que a ação indevida é recusada
 * (não apenas que o código "parece" certo). Nada aqui usa segredo real: o segredo de pareamento é gerado no próprio teste.
 */
class ServiceSecurityTest {
    private Path home;
    private ServiceInstance service;
    private final List<String> logs = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "bx");
        Log.redirect(logs::add);
        service = ServiceInstance.start(home, new ServiceInstance.Limits(3, 600, 600, 2_000));
    }

    @AfterEach
    void down() throws Exception {
        service.close();
        Log.redirect(null);
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private TestClient paired() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        JsonNode ready = c.handshake(TestClient.readToken(home), true);
        assertEquals("ready", ready.path("type").asText());
        return c;
    }

    private void assertStillHealthy() throws Exception {
        try (TestClient c = paired()) {
            assertTrue(c.call("health").path("ok").asBoolean(), "the service keeps serving valid clients");
        }
    }

    @Test
    void pairedClientGetsOnlyNonSensitiveStatus() throws Exception {
        try (TestClient c = paired()) {
            JsonNode health = c.call("health");
            JsonNode version = c.call("version");
            JsonNode caps = c.call("capabilities");
            assertTrue(health.path("ok").asBoolean() && "ok".equals(health.path("result").path("status").asText()));
            assertEquals(1, version.path("result").path("protocolMax").asInt());
            // as 3 operações PÚBLICAS e somente leitura da chain (byx.*) são parte do contrato; nenhuma é privada, de escrita ou genérica
            assertEquals(List.of("byx.bank.balance", "byx.certificados.getCertificate", "byx.certificados.listByMerchant", "byx.denomMetadata", "byx.feesplit.params", "byx.lojas.getMerchant", "byx.lojas.listMerchants", "byx.moduleHealth", "byx.payments.getPayment", "byx.payments.listByStore", "byx.payments.params", "byx.status", "byx.supply", "capabilities", "health", "version"), TestClient.JSON.convertValue(caps.path("result").path("operations"), List.class));
            JsonNode features = caps.path("result").path("features");
            for (String f : List.of("marketData", "notifications", "accountData", "adminOperations")) {
                assertFalse(features.path(f).asBoolean(true), f + " is blocked in the foundation");
            }
            assertEquals("not_implemented", caps.path("result").path("identity").path("userAuthentication").asText());
            String all = health + version.toString() + caps;
            for (String leak : List.of(home.toString(), System.getProperty("user.home"), System.getProperty("user.name"), System.getProperty("java.version"),
                    System.getProperty("os.name"), Pairing.encode(TestClient.readToken(home)))) {
                assertFalse(all.contains(leak), "responses never carry local paths, user, runtime or secrets: " + leak.length());
            }
        }
    }

    @Test
    void clientWithoutTheSecretNeverGetsAnOperation() throws Exception {
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            JsonNode r = c.handshake(new byte[32], false); // não confere o servidor: só testa o lado do servidor
            assertEquals("error", r.path("type").asText());
            assertEquals("auth_failed", r.path("code").asText());
            assertTrue(c.closedWithin(1_000), "the connection is closed after a failed proof");
        }
        // uma prova de CLIENTE não pode ser reaproveitada como prova de SERVIDOR (rótulos por direção) nem vice-versa
        byte[] s = TestClient.readToken(home);
        assertNotEquals(Pairing.serverProof(s, "AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB"), Pairing.clientProof(s, "AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB"));
        assertStillHealthy();
    }

    /** Prova capturada numa conexão não vale em outra: o desafio do servidor é novo a cada conexão e a prova cobre os dois nonces. */
    @Test
    void aValidProofCapturedOnOneConnectionIsUselessOnAnother() throws Exception {
        byte[] secret = TestClient.readToken(home);
        String cn = Pairing.encode(new byte[16]);
        String capturedProof;
        String firstServerNonce;
        try (TestClient a = new TestClient(service.runtimeDir().socket())) {
            a.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
            JsonNode ch = a.readJson();
            firstServerNonce = ch.path("serverNonce").asText();
            capturedProof = Pairing.clientProof(secret, cn, firstServerNonce);
            a.sendJson("{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + capturedProof + "\"}");
            assertEquals("ready", a.readJson().path("type").asText(), "the legitimate conversation worked");
            try (TestClient b = new TestClient(service.runtimeDir().socket())) { // uma conexão autenticada NÃO autentica outra
                b.sendJson("{\"v\":1,\"id\":\"x\",\"op\":\"health\"}");
                assertEquals("bad_request", b.readJson().path("code").asText());
            }
            try (TestClient b = new TestClient(service.runtimeDir().socket())) { // replay: mesmo nonce de cliente, prova antiga
                b.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
                JsonNode ch2 = b.readJson();
                assertNotEquals(firstServerNonce, ch2.path("serverNonce").asText(), "a fresh server nonce per connection");
                b.sendJson("{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + capturedProof + "\"}");
                assertEquals("auth_failed", b.readJson().path("code").asText(), "the replayed proof is rejected");
            }
            try (TestClient b = new TestClient(service.runtimeDir().socket())) { // prova calculada com outro nonce de cliente não vale
                b.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
                String sn = b.readJson().path("serverNonce").asText();
                b.sendJson("{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + Pairing.clientProof(secret, Pairing.encode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}), sn) + "\"}");
                assertEquals("auth_failed", b.readJson().path("code").asText(), "the proof is bound to this connection's nonces");
            }
            // a conexão A segue autenticada e as outras não herdaram nada
            assertTrue(a.call("health").path("ok").asBoolean());
        }
        // nonces do servidor não se repetem entre conexões
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 6; i++) {
            try (TestClient c = new TestClient(service.runtimeDir().socket())) {
                c.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
                assertTrue(seen.add(c.readJson().path("serverNonce").asText()));
            }
        }
        assertStillHealthy();
    }

    @Test
    void requestsBeforeAuthenticationAreRefused() throws Exception {
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            c.sendJson("{\"v\":1,\"id\":\"x\",\"op\":\"health\"}");
            JsonNode r = c.readJson();
            assertEquals("bad_request", r.path("code").asText());
            assertFalse(r.has("result"));
        }
        assertStillHealthy();
    }

    @Test
    void forgedAuthorityClaimsAndUnknownOperationsAreDenied() throws Exception {
        try (TestClient c = paired()) {
            for (String op : List.of("readFile", "exec", "shell", "sql", "signMessage", "getAccount", "listUsers", "setRole", "proxy", "../health", "HEALTH", "")) {
                JsonNode r = c.call(op);
                assertFalse(r.path("ok").asBoolean(true), op + " must be denied");
                assertTrue("unsupported_operation".equals(r.path("error").path("code").asText()) || "bad_request".equals(r.path("code").asText()), op + " -> " + r);
                if ("bad_request".equals(r.path("code").asText())) {
                    break;
                }
            }
        }
        // role, mfa e userId enviados pela UI não são campos do contrato: o pedido inteiro é recusado
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"t2\",\"op\":\"health\",\"role\":\"admin\",\"mfa\":true,\"userId\":1}");
            JsonNode r = c.readJson();
            assertEquals("bad_request", r.path("code").asText(), "authority claims are rejected, never honored");
            assertFalse(r.has("result"));
        }
        assertTrue(logs.stream().anyMatch(l -> l.contains("unsupported_operation")), "denials are logged by code");
        assertStillHealthy();
    }

    @Test
    void malformedAndHostilePayloadsAreRejectedWithoutHarm() throws Exception {
        List<String> bad = List.of("not json", "{\"v\":1,\"id\":\"a\",\"op\":\"health\"} trailing", "{\"v\":1,\"id\":\"a\",\"op\":\"health\",\"op\":\"version\"}",
                "{\"v\":\"1\",\"id\":\"a\",\"op\":\"health\"}", "{\"v\":1,\"id\":\"a b\",\"op\":\"health\"}", "{\"v\":1,\"id\":\"" + "x".repeat(65) + "\",\"op\":\"health\"}",
                "[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]", "{\"v\":1,\"id\":\"a\",\"op\":null}", "{\"v\":2,\"id\":\"a\",\"op\":\"health\"}", "{}", "null", "\"op\"");
        for (String payload : bad) {
            try (TestClient c = paired()) {
                c.sendJson(payload);
                JsonNode r = c.readJson();
                assertEquals("error", r.path("type").asText(), payload);
                assertFalse(r.has("result"), payload);
            }
        }
        // hello e auth malformados
        for (String payload : List.of("{\"v\":1,\"type\":\"hello\"}", "{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"short\"}", "{\"v\":9,\"type\":\"hello\",\"clientNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\"}")) {
            try (TestClient c = new TestClient(service.runtimeDir().socket())) {
                c.sendJson(payload);
                assertEquals("error", c.readJson().path("type").asText(), payload);
            }
        }
        assertStillHealthy();
    }

    @Test
    void oversizedAndBrokenFramesCloseTheConnectionBeforeAllocating() throws Exception {
        for (int len : new int[] {Protocol.MAX_FRAME + 1, 10 * 1024 * 1024, Integer.MAX_VALUE, -1, 0}) {
            try (TestClient c = paired()) {
                c.out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len}); // só o cabeçalho
                c.out.flush();
                assertTrue(c.closedWithin(1_500), "frame length " + len + " must close the connection");
            }
        }
        try (TestClient c = paired()) { // corpo truncado
            c.out.write(new byte[] {0, 0, 0, 50, '{', '"'});
            c.out.flush();
            c.channel.shutdownOutput();
            assertTrue(c.closedWithin(1_500));
        }
        try (TestClient c = paired()) { // quadro exatamente no limite, mas com lixo: recusado como JSON inválido, sem travar
            byte[] filler = new byte[Protocol.MAX_FRAME];
            java.util.Arrays.fill(filler, (byte) 'a');
            Frames.write(c.out, filler);
            assertEquals("bad_request", c.readJson().path("code").asText());
        }
        assertStillHealthy();
    }

    @Test
    void silentOrSlowClientsAreCutOffByTheTimeouts() throws Exception {
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            assertTrue(c.closedWithin(2_000), "no hello within the handshake timeout: closed");
        }
        try (TestClient c = paired()) {
            assertTrue(c.closedWithin(4_000), "an idle paired connection is closed after the idle timeout");
        }
        try (TestClient c = paired()) { // pedido enviado pela metade
            c.out.write(new byte[] {0, 0, 0, 40, '{'});
            c.out.flush();
            assertTrue(c.closedWithin(3_000), "a half-sent request is cut off by the read timeout");
        }
        assertStillHealthy();
    }

    @Test
    void connectionLimitIsEnforcedAndRecovers() throws Exception {
        List<TestClient> open = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                open.add(paired());
            }
            try (TestClient extra = new TestClient(service.runtimeDir().socket())) {
                assertTrue(extra.closedWithin(1_500), "the connection above the limit is refused");
            }
            assertEquals(3, service.activeConnections());
        } finally {
            open.forEach(TestClient::close);
        }
        long end = System.currentTimeMillis() + 2_000;
        while (service.activeConnections() > 0 && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertStillHealthy();
    }

    @Test
    void repeatedFailedProofsAreThrottledThenRecover() throws Exception {
        for (int i = 0; i < ServiceInstance.FAILURES_BEFORE_THROTTLE; i++) {
            try (TestClient c = new TestClient(service.runtimeDir().socket())) {
                c.handshake(new byte[32], false);
            }
        }
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            assertTrue(c.closedWithin(1_500), "while throttled, even a valid client is turned away at the door");
        }
        Thread.sleep(ServiceInstance.THROTTLE_PENALTY_MS + 300);
        assertStillHealthy();
    }

    @Test
    void runtimeFilesArePrivateAndUnsafeSetupsFailClosed() throws Exception {
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("run"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(service.runtimeDir().token())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(service.runtimeDir().socket(), LinkOption.NOFOLLOW_LINKS)));
        assertEquals(32, TestClient.readToken(home).length);
        // diretório acessível ao grupo: o serviço se recusa a subir (e não "conserta" em silêncio)
        Path loose = Files.createTempDirectory(Path.of("/tmp"), "bx");
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(RuntimeDir.InsecureException.class, () -> ServiceInstance.start(loose));
        assertFalse(Files.exists(loose.resolve("run").resolve(RuntimeDir.TOKEN)), "no secret is written into an unsafe directory");
        // home que é um symlink
        Path real = Files.createTempDirectory(Path.of("/tmp"), "bx");
        Path link = Path.of(real + "-link");
        Files.createSymbolicLink(link, real);
        assertThrows(RuntimeDir.InsecureException.class, () -> ServiceInstance.start(link));
        // caminho do socket ocupado por um arquivo comum
        Path occupied = Files.createTempDirectory(Path.of("/tmp"), "bx");
        Files.createDirectory(occupied.resolve("run"), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createFile(occupied.resolve("run").resolve(RuntimeDir.SOCKET));
        assertThrows(RuntimeDir.InsecureException.class, () -> ServiceInstance.start(occupied));
        assertTrue(Files.exists(occupied.resolve("run").resolve(RuntimeDir.SOCKET)), "a foreign file is never deleted");
        // home relativo
        assertThrows(RuntimeDir.InsecureException.class, () -> ServiceInstance.start(Path.of("relative-home")));
        for (Path p : List.of(loose, real, link, occupied)) {
            try (var walk = Files.walk(p, 3, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            } catch (IOException ignored) {
                // limpeza
            }
            Files.deleteIfExists(p);
        }
    }

    @Test
    void restartInvalidatesTheOldPairingAndConnections() throws Exception {
        byte[] oldSecret = TestClient.readToken(home);
        String oldInstance = service.instanceId();
        TestClient stale = paired();
        service.close();
        assertTrue(stale.closedWithin(1_500), "the old connection dies with the old instance");
        stale.close();
        assertFalse(Files.exists(home.resolve("run").resolve(RuntimeDir.TOKEN)), "no pairing secret survives a stopped service");
        service = ServiceInstance.start(home, new ServiceInstance.Limits(3, 600, 600, 2_000));
        byte[] newSecret = TestClient.readToken(home);
        assertFalse(java.util.Arrays.equals(oldSecret, newSecret), "a new secret every start");
        assertNotEquals(oldInstance, service.instanceId());
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            assertEquals("auth_failed", c.handshake(oldSecret, false).path("code").asText(), "the old secret no longer authenticates");
        }
        assertStillHealthy();
    }

    @Test
    void logsNeverContainSecretsNoncesOrProofs() throws Exception {
        byte[] secret = TestClient.readToken(home);
        String token = Pairing.encode(secret);
        try (TestClient c = paired()) {
            c.call("health");
            c.call("readFile");
        }
        try (TestClient c = new TestClient(service.runtimeDir().socket())) {
            c.handshake(new byte[32], false);
        }
        try (TestClient c = paired()) {
            c.sendJson("{\"v\":1,\"id\":\"CANARY-SECRET-123\",\"op\":\"health\",\"canary\":\"CANARY-SECRET-123\"}");
            c.readJson();
        }
        String all = String.join("\n", logs);
        assertFalse(all.contains(token) || all.contains("CANARY") || all.contains(home.toString()), "logs carry codes only");
        assertTrue(all.contains("auth_failed") && all.contains("unsupported_operation"));
    }
}
