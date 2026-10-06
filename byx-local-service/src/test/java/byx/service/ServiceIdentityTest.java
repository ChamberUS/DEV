package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.identity.IdentityPolicy;
import byx.service.identity.PeerVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Identidade do app no serviço: modo estrito fecha antes de ler; token + alegações do cliente não valem; desenvolvimento nunca habilita privado. */
class ServiceIdentityTest {
    private Path home;
    private ServiceInstance service;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<PeerVerifier.Verdict> verdict = new AtomicReference<>(PeerVerifier.Verdict.no("peer_requirement_failed"));
    private final AtomicInteger verifications = new AtomicInteger();

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "id");
        Log.redirect(logs::add);
    }

    @AfterEach
    void down() throws Exception {
        if (service != null) {
            service.close();
        }
        Log.redirect(null);
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void startStrict() throws Exception {
        PeerVerifier stub = ch -> {
            verifications.incrementAndGet();
            PeerVerifier.Verdict v = verdict.get();
            if (v == null) {
                throw new IllegalStateException("verifier blew up");
            }
            return v;
        };
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 800, 800, 2_000, 800), null, IdentityPolicy.strict(stub));
    }

    private TestClient raw() throws Exception {
        return new TestClient(service.runtimeDir().socket());
    }

    @Test
    void strictModeClosesAnUnverifiedPeerBeforeReadingAnythingEvenWithTheRightPairingToken() throws Exception {
        startStrict();
        byte[] token = TestClient.readToken(home); // o atacante do MESMO usuário leu o pairing.token
        try (TestClient c = raw()) {
            assertThrows(IOException.class, () -> c.handshake(token, true), "no challenge: the connection is closed at accept");
        }
        TestClient.waitFor(() -> logs.stream().anyMatch(l -> l.contains("peer_rejected peer_requirement_failed")), 2_000);
        assertTrue(logs.stream().noneMatch(l -> l.contains(Pairing.encode(token))), "the secret never reaches the log");
    }

    @Test
    void theVerifierIsAskedWithoutTheClientSendingAnything() throws Exception {
        startStrict();
        try (TestClient c = raw()) {
            TestClient.waitFor(() -> verifications.get() >= 1, 2_000);
            assertTrue(c.closedWithin(1_500), "rejected on connect: the service did not wait for, or read, a hello");
        }
    }

    @Test
    void falseBundleIdAndPidInThePayloadDoNotHelpAndAreNotTrusted() throws Exception {
        startStrict();
        byte[] token = TestClient.readToken(home);
        // 1) peer não verificado: alegações no hello não mudam nada (a conexão já foi fechada)
        try (TestClient c = raw()) {
            try {
                c.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"bundleId\":\"" + byx.service.identity.AppIdentity.APP_ID + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
            } catch (IOException closedAlready) {
                // esperado: o serviço já fechou
            }
            assertTrue(c.closedWithin(1_500));
        }
        // 2) peer verificado: o DTO estrito recusa campos de identidade desconhecidos (nunca são lidos como prova)
        verdict.set(PeerVerifier.Verdict.ok());
        try (TestClient c = raw()) {
            c.sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"bundleId\":\"" + byx.service.identity.AppIdentity.APP_ID + "\",\"pid\":1,\"teamId\":\"W5Z65G9UP2\"}");
            assertEquals("bad_request", c.readJson().path("code").asText());
        }
        // e o caminho legítimo continua funcionando (o mesmo serviço, o mesmo token, agora um peer verificado)
        try (TestClient c = raw()) {
            assertEquals("ready", c.handshake(token, true).path("type").asText());
            JsonNode caps = c.call("capabilities").path("result");
            assertEquals("packaged_verified", caps.path("identity").path("appIdentity").asText());
            assertEquals("verified_app_code_identity_and_pairing_secret", caps.path("identity").path("peer").asText());
        }
    }

    @Test
    void aVerifiedPeerWorksAndRestartingTheServiceKeepsTheContract() throws Exception {
        verdict.set(PeerVerifier.Verdict.ok());
        startStrict();
        try (TestClient c = raw()) {
            assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
            assertTrue(c.call("health").path("ok").asBoolean());
        }
        service.close();
        startStrict(); // reinício: segredo novo, mesma política, mesmo resultado
        try (TestClient c = raw()) {
            assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        }
        verdict.set(PeerVerifier.Verdict.no("peer_bundle_modified"));
        try (TestClient c = raw()) {
            assertThrows(IOException.class, () -> c.handshake(TestClient.readToken(home), true));
        }
    }

    @Test
    void aVerifierThatFailsClosesTheConnectionAndTheServiceSurvives() throws Exception {
        startStrict();
        verdict.set(null); // o verificador lança
        try (TestClient c = raw()) {
            assertThrows(IOException.class, () -> c.handshake(TestClient.readToken(home), true));
        }
        verdict.set(PeerVerifier.Verdict.ok());
        try (TestClient c = raw()) {
            assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText(), "still serving verified peers");
        }
    }

    @Test
    void repeatedRejectedConnectionsAreThrottledLikeAnyOtherFailure() throws Exception {
        startStrict();
        for (int i = 0; i < 6; i++) {
            try (TestClient c = raw()) {
                c.closedWithin(300);
            }
        }
        TestClient.waitFor(() -> logs.stream().filter(l -> l.contains("peer_rejected")).count() >= 5, 2_000);
        long rejected = logs.stream().filter(l -> l.contains("peer_rejected")).count();
        assertTrue(rejected >= 5, "rejections are counted: " + rejected);
        assertTrue(logs.stream().anyMatch(l -> l.contains("rejected throttled")), "after 5 failures new connections are refused for a while");
    }

    @Test
    void developmentModeDoesNotVerifyButNeverEnablesPrivateCapabilitiesAndSaysSo() throws Exception {
        service = ServiceInstance.start(home, new ServiceInstance.Limits(4, 800, 800, 2_000, 800), null, IdentityPolicy.development());
        try (TestClient c = raw()) {
            assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
            JsonNode caps = c.call("capabilities").path("result");
            assertEquals("development_unverified", caps.path("identity").path("appIdentity").asText());
            for (String f : List.of("accountData", "notifications", "adminOperations", "secretIntegrations")) {
                assertFalse(caps.path("features").path(f).asBoolean(true), f);
            }
            assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        }
    }

    @Test
    void strictModeAlsoKeepsThePrivateGateClosed() throws Exception {
        verdict.set(PeerVerifier.Verdict.ok());
        startStrict();
        try (TestClient c = raw()) {
            c.handshake(TestClient.readToken(home), true);
            JsonNode caps = c.call("capabilities").path("result");
            assertEquals("packaged_verified", caps.path("identity").path("appIdentity").asText());
            for (String f : List.of("accountData", "notifications", "adminOperations", "secretIntegrations")) {
                assertFalse(caps.path("features").path(f).asBoolean(true), "even a verified app does not open " + f + " in this phase");
            }
            assertFalse(caps.path("privateGate").path("allowed").asBoolean(true));
        }
    }
}
