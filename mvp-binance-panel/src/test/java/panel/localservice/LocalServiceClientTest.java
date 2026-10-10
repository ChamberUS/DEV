package panel.localservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panel.localservice.LocalServiceStatus.State;

/**
 * O cliente do painel diante de serviços hostis ou ausentes (testes dinâmicos contra um servidor de mentira real no socket Unix).
 * Nada de segredo real: o segredo de pareamento é gerado em cada teste.
 */
class LocalServiceClientTest {
    private Path home;
    private final List<FakeService> fakes = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        home = IpcTestFiles.home("pc");
    }

    @AfterEach
    void down() throws Exception {
        fakes.forEach(FakeService::close);
        if (home == null) return;
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private FakeService fake(FakeService.Mode mode) throws Exception {
        FakeService f = new FakeService(home, mode);
        fakes.add(f);
        return f;
    }

    private LocalServiceStatus probe() {
        return new LocalServiceClient(home).once(false);
    }

    @Test
    void pairedServiceIsReadAndPrivateCapabilitiesAreNeverTrusted() throws Exception {
        fake(FakeService.Mode.GOOD);
        LocalServiceStatus s = probe();
        assertEquals(State.CONNECTED, s.state());
        assertEquals(1, s.protocol());
        assertEquals("0.1.0", s.version());
        for (String feature : List.of("accountData", "adminOperations", "notifications", "marketData")) {
            assertFalse(s.feature(feature), "the foundation client honors no capability beyond status, whatever the service claims: " + feature);
        }
    }

    @Test
    void packagedClientRefusesAServiceWhoseCodeIdentityIsNotVerifiedEvenWithTheRightSecret() throws Exception {
        FakeService f = fake(FakeService.Mode.GOOD); // um processo do mesmo usuário que LEU o pairing.token e fala o protocolo perfeitamente
        var reject = panel.identity.IdentityPolicy.strict(ch -> panel.identity.PeerVerifier.Verdict.no("peer_requirement_failed"));
        LocalServiceStatus s = new LocalServiceClient(home, reject).once(false);
        assertEquals(State.AUTH_FAILED, s.state());
        assertEquals("service_identity_not_verified", s.code());
        Thread.sleep(300);
        assertEquals(0, f.authFramesSeen.get(), "no hello, proof or request was ever sent to the unverified service");
        var accept = panel.identity.IdentityPolicy.strict(ch -> panel.identity.PeerVerifier.Verdict.ok());
        assertEquals(State.CONNECTED, new LocalServiceClient(home, accept).once(false).state(), "a verified service works as before");
    }

    @Test
    void anImpostorThatDoesNotKnowTheSecretIsRejectedBeforeAnyProofIsSent() throws Exception {
        FakeService f = fake(FakeService.Mode.IMPOSTOR);
        LocalServiceStatus s = probe();
        assertEquals(State.AUTH_FAILED, s.state());
        assertEquals("server_not_verified", s.code());
        Thread.sleep(300);
        assertEquals(0, f.authFramesSeen.get(), "the client never sent its proof or any request to the impostor");
    }

    @Test
    void aServerProofReplayedFromAnEarlierConversationIsRejected() throws Exception {
        FakeService f = fake(FakeService.Mode.REPLAYED_SERVER_PROOF);
        LocalServiceStatus s = probe();
        assertEquals(State.AUTH_FAILED, s.state());
        assertEquals("server_not_verified", s.code(), "a valid-looking proof bound to another client nonce does not authenticate the server");
        Thread.sleep(300);
        assertEquals(0, f.authFramesSeen.get(), "and the client sent nothing to it");
    }

    @Test
    void aServiceThatRejectsOurProofIsAuthFailedNotConnected() throws Exception {
        fake(FakeService.Mode.REJECTS_CLIENT);
        LocalServiceStatus s = probe();
        assertEquals(State.AUTH_FAILED, s.state());
        assertEquals("pairing_rejected", s.code());
    }

    @Test
    void unsafeOrInvalidPairingFilesAreRefusedWithoutConnecting() throws Exception {
        FakeService f = fake(FakeService.Mode.GOOD);
        Path token = home.resolve("run").resolve("pairing.token");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("pairing_not_private", probe().code(), "group/other readable token");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(home.resolve("run"), PosixFilePermissions.fromString("rwxr-x---"));
        assertEquals(State.INSECURE_PAIRING, probe().state(), "group-accessible run directory");
        Files.setPosixFilePermissions(home.resolve("run"), PosixFilePermissions.fromString("rwx------"));
        f.writeToken("not-base64-!!");
        assertEquals("token_malformed", probe().code());
        f.writeToken("QUJD"); // 3 bytes, não 32
        assertEquals("token_malformed", probe().code());
        Files.delete(token);
        Path real = home.resolve("real.token");
        Files.writeString(real, "x");
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(token, real);
        assertEquals("pairing_path_not_real", probe().code(), "a symlinked token is refused");
        assertEquals(0, f.authFramesSeen.get());
    }

    @Test
    void absentServiceIsUnavailableAndBounded() throws Exception {
        long t0 = System.nanoTime();
        LocalServiceStatus none = new LocalServiceClient(home).probe(false);
        assertEquals(State.UNAVAILABLE, none.state());
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        assertEquals(windows ? "native_service_unsupported" : "not_started", none.code());
        if (windows) {
            LocalServiceStatus unavailable = new LocalServiceClient(home).probe(true);
            assertEquals(State.UNAVAILABLE, unavailable.state());
            assertEquals("native_service_unsupported", unavailable.code());
            assertTrue(unavailable.everConnected(), "previous connectivity never becomes current connectivity");
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_000, "unsupported IPC is bounded");
            return;
        }
        // socket velho sem ninguém escutando (serviço morreu)
        FakeService f = fake(FakeService.Mode.GOOD);
        f.close();
        Files.createFile(home.resolve("run").resolve("stale"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        LocalServiceStatus stale = new LocalServiceClient(home).probe(true);
        assertEquals(State.UNAVAILABLE, stale.state());
        assertTrue(stale.everConnected(), "an outage after having been up is a drop, not 'never started'");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_000, "bounded retries: the panel is never held up");
    }

    @Test
    void silentServiceTimesOutAndIsNotRetriedForever() throws Exception {
        fake(FakeService.Mode.STALL);
        long t0 = System.nanoTime();
        LocalServiceStatus s = new LocalServiceClient(home).probe(false);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(State.UNAVAILABLE, s.state());
        assertEquals("timeout", s.code());
        assertTrue(ms < LocalServiceClient.TOTAL_TIMEOUT_MS + 1_500, "one bounded attempt, no retry on a stall: " + ms + " ms");
    }

    @Test
    void oversizedGarbageAndOutOfContractResponsesAreRejected() throws Exception {
        FakeService f = fake(FakeService.Mode.OVERSIZE);
        assertEquals("frame_size", probe().code(), "a 10 MiB declared frame is refused before reading or allocating it");
        f.mode = FakeService.Mode.GARBAGE;
        assertEquals("contract_violation", probe().code());
        f.mode = FakeService.Mode.INCOMPATIBLE_PROTOCOL;
        assertEquals("protocol_out_of_range", probe().code());
        f.mode = FakeService.Mode.HOSTILE_STRINGS;
        LocalServiceStatus hostile = probe();
        assertEquals(State.INCOMPATIBLE, hostile.state());
        assertFalse(hostile.summary().contains("/Users") || hostile.code().contains("/"), "service-supplied text never reaches the UI: " + hostile.summary());
    }

    @Test
    void restartIsReadFreshAndNeverReusesTheOldPairing() throws Exception {
        FakeService first = fake(FakeService.Mode.GOOD);
        assertEquals(State.CONNECTED, probe().state());
        first.close();
        assertEquals(State.UNAVAILABLE, new LocalServiceClient(home).once(true).state(), "stopped service: unavailable, not stale-connected");
        FakeService second = fake(FakeService.Mode.GOOD); // novo segredo no mesmo arquivo
        assertFalse(java.util.Arrays.equals(first.tokenSecret, second.tokenSecret));
        LocalServiceStatus again = probe();
        assertEquals(State.CONNECTED, again.state(), "the client re-reads the new pairing secret on every probe");
    }

    @Test
    void statusTextNeverCarriesPathsOrSecrets() throws Exception {
        FakeService f = fake(FakeService.Mode.GOOD);
        String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(f.tokenSecret);
        for (FakeService.Mode m : FakeService.Mode.values()) {
            if (m == FakeService.Mode.STALL) {
                continue;
            }
            f.mode = m;
            LocalServiceStatus s = probe();
            String all = s.summary() + s.code() + s.instance() + s.version();
            assertFalse(all.contains(secret) || all.contains(home.toString()) || all.contains(System.getProperty("user.home")), m + " leaked into the status");
        }
    }

    @Test
    void monitorDiscardsAResultThatArrivesAfterStopAndStopsEverything() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<LocalServiceStatus> delivered = new AtomicReference<>();
        LocalServiceMonitor m = new LocalServiceMonitor(ever -> {
            inFlight.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LocalServiceStatus(State.CONNECTED, "ok", true, "0.1.0", 1, 1, "i", java.util.Map.of(), java.time.Instant.now());
        }, delivered::set);
        m.start();
        assertTrue(inFlight.await(3, TimeUnit.SECONDS));
        m.stop(); // logout / troca de usuário com a leitura em voo
        release.countDown();
        Thread.sleep(300);
        assertNull(delivered.get(), "a late result from a previous generation is never delivered");
        assertEquals(State.UNKNOWN, m.snapshot().state(), "after stop there is no remembered state");
        assertFalse(m.running(), "zero scheduled work after stop");
        m.start();
        assertTrue(m.running());
        m.close();
        assertFalse(m.running());
    }
}
