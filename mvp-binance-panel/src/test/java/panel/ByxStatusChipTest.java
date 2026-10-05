package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import panel.design.ByxStatusChip;
import panel.design.ByxTheme;
import panel.design.DesignTokens;
import panel.design.StatusState;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Chips e pontos de estado: cores do token, texto sempre, pulso só onde e quando o contrato permite. */
class ByxStatusChipTest {
    private static ByxStatusChip mounted(StatusState s, boolean expected, MotionService m, VBox root) {
        ByxStatusChip c = new ByxStatusChip(StatusState.UNKNOWN, m);
        root.getChildren().add(c);
        c.setState(s, expected);
        return c;
    }

    private static VBox root() {
        VBox root = new VBox();
        ByxTheme.apply(new Scene(root, 400, 400));
        return root;
    }

    @Test
    void enumMatchesTokens() {
        assertEquals(DesignTokens.get().list("statusStates.states"),
                List.of(StatusState.values()).stream().map(Enum::name).toList());
    }

    @Test
    void chipColorsComeFromTokens() throws Exception {
        JsonNode colors = DesignTokens.get().node("statusStates.colors");
        for (StatusState s : StatusState.values()) {
            for (boolean expected : new boolean[] {false, true}) {
                if (expected && s != StatusState.UNAVAILABLE) {
                    continue;
                }
                Color[] got = FxSupport.fx(() -> {
                    VBox root = root();
                    ByxStatusChip c = mounted(s, expected, null, root);
                    root.applyCss();
                    Label t = (Label) c.lookup(".byx-chip-text");
                    var fills = c.getBackground() == null ? null : c.getBackground().getFills();
                    return new Color[] {(Color) t.getTextFill(), fills == null || fills.isEmpty() ? Color.TRANSPARENT : (Color) fills.get(0).getFill()};
                });
                JsonNode spec = colors.path(expected ? "UNAVAILABLE_EXPECTED" : s.name());
                assertEquals(Color.web(spec.path("text").asText()), got[0], s + " text");
                String fill = spec.path("fill").asText();
                assertEquals("transparent".equals(fill) ? Color.TRANSPARENT : Color.web(fill), got[1], s + " fill");
            }
        }
    }

    @Test
    void textIsAlwaysPresent() throws Exception {
        for (StatusState s : StatusState.values()) {
            String text = FxSupport.fx(() -> mounted(s, false, null, root()).text());
            assertEquals(s.name(), text);
        }
    }

    @Test
    void pulseOnlyWhileConnectingInFull() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            for (StatusState s : StatusState.values()) {
                boolean pulsing = FxSupport.fx(() -> {
                    MotionService m = new MotionService();
                    m.preference.set(p);
                    return mounted(s, false, m, root()).dot().pulsing();
                });
                assertEquals(p == MotionPreference.FULL && s.pulses(), pulsing, p + " " + s);
            }
        }
    }

    @Test
    void pulseIsRemovedWithStateAndNeverAccumulates() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            MotionService m = new MotionService();
            VBox root = root();
            ByxStatusChip c = mounted(StatusState.CONNECTING, false, m, root);
            Object first = pulseOf(c);
            for (int i = 0; i < 20; i++) {
                c.setState(i % 2 == 0 ? StatusState.RECONNECTING : StatusState.CONNECTING);
            }
            boolean sameLoop = pulseOf(c) == first;
            c.setState(StatusState.OPERATIONAL);
            boolean stoppedWithState = !c.dot().pulsing() && c.dot().getOpacity() == 1;
            c.setState(StatusState.RECONNECTING);
            boolean back = c.dot().pulsing();
            m.preference.set(MotionPreference.REDUCED);
            boolean stoppedByMode = !c.dot().pulsing();
            m.preference.set(MotionPreference.FULL);
            boolean resumed = c.dot().pulsing();
            root.getChildren().clear();
            boolean stoppedOffScene = !c.dot().pulsing();
            return new boolean[] {sameLoop, stoppedWithState, back, stoppedByMode, resumed, stoppedOffScene};
        });
        assertTrue(r[0], "one loop across CONNECTING/RECONNECTING");
        assertTrue(r[1], "removed with the state");
        assertTrue(r[2]);
        assertTrue(r[3], "REDUCED has no statePulse");
        assertTrue(r[4]);
        assertTrue(r[5], "leaving the scene stops the pulse");
    }

    @Test
    void scaleOnChangeOnlyInFull() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            double scale = FxSupport.fx(() -> {
                MotionService m = new MotionService();
                m.preference.set(p);
                ByxStatusChip c = mounted(StatusState.CONNECTING, false, m, root());
                c.setState(StatusState.OPERATIONAL);
                return c.getScaleX();
            });
            if (p == MotionPreference.FULL) {
                assertTrue(scale < 1, "FULL starts at .98");
            } else {
                assertEquals(1, scale, 0.0001, p.name());
            }
        }
    }

    @Test
    void discardedDotsAreNotRetainedByMotionService() throws Exception {
        MotionService m = new MotionService(); // vive além do chip, como no app
        java.lang.ref.WeakReference<?> ref = FxSupport.fx(() -> {
            ByxStatusChip c = new ByxStatusChip(StatusState.OPERATIONAL, m);
            StackPane holder = new StackPane(c);
            holder.getChildren().clear();
            java.lang.ref.WeakReference<?> w = new java.lang.ref.WeakReference<>(c);
            return w;
        });
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertFalse(ref.get() != null, "chip retained after discard");
        assertEquals(MotionPreference.FULL, m.preference.get());
    }

    private static Object pulseOf(ByxStatusChip c) {
        try {
            var f = c.dot().getClass().getDeclaredMethod("pulse");
            f.setAccessible(true);
            return f.invoke(c.dot());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
