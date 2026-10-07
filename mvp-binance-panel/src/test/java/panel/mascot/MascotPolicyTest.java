package panel.mascot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** V2.1P: política de quando/onde o mascote aparece (lógica pura), manifesto, e guards de código (sem MediaPlayer, sem espera na FX, nada no login/startup). */
class MascotPolicyTest {
    private static final class FakeScheduler implements MascotActivity.Scheduler {
        final List<Object[]> timers = new ArrayList<>(); // {delay, runnable, cancelled[]}
        long now;

        @Override
        public Handle schedule(long delayMs, Runnable r) {
            boolean[] cancelled = {false};
            timers.add(new Object[] {now + delayMs, r, cancelled});
            return () -> cancelled[0] = true;
        }

        void advance(long ms) {
            now += ms;
            for (Object[] t : new ArrayList<>(timers)) {
                if ((long) t[0] <= now && !((boolean[]) t[2])[0]) {
                    ((boolean[]) t[2])[0] = true;
                    ((Runnable) t[1]).run();
                }
            }
        }
    }

    private final FakeScheduler sched = new FakeScheduler();
    private final List<Optional<MascotState>> seen = new ArrayList<>();
    private final MascotActivity activity = new MascotActivity(sched, seen::add);

    @Test
    void fastOperationsNeverShowTheMascotAndSlowOnesDoAfterTheThreshold() {
        var t = activity.begin(MascotActivity.Kind.QUERY);
        sched.advance(MascotActivity.SHOW_AFTER_MS - 1);
        activity.end(t);
        sched.advance(10_000);
        assertTrue(seen.isEmpty(), "an operation under ~250 ms shows nothing and later timers do not fire");
        var t2 = activity.begin(MascotActivity.Kind.QUERY);
        sched.advance(MascotActivity.SHOW_AFTER_MS);
        assertEquals(List.of(Optional.of(MascotState.THINKING)), seen);
        activity.end(t2);
        assertEquals(Optional.<MascotState>empty(), seen.get(seen.size() - 1), "the result is never held back: the mascot is released immediately");
    }

    @Test
    void longOperationsEscalateAndSyncOutranksTask() {
        var q = activity.begin(MascotActivity.Kind.QUERY);
        sched.advance(MascotActivity.SHOW_AFTER_MS);
        sched.advance(MascotActivity.ESCALATE_AFTER_MS);
        assertEquals(Optional.of(MascotState.PROCESSING), seen.get(seen.size() - 1));
        var s = activity.begin(MascotActivity.Kind.SYNC);
        assertEquals(Optional.of(MascotState.SYNCING), seen.get(seen.size() - 1));
        activity.end(s);
        assertEquals(Optional.of(MascotState.PROCESSING), seen.get(seen.size() - 1));
        activity.end(q);
        activity.end(q); // idempotente
        assertEquals(Optional.<MascotState>empty(), seen.get(seen.size() - 1));
        assertEquals(Optional.<MascotState>empty(), activity.shown());
    }

    @Test
    void usageMatrixFollowsTheSpecAndNeverUsesTheMascotForErrorsOrSecurity() {
        assertEquals(new MascotUsage.Plan(MascotState.IDLE, null, false), MascotUsage.forChain("NOT_CONFIGURED").orElseThrow());
        assertEquals(MascotState.THINKING, MascotUsage.forChain("CONNECTING").orElseThrow().steady());
        assertEquals(MascotState.SYNCING, MascotUsage.forChain("SYNCING").orElseThrow().steady());
        assertFalse(MascotUsage.forChain("LIVE").orElseThrow().animate(), "LIVE has no constant animation");
        var off = MascotUsage.forChain("OFFLINE").orElseThrow();
        assertEquals(MascotState.ATTENTION, off.oneShot());
        assertFalse(off.animate(), "OFFLINE: ATTENTION once, then static");
        for (String bad : new String[] {"NETWORK_MISMATCH", "ERROR", "SOMETHING_NEW", null}) {
            assertTrue(MascotUsage.forChain(bad).isEmpty(), "no playful mascot for " + bad);
        }
        assertEquals(MascotState.PROCESSING, MascotUsage.forResearch(MascotUsage.Research.LOCAL_PROCESSING).orElseThrow().steady());
        assertEquals(MascotState.THINKING, MascotUsage.forResearch(MascotUsage.Research.VALIDATION_CHECK).orElseThrow().steady());
        assertTrue(MascotUsage.forResearch(MascotUsage.Research.SCIENTIFIC_ERROR).isEmpty(), "a scientific blocker is never softened by the mascot");
        assertFalse(MascotUsage.allowedForSecurity());
    }

    @Test
    void theStateSetIsClosedAndLoopsAreDistinguishedFromOneShots() {
        assertEquals(7, MascotState.values().length);
        for (MascotState s : List.of(MascotState.IDLE, MascotState.THINKING, MascotState.PROCESSING, MascotState.SYNCING)) {
            assertTrue(s.loops());
        }
        for (MascotState s : List.of(MascotState.ATTENTION, MascotState.NOTIFICATION, MascotState.TRANSITION)) {
            assertTrue(s.oneShot());
        }
    }

    @Test
    void theShippedManifestIsCompleteConsistentAndPointsAtExistingAssets() throws Exception {
        MascotManifest m = MascotManifest.load(MascotManifest.RESOURCE);
        assertEquals(64, m.sourceSha256().length());
        for (MascotState s : MascotState.values()) {
            var e = m.entry(s).orElseThrow();
            assertEquals(s.loops(), e.loop());
            assertTrue(Files.exists(Path.of("src/main/resources/panel/mascot", e.sheet())) && Files.exists(Path.of("src/main/resources/panel/mascot", e.poster())), s + " assets");
            assertTrue(e.sheetWidth() <= 4096 && e.sheetHeight() <= 4096);
        }
        assertEquals(450, m.entry(MascotState.TRANSITION).orElseThrow().durationMs(), "the navigation transition is short");
        assertThrows(java.io.IOException.class, () -> MascotManifest.load("/panel/mascot/does-not-exist.json"));
    }

    private static <T extends Throwable> void assertThrows(Class<T> c, org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(c, e);
    }

    @Test
    void guardsNoMediaPlayerNoBlockingNoMascotInTheLoginOrStartupPath() throws Exception {
        Path root = Path.of("src/main/java/panel");
        try (var files = Files.walk(root.resolve("mascot"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
                assertFalse(src.contains("javafx.scene.media") || src.contains("MediaPlayer") || src.contains("ProcessBuilder"), f.getFileName() + ": no codec/media dependency, no subprocess");
                assertFalse(src.matches("(?s).*\\.(get\\(\\)|join\\(\\)).*") && !src.contains("Optional") && src.contains("Future"), f.getFileName() + ": no Future.get/join");
                assertFalse(src.contains("Thread.sleep") || src.contains(".await("), f.getFileName() + ": no blocking wait");
            }
        }
        for (String f : new String[] {"authview/AuthScreens.java", "app/AppContext.java"}) {
            assertFalse(Files.readString(root.resolve(f)).contains("panel.mascot"), f + ": the mascot is not part of login/bootstrap (poster-only identity is allowed later; no animation now)");
        }
        String app = Files.readString(root.resolve("app/PanelApp.java"));
        assertFalse(app.contains("new panel.mascot.MascotView"), "PanelApp creates no animated mascot itself");
    }
}
