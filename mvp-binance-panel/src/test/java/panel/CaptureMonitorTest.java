package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.adapter.LocalCaptureProcessProbe;
import panel.adapter.LocalCaptureProcessProbe.*;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;
import panel.motion.*;
import panel.security.AccessDeniedException;
import panel.service.CaptureMonitorService;
import panel.researchview.CapturePanel;

class CaptureMonitorTest {
    static final Path STATE = Path.of("/test/state").toAbsolutePath().normalize();
    static final Path SCRIPT = STATE.resolve("continuous_capture.sh");
    static final Instant START = Instant.parse("2026-10-02T06:00:00Z");
    static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-10-03T07:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    static class FakeSource implements Source {
        String pid = "42";
        String campaign = "ethusdt-futures-continuous-20261003T060000Z";
        ProcessInfo process = new ProcessInfo(true, START, "/bin/zsh", List.of(SCRIPT.toString()), "ETHUSDT", "USD-M Futures");
        int reads; int scans; boolean denied;
        public String text(Path path) throws IOException {
            reads++;
            if (denied) throw new AccessDeniedException("permission");
            String value = path.getFileName().toString().equals("capture.pid") ? pid : campaign;
            if (value == null) throw new NoSuchFileException(path.toString());
            return value;
        }
        public ProcessInfo process(long pid, Path script, String campaign) { return process; }
        public Storage storage(Path root) { scans++; return new Storage(1024L, 4096L, 8192L, START, null); }
    }
    final FakeClock clock = new FakeClock();
    final FakeSource source = new FakeSource();
    LocalCaptureProcessProbe probe() { return new LocalCaptureProcessProbe(STATE, Path.of("/test/storage"), clock, source); }
    void dead() { source.process = new ProcessInfo(false, null, null, null, null, null); }

    @Test void runningUsesSupervisorStartAndRealChildIdentity() {
        var s = probe().read();
        assertEquals(State.RUNNING, s.state()); assertEquals(42L, s.pid());
        assertEquals(Duration.ofHours(25), s.continuousElapsed(clock.instant()));
        assertEquals("1d 01:00:00", CaptureSnapshot.elapsedText(s.continuousElapsed(clock.instant())));
        assertEquals("ETHUSDT", s.symbol()); assertEquals("USD-M Futures", s.market());
    }
    @Test void missingPidIsStopped() { source.pid = null; assertEquals(State.STOPPED, probe().read().state()); }
    @Test void deadPidOnFirstObservationIsStale() { dead(); assertEquals(State.STALE, probe().read().state()); }
    @Test void observedProcessExitBecomesStoppedAndClockFreezes() {
        var probe = probe(); probe.read(); dead(); var s = probe.read();
        assertEquals(State.STOPPED, s.state());
        assertEquals(s.continuousElapsed(clock.instant()), s.continuousElapsed(clock.instant().plusSeconds(100)));
    }
    @Test void invalidPidIsStale() {
        for (String invalid : List.of("", " ", "abc", "-1", "0", "999999999999999999999")) {
            source.pid = invalid; assertEquals(State.STALE, probe().read().state());
        }
    }
    @Test void wrongProcessAndReusedPidAreStale() {
        var probe = probe(); probe.read();
        source.process = new ProcessInfo(true, START.plusSeconds(1), "/bin/zsh", List.of(SCRIPT.toString()), null, null);
        assertEquals(State.STALE, probe.read().state());
        source.process = new ProcessInfo(true, START, "/bin/zsh", List.of("/other/script.sh"), null, null);
        assertEquals(State.STALE, probe().read().state());
    }
    @Test void campaignParsingIsDeterministicAndRejectsInvalidDates() {
        assertEquals(Instant.parse("2026-10-02T20:40:51Z"), LocalCaptureProcessProbe.campaignStart("ethusdt-futures-continuous-20261002T204051Z"));
        assertNull(LocalCaptureProcessProbe.campaignStart("ethusdt-futures-continuous-20260230T204051Z"));
        assertNull(LocalCaptureProcessProbe.campaignStart("bad"));
    }
    @Test void rotationResetsOnlyCampaignTimer() {
        var probe = probe(); var first = probe.read();
        clock.now = clock.now.plusSeconds(10);
        source.campaign = "ethusdt-futures-continuous-20261003T070010Z";
        var next = probe.read();
        assertEquals(Duration.ZERO, next.campaignElapsed(clock.instant()));
        assertEquals(first.continuousElapsed(clock.instant()), next.continuousElapsed(clock.instant()));
        assertEquals(0.0, next.progress(clock.instant()));
    }
    @Test void progressClampsAndTimersHandleMoreThan24Hours() {
        source.campaign = "ethusdt-futures-continuous-20261002T060000Z";
        var s = probe().read(); assertEquals(1.0, s.progress(clock.instant()));
        assertEquals("1d 01:00:00", CaptureSnapshot.elapsedText(s.campaignElapsed(clock.instant())));
        assertEquals(0.0, s.progress(START.minusSeconds(1)));
    }
    @Test void missingCampaignDuringRotationIsTransitionWithContinuousClock() {
        source.campaign = null; var s = probe().read();
        assertEquals(State.UNKNOWN, s.state()); assertTrue(s.processAlive());
        assertNull(s.campaignStartedAt()); assertNull(s.progress(clock.instant()));
        assertNotNull(s.continuousElapsed(clock.instant()));
    }
    @Test void missingStartInstantIsNotFabricated() {
        source.process = new ProcessInfo(true, null, null, null, null, null);
        var s = probe().read(); assertEquals(State.RUNNING, s.state()); assertNull(s.continuousElapsed(clock.instant()));
        assertFalse(s.warnings().isEmpty());
    }
    @Test void permissionFailureIsUnknown() {
        Source denied = new FakeSource() {
            @Override public String text(Path p) throws IOException { throw new java.nio.file.AccessDeniedException(p.toString()); }
        };
        var s = new LocalCaptureProcessProbe(STATE, Path.of("/test/storage"), clock, denied).read();
        assertEquals(State.UNKNOWN, s.state()); assertFalse(s.warnings().isEmpty());
    }
    @Test void storageIsCachedFor60SecondsAndSnapshotsAreImmutable() {
        var probe = probe(); var first = probe.read();
        clock.now = clock.now.plusSeconds(59); probe.read(); assertEquals(1, source.scans);
        clock.now = clock.now.plusSeconds(1); probe.read(); assertEquals(2, source.scans);
        assertThrows(UnsupportedOperationException.class, () -> first.warnings().add("mutation"));
    }
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @Test void nioStorageUsesMetadataAndDoesNotFollowSymlinks(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("chunk"), "data");
        Path elsewhere = Files.createDirectory(temp.resolve("FINAL_HOLDOUT"));
        Files.writeString(elsewhere.resolve("sealed"), "do not read");
        Files.createSymbolicLink(temp.resolve("linked"), elsewhere.resolve("sealed"));
        Storage s = new LocalCaptureProcessProbe.NioSource(Path.of("/missing/cli")).storage(temp);
        assertEquals(4L, s.bytes()); assertNotNull(s.free()); assertNotNull(s.updated());
    }
    @Test void pollingDoesNotBlockFxAndRefreshesAreCoalesced() throws Exception {
        FxSupport.start(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var published = new CountDownLatch(1); var calls = new AtomicInteger();
        var service = new CaptureMonitorService(() -> {
            assertFalse(Platform.isFxApplicationThread()); calls.incrementAndGet(); entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS)); return probe().read();
        }, () -> () -> {}, () -> true);
        try {
            FxSupport.fx(() -> service.start(s -> { assertTrue(Platform.isFxApplicationThread()); published.countDown(); }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FxSupport.fx(() -> { for (int i=0;i<50;i++) service.refresh(); });
            assertEquals(1, calls.get()); release.countDown(); assertTrue(published.await(5, TimeUnit.SECONDS));
            FxSupport.fx(service::stop); assertTrue(calls.get() <= 2);
        } finally { release.countDown(); FxSupport.fx(service::close); }
    }
    @Test void blockedAuthorizationRunsOffFxAndStoppedGenerationNeverReadsMetadata() throws Exception {
        FxSupport.start(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1); var reads = new AtomicInteger(); var publishes = new AtomicInteger();
        var service = new CaptureMonitorService(() -> { reads.incrementAndGet(); return probe().read(); },
                () -> () -> {
                    assertFalse(Platform.isFxApplicationThread()); entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    finally { finished.countDown(); }
                }, () -> true);
        try {
            FxSupport.fx(() -> service.start(s -> publishes.incrementAndGet()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FxSupport.fx(service::stop); // a pulse and lifecycle change complete while Service authorization is blocked
            assertEquals(0, reads.get()); release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            FxSupport.fx(() -> {}); assertEquals(0, reads.get()); assertEquals(0, publishes.get());
        } finally { release.countDown(); FxSupport.fx(service::close); }
    }
    @Test void userAndAdminWithoutAdminSessionCannotReadMonitor() throws Exception {
        FxSupport.start(); var f = AuthFixture.ready(); f.seedUser();
        var calls = new AtomicInteger();
        var service = new CaptureMonitorService(() -> { calls.incrementAndGet(); return probe().read(); }, () -> { var scope = f.auth.captureSession(); return () -> f.access.requireAdmin(scope); }, f.access::hasValidAdminSession);
        try {
            f.auth.login("alice", "temporary-pass-1".toCharArray());
            FxSupport.fx(() -> assertThrows(AccessDeniedException.class, () -> service.start(s -> {})));
            f.auth.logout(); f.auth.login("boss", "correct-horse-1".toCharArray());
            FxSupport.fx(() -> assertThrows(AccessDeniedException.class, service::refresh));
            FxSupport.fx(() -> assertThrows(AccessDeniedException.class, () -> service.start(s -> {})));
            assertEquals(0, calls.get());
            f.authorize(); var read = new CountDownLatch(1);
            FxSupport.fx(() -> service.start(s -> read.countDown())); assertTrue(read.await(5, TimeUnit.SECONDS));
        } finally { FxSupport.fx(service::close); }
    }
    @Test void motionFullReducedOffAndPresentationTimerDoNotReadSource() throws Exception {
        FxSupport.fx(() -> {
            var motion = new MotionService(); var card = new CapturePanel(motion, clock);
            var stage = new Stage(); stage.setScene(new Scene(card, 1200, 850)); stage.show();
            try {
                card.show(probe().read()); card.start(() -> {}); motion.refreshLoops(); assertEquals(1, motion.runningLoops());
                int reads = source.reads; clock.now = clock.now.plusSeconds(2); card.updateTimers(); assertEquals(reads, source.reads);
                motion.preference.set(MotionPreference.REDUCED); assertEquals(0, motion.runningLoops());
                motion.preference.set(MotionPreference.OFF); assertEquals(0, motion.runningLoops());
                motion.preference.set(MotionPreference.FULL); assertEquals(1, motion.runningLoops());
                card.stop(); assertEquals(0, motion.runningLoops());
            } finally { card.stop(); stage.close(); }
        });
    }
}
