package panel.shell.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javafx.event.Event;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.FxBridge;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** The avatar inside a real JavaFX scene: tracking, confinement, lifecycle, accessibility and operations. */
class MascotAvatarTest {
    private static final class Fixture {
        final MotionService motion = new MotionService();
        final AtomicLong clock = new AtomicLong(10_000);
        final Button button = new Button();
        final HBox root = new HBox(button);
        final Stage stage = new Stage();
        final Scene scene = new Scene(root, 600, 400);
        final MascotAvatar avatar;

        Fixture(MotionPreference pref, boolean timer) {
            motion.preference.set(pref);
            button.getStyleClass().add("byx-avatar");
            button.setMinSize(36, 36);
            button.setPrefSize(36, 36);
            button.setMaxSize(36, 36);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            avatar = new MascotAvatar(button, motion, clock::get, timer);
            stage.show();
            root.applyCss();
            root.layout();
            avatar.windowActiveForTest(true);
        }

        void moveTo(double x, double y) {
            Event.fireEvent(root, new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, x, y, MouseButton.NONE, 0, false, false, false, false, false, false,
                    false, false, false, false, null));
        }

        void frames(long ms) {
            for (long t = 0; t <= ms; t += 16) {
                clock.addAndGet(16);
                avatar.frame(clock.get());
            }
        }

        void close() {
            avatar.dispose();
            stage.close();
        }
    }

    @Test
    void theArtworkReplacesTheInitialsInTheSameButton() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            f.avatar.setUserName("GW User");
            Object[] out = {f.button.getText(), f.button.getGraphic() == f.avatar.art(), f.button.getAccessibleText(), f.button.getAccessibleRole(),
                    f.button.isFocusTraversable(), f.avatar.art().isMouseTransparent(), f.button.getWidth(), f.button.getHeight()};
            f.close();
            return out;
        });
        assertEquals("", r[0], "no initials");
        assertEquals(true, r[1]);
        assertEquals("Account menu, GW User", r[2]);
        assertEquals(AccessibleRole.MENU_BUTTON, r[3]);
        assertEquals(true, r[4], "reachable by keyboard");
        assertEquals(true, r[5], "the art never steals pointer events from the button");
        assertEquals(36.0, (double) r[6], 0.5);
        assertEquals(36.0, (double) r[7], 0.5);
    }

    @Test
    void eyesFollowTheSceneCursorWithinTheClampAndIgnoreOutsideEvents() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            f.moveTo(580, 380); // bottom-right of the scene, far from the avatar (top-left)
            f.frames(1_500);
            var right = f.avatar.pose();
            f.moveTo(-50, 100); // outside the scene: ignored, pointer cleared
            boolean has = f.avatar.engine().hasPointer();
            f.moveTo(580, 380);
            f.frames(500);
            Event.fireEvent(f.root, new MouseEvent(f.root, f.root, MouseEvent.MOUSE_EXITED_TARGET, 1, 1, 1, 1, MouseButton.NONE, 0, false, false, false,
                    false, false, false, false, false, false, false, null));
            boolean afterExit = f.avatar.engine().hasPointer();
            f.close();
            return new Object[] {right.eyeX(), right.eyeY(), has, afterExit};
        });
        double tx = MascotTokens.shared().clampX();
        double ty = MascotTokens.shared().clampY();
        assertTrue((double) r[0] > 0.6 * tx && (double) r[0] <= tx + 1e-6, "looks right: " + r[0]);
        assertTrue((double) r[1] > 0 && (double) r[1] <= ty + 1e-6, "looks down: " + r[1]);
        assertEquals(false, r[2], "an event outside the Scene is not tracked");
        assertEquals(false, r[3], "leaving the window returns the eyes to idle");
    }

    @Test
    void reducedAndOffNeverTrackAndAreStillFunctional() throws Exception {
        for (MotionPreference p : new MotionPreference[] {MotionPreference.REDUCED, MotionPreference.OFF}) {
            Object[] r = FxBridge.fx(() -> {
                Fixture f = new Fixture(p, false);
                f.moveTo(580, 380);
                f.frames(1_000);
                Object[] out = {f.avatar.pose().eyeX(), f.avatar.engine().hasPointer(), f.button.getGraphic() != null, f.button.isDisabled()};
                f.close();
                return out;
            });
            assertEquals(0.0, (double) r[0], 1e-9, p + " ignores the cursor");
            assertEquals(false, r[1]);
            assertEquals(true, r[2], "artwork still present");
            assertEquals(false, r[3], "still an enabled, focusable control");
        }
    }

    @Test
    void motionPreferenceChangesApplyLive() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            f.moveTo(580, 380);
            f.frames(800);
            double before = f.avatar.pose().eyeX();
            f.motion.preference.set(MotionPreference.OFF);
            f.frames(32);
            Object[] out = {before, f.avatar.pose().eyeX(), f.avatar.engine().mode()};
            f.close();
            return out;
        });
        assertTrue((double) r[0] > 1);
        assertEquals(0.0, (double) r[1], 1e-9);
        assertEquals(AvatarMotion.Mode.OFF, r[2]);
    }

    @Test
    void theClickReactionIsAdditionalToTheMenuActionAndNeverDelaysIt() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            AtomicInteger menu = new AtomicInteger();
            f.button.setOnAction(e -> menu.incrementAndGet()); // the existing UserMenu handler lives here
            f.button.fire();
            int opened = menu.get(); // synchronous: before any frame ran
            f.clock.addAndGet(100);
            f.avatar.frame(f.clock.get());
            boolean reacting = f.avatar.pose().reacting();
            f.frames(600);
            boolean done = !f.avatar.pose().reacting();
            Node parent = f.button.getParent();
            f.close();
            return new Object[] {opened, reacting, done, parent == f.root};
        });
        assertEquals(1, r[0], "menu action ran immediately, in the same event");
        assertEquals(true, r[1]);
        assertEquals(true, r[2]);
        assertEquals(true, r[3]);
    }

    @Test
    void headerNodeIsNeverReparentedAndKeepsItsBoundsWhileTheMenuIsOpenAndOnReaction() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            javafx.beans.property.SimpleBooleanProperty menuOpen = new javafx.beans.property.SimpleBooleanProperty();
            f.avatar.bindMenuOpen(menuOpen);
            var before = f.button.localToScene(f.button.getBoundsInLocal());
            Node graphic = f.button.getGraphic();
            menuOpen.set(true);
            f.button.fire();
            f.frames(1_000);
            var during = f.button.localToScene(f.button.getBoundsInLocal());
            boolean looks = Math.abs(f.avatar.pose().eyeY() - MascotTokens.shared().menuTargetY()) < 0.1;
            menuOpen.set(false);
            f.frames(1_000);
            var after = f.button.localToScene(f.button.getBoundsInLocal());
            Object[] out = {before.equals(during), before.equals(after), f.button.getParent() == f.root, f.button.getGraphic() == graphic, looks};
            f.close();
            return out;
        });
        for (Object o : r) {
            assertEquals(true, o);
        }
    }

    @Test
    void operationsDriveTheLookAndTheAccessibleNameAndNothingElseDoes() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            f.avatar.setUserName("Ana");
            f.frames(2_000);
            var idle = f.avatar.presentation();
            var tok = f.avatar.operations().begin("benefits.refresh");
            f.frames(200);
            var early = f.avatar.presentation();
            f.frames(300);
            var loading = f.avatar.presentation();
            String label = f.button.getAccessibleText();
            double rings = f.avatar.pose().ringsMix();
            f.avatar.operations().end(tok, false, "BOOM");
            f.frames(100);
            var err = f.avatar.presentation();
            String errLabel = f.button.getAccessibleText();
            f.frames(4_300);
            var back = f.avatar.presentation();
            f.close();
            return new Object[] {idle, early, loading, label, rings, err, errLabel, back};
        });
        assertEquals(OperationRegistry.Presentation.NONE, r[0], "no fake loading");
        assertEquals(OperationRegistry.Presentation.NONE, r[1], "operations shorter than 300 ms stay silent");
        assertEquals(OperationRegistry.Presentation.LOADING, r[2]);
        assertEquals("Account menu, Ana, loading", r[3]);
        assertTrue((double) r[4] > 0.3, "rings are entering (320 ms ramp): " + r[4]);
        assertEquals(OperationRegistry.Presentation.ERROR, r[5]);
        assertEquals("Account menu, Ana, an operation did not finish", r[6]);
        assertEquals(OperationRegistry.Presentation.NONE, r[7]);
    }

    @Test
    void repeatedSceneAttachDetachAndDisposeNeverAccumulateListeners() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            int attached = f.avatar.sceneFilterCount();
            for (int i = 0; i < 50; i++) {
                f.root.getChildren().remove(f.button);
                int detached = f.avatar.sceneFilterCount();
                if (detached != 0) {
                    return new Object[] {"detached=" + detached};
                }
                f.root.getChildren().add(f.button);
                if (f.avatar.sceneFilterCount() != attached) {
                    return new Object[] {"reattached=" + f.avatar.sceneFilterCount()};
                }
            }
            f.close();
            return new Object[] {attached, f.avatar.sceneFilterCount(), f.avatar.disposed(), f.button.getGraphic(), f.avatar.timerRunning()};
        });
        assertEquals(3, r[0]);
        assertEquals(0, r[1], "dispose removed every scene filter");
        assertEquals(true, r[2]);
        assertNull(r[3], "graphic detached");
        assertEquals(false, r[4]);
    }

    @Test
    void disposeInvalidatesPendingOperationsAndIsIdempotent() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, false);
            var tok = f.avatar.operations().begin("auth.logout");
            f.avatar.dispose();
            f.avatar.dispose();
            f.avatar.operations().end(tok, false, "LATE");
            int pending = f.avatar.registry().pending();
            f.frames(100); // frames after dispose are inert
            f.stage.close();
            return new Object[] {pending, f.avatar.registry().presentation()};
        });
        assertEquals(0, r[0]);
        assertEquals(OperationRegistry.Presentation.NONE, r[1]);
    }

    @Test
    void theSingleTimerRunsOnlyWhileActiveAndSomethingNeedsFrames() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, true);
            boolean fullActive = f.avatar.timerRunning();
            f.avatar.windowActiveForTest(false); // unfocused or minimised
            boolean inactive = f.avatar.timerRunning();
            f.avatar.windowActiveForTest(true);
            boolean resumed = f.avatar.timerRunning();
            f.motion.preference.set(MotionPreference.OFF);
            f.avatar.windowActiveForTest(true);
            f.clock.addAndGet(10_000);
            f.avatar.frame(f.clock.get());
            f.avatar.windowActiveForTest(true);
            // OFF with nothing pending: nothing needs frames any more
            boolean offIdle = f.avatar.timerRunning();
            var tok = f.avatar.operations().begin("x");
            boolean withOp = f.avatar.timerRunning();
            f.avatar.operations().end(tok, true, null);
            f.close();
            return new Object[] {fullActive, inactive, resumed, offIdle, withOp, f.avatar.timerRunning()};
        });
        assertEquals(true, r[0], "FULL + active window runs");
        assertEquals(false, r[1], "no timer while the window is unfocused/minimised");
        assertEquals(true, r[2], "resumes");
        assertEquals(true, r[4], "a real pending operation wakes the timer even in OFF (for its delay/hold)");
        assertEquals(false, r[5], "disposed: stopped");
    }
}
