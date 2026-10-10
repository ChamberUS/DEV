package panel.mascot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;

/** V2.1P: o MascotView real com os assets reais: FULL/REDUCED/OFF, one-shots, mudanças rápidas, falhas de asset, dispose e vazamento. Nunca exceção global. */
class MascotViewTest {
    private final List<MascotView> mounted = new ArrayList<>();

    @AfterEach
    void releaseViewsEvenWhenAnAssertionFails() throws Exception {
        fx(() -> { mounted.forEach(MascotView::dispose); mounted.clear(); return null; });
    }
    private static <T> T fx(Supplier<T> s) throws Exception {
        return DeskHarness.fx(s);
    }

    private static void until(String what, BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < end) {
            if (fx(() -> c.getAsBoolean())) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("timeout: " + what);
    }

    private MascotView mount(MotionService motion, MascotAssets assets, double size) throws Exception {
        return fx(() -> {
            MascotView v = new MascotView(motion, size, assets, Platform::runLater, () -> 1.0, MascotStage.ACCENT_BYX);
            Scene sc = new Scene(new StackPane(v), 300, 300);
            mounted.add(v);
            return v;
        });
    }

    private static MotionService motion(MotionPreference p) throws Exception {
        return fx(() -> {
            MotionService m = new MotionService();
            m.preference.set(p);
            return m;
        });
    }

    @Test
    void fullMotionLoopsAndAdvancesFramesThenStopsToThePoster() throws Exception {
        MotionService m = motion(MotionPreference.FULL);
        MascotAssets assets = MascotAssets.shared();
        MascotView v = mount(m, assets, 96);
        fx(() -> { v.setState(MascotState.THINKING); return null; });
        until("loop starts", () -> v.showing() == MascotView.Showing.LOOP);
        int f0 = fx(v::frameIndex);
        until("frames advance", () -> v.frameIndex() != f0);
        assertEquals(1, (int) fx(m::runningLoops), "exactly one loop, registered in the motion service");
        assertTrue(fx(v::firstFrameMicros) > 0);
        fx(() -> { v.stop(); return null; });
        until("poster", () -> v.showing() == MascotView.Showing.POSTER);
        assertEquals(0, (int) fx(m::runningLoops), "stop leaves no loop");
        fx(() -> { v.dispose(); return null; });
        assertEquals(0, assets.cached(), "dispose releases every image");
    }

    @Test
    void reducedAndOffNeverAnimateAndAcceptanceOfSwitchingModes() throws Exception {
        MotionService m = motion(MotionPreference.REDUCED);
        MascotAssets assets = MascotAssets.shared();
        MascotView v = mount(m, assets, 96);
        fx(() -> { v.setState(MascotState.PROCESSING); return null; });
        until("poster in REDUCED", () -> v.showing() == MascotView.Showing.POSTER);
        Thread.sleep(300);
        assertEquals(MascotView.Showing.POSTER, fx(v::showing), "REDUCED shows the poster only");
        assertEquals(0, (int) fx(m::runningLoops));
        fx(() -> { m.preference.set(MotionPreference.FULL); return null; });
        until("FULL after REDUCED", () -> v.showing() == MascotView.Showing.LOOP);
        fx(() -> { m.preference.set(MotionPreference.REDUCED); return null; });
        until("back to poster", () -> v.showing() == MascotView.Showing.POSTER);
        fx(() -> { m.preference.set(MotionPreference.OFF); return null; });
        Thread.sleep(200);
        assertEquals(MascotView.Showing.POSTER, fx(v::showing));
        assertEquals(0, (int) fx(m::runningLoops));
        fx(() -> { v.dispose(); return null; });
    }

    @Test
    void oneShotPlaysOnceHoldsThePoseAndReturnsToTheSteadyState() throws Exception {
        MotionService m = motion(MotionPreference.FULL);
        MascotView v = mount(m, MascotAssets.shared(), 96);
        fx(() -> { v.setStaticState(MascotState.IDLE); return null; });
        until("idle poster", () -> v.shownState() == MascotState.IDLE);
        fx(() -> { v.play(MascotState.ATTENTION); return null; });
        until("one-shot plays", () -> v.showing() == MascotView.Showing.ONE_SHOT && v.shownState() == MascotState.ATTENTION);
        assertEquals(0, (int) fx(m::runningLoops), "a one-shot is not a loop");
        until("returns to the steady poster", () -> v.shownState() == MascotState.IDLE && v.showing() == MascotView.Showing.POSTER);
        Thread.sleep(500);
        assertEquals(MascotState.IDLE, fx(v::shownState), "it does not replay by itself");
        fx(() -> { v.dispose(); return null; });
    }

    @Test
    void rapidStateChangesNeverResurrectAnOldAnimation() throws Exception {
        MotionService m = motion(MotionPreference.FULL);
        MascotView v = mount(m, MascotAssets.shared(), 96);
        fx(() -> {
            v.setState(MascotState.THINKING);
            v.setState(MascotState.PROCESSING);
            v.setState(MascotState.SYNCING);
            v.setStaticState(MascotState.IDLE);
            return null;
        });
        until("settles on IDLE poster", () -> v.shownState() == MascotState.IDLE && v.showing() == MascotView.Showing.POSTER);
        Thread.sleep(800); // tempo para qualquer sheet atrasada chegar
        assertEquals(MascotState.IDLE, fx(v::shownState), "late results of older states are discarded");
        assertEquals(MascotView.Showing.POSTER, fx(v::showing));
        assertEquals(0, (int) fx(m::runningLoops));
        fx(() -> { v.dispose(); return null; });
    }

    @Test
    void missingCorruptOrUnreadableAssetsFallBackSafelyWithoutAnyException() throws Exception {
        Path garbage = Files.createTempFile("mascot-corrupt", ".png");
        Files.writeString(garbage, "this is not a png");
        List<Throwable> uncaught = new ArrayList<>();
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        try {
            MotionService m = motion(MotionPreference.FULL);
            for (MascotAssets assets : new MascotAssets[] {
                    new MascotAssets(rel -> null, MascotManifest.RESOURCE), // asset ausente
                    new MascotAssets(rel -> garbage.toUri().toString(), MascotManifest.RESOURCE), // corrompido
                    new MascotAssets(rel -> { throw new IllegalStateException("source broke"); }, MascotManifest.RESOURCE), // fonte que lança
                    new MascotAssets(rel -> null, "/panel/mascot/missing-manifest.json")}) { // manifesto ausente
                MascotView v = mount(m, assets, 96);
                fx(() -> {
                    v.setState(MascotState.PROCESSING);
                    v.play(MascotState.NOTIFICATION);
                    v.transitionTo(MascotState.SYNCING);
                    return null;
                });
                Thread.sleep(700);
                assertEquals(0, (int) fx(m::runningLoops), "no animation without assets");
                assertNotEquals(MascotView.Showing.LOOP, fx(v::showing));
                fx(() -> { v.setMotionMode(MotionPreference.OFF); v.stop(); v.dispose(); return null; });
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old);
            Files.deleteIfExists(garbage);
        }
        assertTrue(uncaught.isEmpty(), "no uncaught exception (no global 'Something went wrong'): " + uncaught);
    }

    @Test
    void closingTheScreenMidAnimationReleasesEverythingAndLaterCallbacksDoNothing() throws Exception {
        MotionService m = motion(MotionPreference.FULL);
        MascotAssets assets = MascotAssets.shared();
        MascotView v = mount(m, assets, 128);
        fx(() -> { v.setState(MascotState.PROCESSING); return null; });
        until("running", () -> v.showing() == MascotView.Showing.LOOP);
        fx(() -> { v.dispose(); return null; });
        assertEquals(0, (int) fx(m::runningLoops));
        assertEquals(0, assets.cached());
        fx(() -> { v.setState(MascotState.THINKING); v.play(MascotState.ATTENTION); v.setMotionMode(MotionPreference.FULL); return null; }); // após dispose: inofensivo
        Thread.sleep(300);
        assertEquals(MascotView.Showing.NONE, fx(v::showing));
        assertEquals(0, assets.cached());
    }

    @Test
    void acquiringAnAssetNeverBlocksTheFxThreadAndNeverUpscales() throws Exception {
        MascotManifest man = MascotManifest.load(MascotManifest.RESOURCE);
        var e = man.entry(MascotState.PROCESSING).orElseThrow();
        MascotAssets assets = MascotAssets.shared();
        long ms = fx(() -> {
            long t0 = System.nanoTime();
            MascotAssets.Lease l = assets.sheet(e, 192);
            long took = (System.nanoTime() - t0) / 1_000_000;
            l.close();
            return took;
        });
        assertTrue(ms < 150, "acquire returns without waiting for the decode: " + ms + " ms");
        assertEquals(1.0, MascotAssets.factor(512), "never above the 384 master");
        assertEquals(0.5, MascotAssets.factor(192), 1e-9);
        assertEquals(64 / 384.0, MascotAssets.factor(64), 1e-9);
        assertFalse(MascotAssets.shared().cached() < 0);
    }

    @Test
    void workspaceTransitionOverlayOnlyRunsInFullDoesNotStackAndDoesNotCaptureTheMouse() throws Exception {
        MotionService full = motion(MotionPreference.FULL);
        MotionService reduced = motion(MotionPreference.REDUCED);
        fx(() -> {
            MascotTransitionOverlay o = new MascotTransitionOverlay(reduced);
            o.trigger();
            assertFalse(o.busy(), "REDUCED: no transition animation");
            MascotTransitionOverlay f = new MascotTransitionOverlay(full);
            assertTrue(f.isMouseTransparent());
            f.trigger();
            assertTrue(f.busy());
            f.trigger(); // não empilha
            f.dispose();
            o.dispose();
            return null;
        });
    }
}
