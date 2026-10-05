package panel;

import static org.junit.jupiter.api.Assertions.*;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.ViewTransitionService;
import panel.ui.motion.OrbIndicator;

/** Regressões de fundação: onFinished preservado, loops com ciclo de vida, entrada de cards uma vez. */
class FoundationLifecycleTest {
    private static Region card() { Region r = new Region(); r.getStyleClass().add("card"); return r; }

    @Test void playPreservesCallerOnFinished() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            Region node = new Region();
            boolean[] called = {false};
            Timeline t = new Timeline(new KeyFrame(Duration.millis(10), new KeyValue(node.opacityProperty(), 0.5)));
            t.setOnFinished(e -> called[0] = true);
            m.play(node, t);
            t.jumpTo(t.getTotalDuration());
            t.getOnFinished().handle(null);
            t.stop();
            assertTrue(called[0], "o onFinished do chamador não pode ser sobrescrito");
            assertNull(node.getProperties().get("motion.anim"));
        });
    }

    @Test void orbRestartsAfterLoopWasRemoved() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            OrbIndicator orb = new OrbIndicator(m, 16, "warn");
            Pane root = new Pane(orb);
            Scene scene = new Scene(root, 100, 100);
            orb.setActive(true);
            assertTrue(orb.running());
            m.stopLoops(); // ex.: troca de preferência ou saída de cena removeu o loop
            assertFalse(orb.running());
            orb.setActive(true);
            assertTrue(orb.running(), "setActive(true) deve recriar o loop removido");
            orb.setActive(false);
            assertFalse(orb.running());
            assertEquals(0, m.loopCount());
            assertNotNull(scene);
        });
    }

    @Test void orbInReducedModeCreatesNoLoop() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            m.preference.set(MotionPreference.REDUCED);
            OrbIndicator orb = new OrbIndicator(m, 16, "warn");
            orb.setActive(true);
            assertFalse(orb.running());
            assertEquals(0, m.loopCount());
        });
    }

    @Test void isLoopingTracksRegistration() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            Region owner = new Region();
            new Scene(new Pane(owner), 50, 50);
            Animation a = m.loop(owner, () -> new Timeline(new KeyFrame(Duration.millis(100))));
            assertTrue(m.isLooping(a));
            m.removeLoop(a);
            assertFalse(m.isLooping(a));
            assertFalse(m.isLooping(null));
        });
    }

    @Test void reshowingSameViewDoesNotReplayCardEntry() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            ViewTransitionService nav = new ViewTransitionService(m);
            Pane a = new Pane(card()), b = new Pane(card());
            List<javafx.scene.Node> pages = List.of(a, b);
            nav.show(pages, a, false);
            assertEquals(1, nav.entries());
            nav.show(pages, a, false);
            nav.show(pages, a, false);
            assertEquals(1, nav.entries(), "reexibir a mesma view não repete a entrada");
            nav.show(pages, b, true);
            assertEquals(2, nav.entries());
            nav.forget();
            nav.show(pages, b, false);
            assertEquals(3, nav.entries(), "após logout/login a entrada volta a valer");
            m.reference.settleTree(a); m.reference.settleTree(b);
        });
    }

    @Test void onlyOneViewIsVisibleAfterRapidSwitching() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService();
            ViewTransitionService nav = new ViewTransitionService(m);
            Pane a = new Pane(card()), b = new Pane(card()), c = new Pane(card());
            List<javafx.scene.Node> pages = List.of(a, b, c);
            for (int i = 0; i < 30; i++) nav.show(pages, pages.get(i % 3), i % 2 == 0);
            long visible = pages.stream().filter(javafx.scene.Node::isVisible).count();
            assertEquals(1, visible);
            m.reference.settleTree(pages.get(29 % 3));
        });
    }
}
