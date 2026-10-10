package panel.mascot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;

/** V2.1P-1: o IDLE vivo na prática (rig, olhar, foco, visibilidade, modos, prioridades, bolha, falhas, dispose). Janela e assets reais; relógio do motor controlado por tickAt. */
class MascotPresenceViewTest {
    private final List<Mounted> mounted = new ArrayList<>();

    @AfterEach
    void releaseViewsEvenWhenAnAssertionFails() throws Exception {
        for (Mounted view : mounted) close(view);
        mounted.clear();
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

    private static MotionService motion(MotionPreference p) throws Exception {
        return fx(() -> {
            MotionService m = new MotionService();
            m.preference.set(p);
            return m;
        });
    }

    private static final class Mounted {
        final MascotView view;
        final Stage stage;
        final StackPane root;

        Mounted(MascotView v, Stage s, StackPane r) {
            view = v;
            stage = s;
            root = r;
        }
    }

    private Mounted mount(MotionService m, double size, MascotAssets assets) throws Exception {
        return mount(m, size, assets, false);
    }

    private Mounted mount(MotionService m, double size, MascotAssets assets, boolean controlledClock) throws Exception {
        return fx(() -> {
            MascotView v = new MascotView(m, size, assets, Platform::runLater, () -> 1.0, null);
            // Establish the manual clock before Scene/window attachment can schedule a real-time tick.
            if (controlledClock) v.tickAt(0);
            StackPane root = new StackPane(v);
            Stage st = new Stage();
            st.setScene(new Scene(root, 420, 320));
            st.show();
            v.setFocusOverride(Boolean.TRUE); // o foco real depende do desktop; o teste de foco usa FALSE/TRUE explicitamente
            Mounted result = new Mounted(v, st, root);
            mounted.add(result);
            return result;
        });
    }

    private static void close(Mounted m) throws Exception {
        fx(() -> {
            m.view.dispose();
            m.stage.close();
            return null;
        });
    }

    @Test
    void idleFullRunsTheRigWithAliveBlinkingBreathingAndAnEyeThatFollowsTheCursorWithinLimits() throws Exception {
        Mounted m = mount(motion(MotionPreference.FULL), 96, MascotAssets.shared(), true);
        fx(() -> { m.view.setState(MascotState.IDLE); return null; });
        until("rig on screen", () -> m.view.showing() == MascotView.Showing.RIG);
        until("life running", m.view::lifeRunning);
        assertTrue(fx(m.view::pointerTracking), "FULL tracks the cursor (scene events only)");
        // ponteiro muito à direita: o olhar vai para a direita, dentro do clamp
        double[] gx = new double[1];
        fx(() -> {
            long t = 100_000;
            m.view.tickAt(t);
            m.view.pointerAtScene(5_000, 160);
            // Pointer input and controlled ticks form one FX transaction: no native exit event
            // or scheduled pulse can clear the synthetic pointer between these assertions.
            for (int i = 0; i < 80; i++) {
                t += 20;
                m.view.tickAt(t);
            }
            gx[0] = m.view.pose().gazeX();
            return null;
        });
        assertTrue(gx[0] > 0.8 && gx[0] <= 1.0, "looks right and is clamped: " + gx[0]);
        fx(() -> {
            long t = 200_000;
            m.view.tickAt(t);
            m.view.pointerAtScene(-90_000, 160);
            for (int i = 0; i < 120; i++) {
                t += 20;
                m.view.tickAt(t);
            }
            gx[0] = m.view.pose().gazeX();
            return null;
        });
        assertTrue(gx[0] < -0.8 && gx[0] >= -1.0, "looks left: " + gx[0]);
        // o deslocamento real dos olhos é de poucos px (proporcional ao tamanho)
        assertTrue(Math.abs(fx(() -> m.view.pose().gazeX() * 96 * MascotView.MAX_GAZE_X)) <= 96 * MascotView.MAX_GAZE_X + 1e-9);
        assertTrue(96 * MascotView.MAX_GAZE_X < 4.5 && 96 * MascotView.MAX_GAZE_Y < 3.2, "a few pixels at most");
        close(m);
    }

    @Test
    void aStateThatIsFunctionalOrOneShotNeverFollowsThePointerAndIdleResumesAfterwards() throws Exception {
        Mounted m = mount(motion(MotionPreference.FULL), 96, MascotAssets.shared());
        fx(() -> { m.view.setState(MascotState.PROCESSING); return null; });
        until("processing loop", () -> m.view.showing() == MascotView.Showing.LOOP);
        assertFalse(fx(m.view::lifeRunning), "no procedural life while PROCESSING");
        assertFalse(fx(m.view::pointerTracking), "no pointer tracking while PROCESSING");
        assertEquals(MascotPriority.FUNCTIONAL, fx(m.view::priority));
        assertFalse(fx(() -> m.view.accepts(MascotPriority.CONTEXT_GUIDE)), "a hint never interrupts a task");
        assertFalse(fx(() -> m.view.accepts(MascotPriority.POINTER)));
        fx(() -> { m.view.setState(MascotState.THINKING); return null; });
        until("thinking loop", () -> m.view.shownState() == MascotState.THINKING && m.view.showing() == MascotView.Showing.LOOP);
        assertFalse(fx(m.view::pointerTracking));
        fx(() -> { m.view.setState(MascotState.IDLE); m.view.play(MascotState.ATTENTION); return null; });
        until("attention one-shot", () -> m.view.shownState() == MascotState.ATTENTION);
        assertFalse(fx(m.view::pointerTracking), "ATTENTION wins over the pointer");
        assertEquals(MascotPriority.ATTENTION_NOTIFICATION, fx(m.view::priority));
        until("back to the alive idle", () -> m.view.showing() == MascotView.Showing.RIG);
        assertTrue(fx(m.view::lifeRunning));
        close(m);
    }

    @Test
    void windowFocusLossStopsTheEngineAndPointerTrackingAndFocusResumesCleanly() throws Exception {
        Mounted m = mount(motion(MotionPreference.FULL), 96, MascotAssets.shared());
        fx(() -> { m.view.setState(MascotState.IDLE); return null; });
        until("running", m.view::lifeRunning);
        fx(() -> { m.view.setFocusOverride(false); return null; });
        assertFalse(fx(m.view::lifeRunning), "no timer while unfocused");
        assertFalse(fx(m.view::pointerTracking), "no tracking while unfocused");
        fx(() -> { m.view.setFocusOverride(Boolean.TRUE); return null; });
        until("resumes", m.view::lifeRunning);
        assertTrue(fx(m.view::pointerTracking));
        close(m);
    }

    @Test
    void invisibleOrDetachedOrHiddenWindowMeansNoTimerAtAll() throws Exception {
        Mounted m = mount(motion(MotionPreference.FULL), 96, MascotAssets.shared());
        fx(() -> { m.view.setState(MascotState.IDLE); return null; });
        until("running", m.view::lifeRunning);
        fx(() -> { m.view.setVisible(false); return null; });
        assertFalse(fx(m.view::lifeRunning), "invisible view: timer stopped");
        fx(() -> { m.view.setVisible(true); return null; });
        until("visible again", m.view::lifeRunning);
        fx(() -> { m.root.getChildren().remove(m.view); return null; });
        assertFalse(fx(m.view::lifeRunning), "removed from the scene: timer stopped");
        assertFalse(fx(m.view::pointerTracking));
        fx(() -> { m.root.getChildren().add(m.view); return null; });
        until("re-attached", m.view::lifeRunning);
        fx(() -> { m.stage.hide(); return null; });
        assertFalse(fx(m.view::lifeRunning), "hidden window: timer stopped");
        close(m);
    }

    @Test
    void reducedBlinksOnlyAndOffIsCompletelyStatic() throws Exception {
        Mounted m = mount(motion(MotionPreference.REDUCED), 96, MascotAssets.shared(), true);
        fx(() -> { m.view.setState(MascotState.IDLE); return null; });
        until("rig in REDUCED", () -> m.view.showing() == MascotView.Showing.RIG);
        assertFalse(fx(m.view::pointerTracking), "REDUCED never follows the cursor");
        fx(() -> {
            m.view.pointerAtScene(5_000, 100);
            long t = 0;
            double maxBlink = 0;
            for (int i = 0; i < 2_500; i++) {
                t += 20;
                m.view.tickAt(t);
                var p = m.view.pose();
                assertEquals(0, p.gazeX(), 0);
                assertEquals(0, p.breath(), 0);
                assertEquals(0, p.tiltDeg(), 0);
                maxBlink = Math.max(maxBlink, p.blink());
            }
            assertTrue(maxBlink > 0.9, "an occasional blink is the only motion");
            return null;
        });
        close(m);
        Mounted off = mount(motion(MotionPreference.OFF), 96, MascotAssets.shared());
        fx(() -> { off.view.setState(MascotState.IDLE); return null; });
        until("poster in OFF", () -> off.view.showing() == MascotView.Showing.POSTER);
        Thread.sleep(300);
        assertEquals(MascotView.Showing.POSTER, fx(off.view::showing));
        assertFalse(fx(off.view::lifeRunning));
        assertFalse(fx(off.view::pointerTracking));
        close(off);
    }

    @Test
    void switchingMotionModesAtRuntimeIsCleanFullToReducedToOffAndBack() throws Exception {
        MotionService mo = motion(MotionPreference.FULL);
        Mounted m = mount(mo, 96, MascotAssets.shared());
        fx(() -> { m.view.setState(MascotState.IDLE); return null; });
        until("full rig", () -> m.view.lifeRunning() && m.view.pointerTracking());
        fx(() -> { mo.preference.set(MotionPreference.REDUCED); return null; });
        until("reduced", () -> m.view.showing() == MascotView.Showing.RIG && !m.view.pointerTracking());
        fx(() -> { mo.preference.set(MotionPreference.OFF); return null; });
        until("off", () -> m.view.showing() == MascotView.Showing.POSTER && !m.view.lifeRunning());
        fx(() -> { mo.preference.set(MotionPreference.FULL); return null; });
        until("full again", () -> m.view.lifeRunning() && m.view.pointerTracking());
        close(m);
    }

    @Test
    void rapidStateChangesLeaveExactlyOneEngineAndNoLeak() throws Exception {
        MascotAssets assets = MascotAssets.shared();
        Mounted m = mount(motion(MotionPreference.FULL), 96, assets);
        fx(() -> {
            for (int i = 0; i < 5; i++) {
                m.view.setState(MascotState.IDLE);
                m.view.setState(MascotState.THINKING);
                m.view.setState(MascotState.PROCESSING);
            }
            m.view.setState(MascotState.IDLE);
            return null;
        });
        until("settles on the alive idle", () -> m.view.showing() == MascotView.Showing.RIG && m.view.lifeRunning());
        Thread.sleep(500);
        assertEquals(MascotView.Showing.RIG, fx(m.view::showing), "late results of older states never reappear");
        assertEquals(MascotState.IDLE, fx(m.view::shownState));
        close(m);
        assertEquals(0, assets.cached(), "everything released");
    }

    @Test
    void aBrokenRigFallsBackToTheIdleSheetLoopThenToThePosterWithoutExceptions() throws Exception {
        List<Throwable> uncaught = new ArrayList<>();
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        try {
            MascotAssets real = MascotAssets.shared();
            MascotAssets noRig = new MascotAssets(rel -> rel.startsWith("rig/") ? null : MascotAssets.class.getResource("/panel/mascot/" + rel).toExternalForm(), MascotManifest.RESOURCE);
            Mounted m = mount(motion(MotionPreference.FULL), 96, noRig);
            fx(() -> { m.view.setState(MascotState.IDLE); return null; });
            until("falls back to the sheet loop", () -> m.view.showing() == MascotView.Showing.LOOP && m.view.shownState() == MascotState.IDLE);
            assertFalse(fx(m.view::lifeRunning));
            close(m);
            MascotAssets nothing = new MascotAssets(rel -> null, MascotManifest.RESOURCE);
            Mounted n = mount(motion(MotionPreference.FULL), 96, nothing);
            fx(() -> { n.view.setState(MascotState.IDLE); n.view.play(MascotState.ATTENTION); return null; });
            Thread.sleep(600);
            assertNotEquals(MascotView.Showing.RIG, fx(n.view::showing));
            close(n);
            assertNotNull(real);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old);
        }
        assertTrue(uncaught.isEmpty(), "no uncaught exception: " + uncaught);
    }

    @Test
    void theHintBubbleIsSmallNonModalDismissableAndTogglesOnClick() throws Exception {
        Mounted m = mount(motion(MotionPreference.FULL), 96, MascotAssets.shared());
        MascotHintBubble bubble = fx(() -> new MascotHintBubble(m.view));
        MascotHint hint = new StaticMascotGuideProvider().hint(MascotContext.CHAIN_OFFLINE).orElseThrow();
        boolean focusedBefore = fx(m.stage::isFocused);
        fx(() -> { bubble.toggle(hint); return null; });
        assertTrue(fx(bubble::showing), "click opens it");
        assertEquals("The local BYX node is offline.", fx(bubble::text));
        assertEquals(focusedBefore, fx(m.stage::isFocused), "it does not take the focus of the main window");
        fx(() -> { bubble.toggle(hint); return null; });
        assertFalse(fx(bubble::showing), "a second click closes it");
        fx(() -> { bubble.show(hint); return null; });
        assertTrue(fx(bubble::showing));
        fx(() -> { m.view.dispose(); return null; });
        fx(() -> { bubble.hide(); return null; });
        assertFalse(fx(bubble::showing));
        assertNull(fx(bubble::text));
        fx(() -> { m.stage.close(); return null; });
    }

    @Test
    void stageModesHaloIsTheDefaultAndNoModeDrawsAFilledBackgroundExceptSurface() throws Exception {
        assertEquals(MascotStage.Mode.HALO, MascotStage.DEFAULT_MODE);
        assertTrue(fx(() -> new MascotStage(96, MascotStage.Mode.HALO).getBackground().getFills().isEmpty()), "no filled disc by default");
        assertTrue(fx(() -> new MascotStage(96, MascotStage.Mode.TRANSPARENT).getBackground().getFills().isEmpty()));
        assertFalse(fx(() -> new MascotStage(96, MascotStage.Mode.SURFACE).getBackground().getFills().isEmpty()));
        assertNotNull(MascotStage.effectFor(MascotStage.Mode.HALO, 96));
        assertNull(MascotStage.effectFor(MascotStage.Mode.TRANSPARENT, 96));
        assertNull(MascotStage.effectFor(MascotStage.Mode.SURFACE, 96));
        MotionService mo = motion(MotionPreference.FULL);
        MascotView v = fx(() -> new MascotView(mo, MascotSize.MEDIUM));
        assertEquals(MascotStage.Mode.HALO, fx(v::stageMode));
        assertEquals(96.0, fx(v::size));
        fx(() -> { v.dispose(); return null; });
    }

    @Test
    void placementUsesOnlyTheClosedAnchors() throws Exception {
        MotionService mo = motion(MotionPreference.FULL);
        fx(() -> {
            MascotView v = new MascotView(mo, MascotSize.SMALL);
            StackPane content = new StackPane();
            for (MascotAnchor a : MascotAnchor.values()) {
                assertNotNull(MascotPlacement.place(content, v, a));
                if (a != MascotAnchor.INLINE) {
                    v.getChildren().size(); // sem exceção: o mascote é re-parentado por cada âncora
                }
                new StackPane().getChildren().clear();
            }
            v.dispose();
            return null;
        });
    }
}
