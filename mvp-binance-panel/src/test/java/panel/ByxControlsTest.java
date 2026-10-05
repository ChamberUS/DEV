package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.design.ByxTheme;
import panel.design.ByxToggle;
import panel.design.DesignTokens;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Controles base V2: estados, motion por modo e regras de QA do handoff (§6). */
class ByxControlsTest {
    private static MotionService motion(MotionPreference p) {
        MotionService m = new MotionService();
        m.preference.set(p);
        return m;
    }

    private static VBox themed(javafx.scene.Node... nodes) {
        VBox root = new VBox(nodes);
        Scene s = new Scene(root, 600, 400);
        ByxTheme.apply(s);
        root.applyCss();
        root.layout();
        return root;
    }

    private static void mouse(javafx.scene.Node n, javafx.event.EventType<MouseEvent> type) {
        Event.fireEvent(n, new MouseEvent(type, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false, false, false, true,
                false, false, false, false, false, null));
    }

    @Test
    void loadingButtonSendsOneRequest() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            int[] r = FxSupport.fx(() -> {
                AtomicInteger sent = new AtomicInteger();
                ByxButton b = new ByxButton("Sign in", ByxButton.Variant.PRIMARY, motion(p)).wide();
                themed(b);
                b.setOnAction(e -> {
                    sent.incrementAndGet();
                    b.setLoading(true);
                });
                b.fire();
                b.fire(); // duplo clique durante o carregamento
                int during = sent.get();
                boolean disabledWhileLoading = b.isDisabled();
                boolean spinning = b.spinner().spinning();
                var spinner = b.spinner();
                b.setLoading(false);
                return new int[] {during, disabledWhileLoading ? 1 : 0, spinning ? 1 : 0, spinner.spinning() ? 1 : 0,
                        b.isDisabled() ? 1 : 0, b.spinner() == null ? 1 : 0};
            });
            assertEquals(1, r[0], p + " requests");
            assertEquals(1, r[1], p + " disabled while loading");
            assertEquals(p != MotionPreference.OFF ? 1 : 0, r[2], p + " spinner (loading token: static in OFF)");
            assertEquals(0, r[3], p + " spinner stopped with the state");
            assertEquals(0, r[4], p + " enabled again");
            assertEquals(1, r[5], p + " spinner removed");
        }
    }

    @Test
    void disabledButtonUsesTokensNotFade() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            ByxButton b = new ByxButton("Save", ByxButton.Variant.PRIMARY, null);
            b.setDisable(true);
            themed(b);
            return new Object[] {b.getOpacity(), b.getBackground().getFills().get(0).getFill(), b.getTextFill()};
        });
        DesignTokens t = DesignTokens.get();
        assertEquals(1.0, (double) r[0], 0.001);
        assertEquals(t.color("colors.surface.bg3"), r[1]);
        assertEquals(t.color("colors.text.tertiary"), r[2]);
    }

    @Test
    void hoverAndPressMoveOnlyInFull() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            Object[] r = FxSupport.fx(() -> {
                ByxButton b = new ByxButton("Go", ByxButton.Variant.SECONDARY, motion(p));
                themed(b);
                mouse(b, MouseEvent.MOUSE_ENTERED);
                mouse(b, MouseEvent.MOUSE_PRESSED);
                return new Object[] {b.getProperties().get("byx.motion.hover"), b.getProperties().get("byx.motion.press"),
                        b.getTranslateY(), b.getScaleX()};
            });
            if (p == MotionPreference.FULL) {
                assertNotNull(r[0], "hover lift animates");
                assertNotNull(r[1], "press scale animates");
            } else {
                assertNull(r[0], p + " no hover movement");
                assertNull(r[1], p + " no press movement");
                assertEquals(0.0, (double) r[2], 0.001);
                assertEquals(1.0, (double) r[3], 0.001);
            }
        }
    }

    @Test
    void fieldErrorIsIconPlusTextAndRevealSharesValue() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            ByxField f = ByxField.password("Password");
            themed(f);
            f.input().setText("s3cret");
            f.setRevealed(true);
            TextFieldValue v = new TextFieldValue(f);
            f.setError("Incorrect email or password.");
            boolean pseudo = f.input().getPseudoClassStates().contains(PseudoClass.getPseudoClass("error"));
            f.setRevealed(false);
            boolean revealedAfter = f.isRevealed();
            f.setError(null);
            return new Object[] {f.labelText(), v.visibleText, pseudo, revealedAfter, f.hasError(),
                    f.input().getPseudoClassStates().contains(PseudoClass.getPseudoClass("error"))};
        });
        assertEquals("PASSWORD", r[0]);
        assertEquals("s3cret", r[1], "reveal shows the same value");
        assertTrue((boolean) r[2]);
        assertFalse((boolean) r[3]);
        assertFalse((boolean) r[4]);
        assertFalse((boolean) r[5]);
    }

    /** Lê o texto do campo revelado sem expor API extra no controle. */
    private static final class TextFieldValue {
        final String visibleText;

        TextFieldValue(ByxField f) {
            String t = null;
            for (javafx.scene.Node n : f.lookupAll(".byx-input")) {
                if (n.isVisible() && n instanceof javafx.scene.control.TextField tf && !(n instanceof javafx.scene.control.PasswordField)) {
                    t = tf.getText();
                }
            }
            visibleText = t;
        }
    }

    @Test
    void fieldErrorRowHasIcon() throws Exception {
        int icons = FxSupport.fx(() -> {
            ByxField f = ByxField.text("Email");
            themed(f);
            f.setError("Enter a valid email.");
            return f.lookupAll(".byx-field-error .byx-icon").size();
        });
        assertEquals(1, icons);
    }

    @Test
    void toggleSlidesOnlyInFull() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            double[] r = FxSupport.fx(() -> {
                ByxToggle t = new ByxToggle(motion(p), "Compact density");
                themed(t);
                Event.fireEvent(t, new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
                return new double[] {t.isSelected() ? 1 : 0, t.thumbOffset()};
            });
            assertEquals(1, r[0], p + " space toggles");
            if (p == MotionPreference.FULL) {
                assertTrue(r[1] < 18, "FULL slides over toggleSwitch");
            } else {
                assertEquals(18, r[1], 0.001, p + " jumps (translate=false)");
            }
        }
    }

    @Test
    void checkboxUsesAccentWhenSelected() throws Exception {
        Color fill = FxSupport.fx(() -> {
            CheckBox c = new CheckBox("I agree");
            c.getStyleClass().add("byx-check");
            c.setSelected(true);
            themed(c);
            Region box = (Region) c.lookup(".box");
            return (Color) box.getBackground().getFills().get(0).getFill();
        });
        assertEquals(DesignTokens.get().color("colors.accent.trading"), fill);
    }

    @Test
    void bannerTextMeetsContrastOnItsTint() throws Exception {
        for (ByxBanner.Kind k : ByxBanner.Kind.values()) {
            Color[] c = FxSupport.fx(() -> {
                ByxBanner b = new ByxBanner(k, "Title", "Body text");
                themed(b);
                Label text = (Label) b.lookup(".byx-banner-text");
                return new Color[] {(Color) text.getTextFill(), (Color) b.getBackground().getFills().get(0).getFill()};
            });
            Color bg = DesignTokens.get().color("colors.surface.bg1");
            Color tint = c[1];
            Color composite = new Color(tint.getRed() * tint.getOpacity() + bg.getRed() * (1 - tint.getOpacity()),
                    tint.getGreen() * tint.getOpacity() + bg.getGreen() * (1 - tint.getOpacity()),
                    tint.getBlue() * tint.getOpacity() + bg.getBlue() * (1 - tint.getOpacity()), 1);
            double ratio = contrast(c[0], composite);
            assertTrue(ratio >= 4.5, k + " contrast " + ratio);
        }
    }

    @Test
    void availabilityAndDataBadgesAlwaysCarryIconAndText() throws Exception {
        int missing = FxSupport.fx(() -> {
            int n = 0;
            for (ByxBadge.Availability a : ByxBadge.Availability.values()) {
                Label l = ByxBadge.availability(a);
                if (l.getGraphic() == null || l.getText().isBlank()) {
                    n++;
                }
            }
            for (ByxBadge.Data d : ByxBadge.Data.values()) {
                Label l = ByxBadge.data(d);
                if (l.getGraphic() == null || l.getText().isBlank()) {
                    n++;
                }
            }
            return n;
        });
        assertEquals(0, missing);
    }

    private static double luminance(Color c) {
        double[] v = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) {
            v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
