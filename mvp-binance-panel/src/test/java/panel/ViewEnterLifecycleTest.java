package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.ViewTransitionService;

/** P3.3: a entrada pesada roda uma vez por instância de View, na primeira exibição, em FULL, REDUCED e OFF. */
class ViewEnterLifecycleTest {
    private static Pane view() {
        Region card = new Region();
        card.getStyleClass().add("card");
        return new Pane(card);
    }

    private static final class Fixture {
        final MotionService motion = new MotionService();
        final ViewTransitionService nav = new ViewTransitionService(motion);
        final List<Node> pages = new ArrayList<>();
        final StackPane root = new StackPane();
        final Scene scene;

        Fixture(MotionPreference p, int views) {
            motion.preference.set(p);
            for (int i = 0; i < views; i++) {
                pages.add(view());
            }
            root.getChildren().addAll(pages);
            scene = new Scene(root, 1440, 900);
        }

        void show(int i) {
            nav.show(pages, pages.get(i), false);
        }

        void settle() {
            pages.forEach(motion.reference::settleTree);
        }
    }

    @Test
    void newViewFirstShowEntersOnce() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int n = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 2);
                f.show(0);
                f.settle();
                return f.nav.entries();
            });
            assertEquals(1, n, p.name());
        }
    }

    @Test
    void hideThenShowSameInstanceDoesNotReplay() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 2);
                f.show(0);
                f.show(1); // A escondida
                int afterB = f.nav.entries();
                f.show(0); // mesma instância de A volta
                int afterReturn = f.nav.entries();
                f.settle();
                return new int[] {afterB, afterReturn, f.pages.get(0).isVisible() ? 1 : 0, f.pages.get(1).isVisible() ? 1 : 0};
            });
            assertEquals(2, r[0], p + " B first show enters");
            assertEquals(2, r[1], p + " returning to A does not replay");
            assertEquals(1, r[2], p + " A visible");
            assertEquals(0, r[3], p + " B hidden");
        }
    }

    @Test
    void twentyRoundTripsEnterOnlyOnFirstDisplay() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int n = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 3);
                f.show(0);
                for (int i = 0; i < 20; i++) {
                    f.show(1);
                    f.show(2);
                    f.show(0);
                }
                f.settle();
                return f.nav.entries();
            });
            assertEquals(3, n, p + " one entry per View instance");
        }
    }

    @Test
    void dataRefreshStatusResizeAndForegroundNeverReplay() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 2);
                f.show(0);
                int first = f.nav.entries();
                for (int i = 0; i < 10; i++) {
                    f.show(0); // refresh de dados / status reaplica a rota atual
                }
                int afterRefresh = f.nav.entries();
                f.root.resize(1280, 760); // resize
                f.root.layout();
                f.root.resize(1920, 1080);
                f.root.layout();
                f.show(0);
                int afterResize = f.nav.entries();
                f.motion.setActive(false); // janela para o fundo
                f.motion.setActive(true);  // de volta à frente
                f.show(0);
                int afterForeground = f.nav.entries();
                f.settle();
                return new int[] {first, afterRefresh, afterResize, afterForeground};
            });
            assertEquals(1, r[0], p.name());
            assertEquals(1, r[1], p + " data refresh / status update");
            assertEquals(1, r[2], p + " resize");
            assertEquals(1, r[3], p + " background to foreground");
        }
    }

    @Test
    void disposedViewRecreatedAsNewInstanceEntersOnce() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 2);
                f.show(0);
                f.show(1);
                Node old = f.pages.get(0);
                f.nav.dispose(old); // View A descartada
                Pane fresh = view(); // nova instância de A
                f.pages.set(0, fresh);
                f.root.getChildren().set(0, fresh);
                f.show(0);
                int afterNew = f.nav.entries();
                f.show(1);
                f.show(0);
                int afterReturn = f.nav.entries();
                f.settle();
                return new int[] {afterNew, afterReturn};
            });
            assertEquals(3, r[0], p + " new instance gets its first entry");
            assertEquals(3, r[1], p + " and only once");
        }
    }

    @Test
    void endOfSessionDisposesEveryInstance() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, 1);
            f.show(0);
            boolean entered = f.nav.hasEntered(f.pages.get(0));
            f.nav.forget(); // logout/login
            boolean stale = !f.nav.hasEntered(f.pages.get(0));
            f.settle();
            return new boolean[] {entered, stale};
        });
        assertTrue(r[0]);
        assertTrue(r[1], "marks from an ended session do not suppress the next session's first entry");
    }

    @Test
    void sameLogicalBehaviourInEveryMode() throws Exception {
        List<String> states = new ArrayList<>();
        for (MotionPreference p : MotionPreference.values()) {
            states.add(FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 3);
                for (int i = 0; i < 30; i++) {
                    f.show(i % 3);
                }
                f.settle();
                long visible = f.pages.stream().filter(Node::isVisible).count();
                return "entries=" + f.nav.entries() + " visible=" + visible + " current=" + f.pages.indexOf(
                        f.pages.stream().filter(Node::isVisible).findFirst().orElseThrow());
            }));
        }
        assertEquals(states.get(0), states.get(1), "FULL vs REDUCED");
        assertEquals(states.get(0), states.get(2), "FULL vs OFF");
        assertEquals("entries=3 visible=1 current=2", states.get(0));
        assertFalse(states.get(0).isEmpty());
    }
}
