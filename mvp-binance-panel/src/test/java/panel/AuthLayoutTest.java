package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.authview.AuthLayout;
import panel.authview.BrandFieldModel;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Brand field idêntico à referência (P3.20) e layout de entrada por breakpoint (handoff §3/§4). */
class AuthLayoutTest {
    private static double[] p(BrandFieldModel m, int node) {
        return BrandFieldModel.position(m.nodes().get(node), 0);
    }

    private static String r4(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    @Test
    void modelMatchesTheReferenceBitForBit() {
        BrandFieldModel m = new BrandFieldModel();
        assertEquals(42, m.nodes().size());
        assertEquals(60, m.triangles().size());
        assertEquals(101, m.edges().size());
        assertEquals(80, m.stars().size());
        // BYX_BRAND.svg() da referência: primeiro e último triângulo, primeira e última aresta (t = 0)
        var first = m.triangles().get(0);
        assertEquals("M-0.1387 -0.1000L0.1004 -0.0792L-0.0495 0.1579Z", path(m, first));
        assertEquals("0.041", String.format(java.util.Locale.ROOT, "%.3f", first.fa()));
        assertEquals("M1.0495 0.8319L1.0890 1.1031L0.9373 1.0846Z", path(m, m.triangles().get(59)));
        int[] e0 = m.edges().get(0);
        assertEquals("-0.1387 -0.1000 0.1004 -0.0792", r4(p(m, e0[0])[0]) + " " + r4(p(m, e0[0])[1]) + " " + r4(p(m, e0[1])[0]) + " " + r4(p(m, e0[1])[1]));
        int[] e100 = m.edges().get(100);
        assertEquals("1.0890 1.1031 0.9373 1.0846", r4(p(m, e100[0])[0]) + " " + r4(p(m, e100[0])[1]) + " " + r4(p(m, e100[1])[0]) + " " + r4(p(m, e100[1])[1]));
    }

    private static String path(BrandFieldModel m, BrandFieldModel.Triangle t) {
        int[] v = {t.a(), t.b(), t.c()};
        StringBuilder b = new StringBuilder("M");
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                b.append('L');
            }
            b.append(r4(p(m, v[i])[0])).append(' ').append(r4(p(m, v[i])[1]));
        }
        return b.append('Z').toString();
    }

    @Test
    void barsCascadeOnTheMasterPhaseOnlyInFull() {
        assertEquals(0.9, BrandFieldModel.barOpacity(0, 0.0, true), 1e-6);
        assertEquals(1.0, BrandFieldModel.barOpacity(1, 0.56, true), 1e-6, "bar 2 peaks at p = 0.56 (capped at 1)");
        assertEquals(0.6, BrandFieldModel.barOpacity(1, 0.56, false), 1e-6, "REDUCED/OFF: base opacity");
        assertEquals(0.5, BrandFieldModel.advance(0, 4500, false), 1e-9, "9 s period");
        assertEquals(0.0, BrandFieldModel.advance(0.5, 2250, true), 1e-9, "4.5 s while loading, wraps");
    }

    private static final class Fixture {
        final MotionService motion = new MotionService();
        final AuthLayout layout;
        final Stage stage = new Stage();

        Fixture(MotionPreference p, int w, int h, boolean show) {
            motion.preference.set(p);
            layout = new AuthLayout(motion, null, () -> { });
            layout.show("Sign in", List.of(new Label("Sign in"), new Label("Access the BYX-MVP terminal.")));
            Scene s = new Scene(layout, w, h);
            ByxTheme.apply(s);
            stage.setScene(s);
            if (show) {
                stage.show();
                layout.brand().setFocusOverride(Boolean.TRUE); // o foco real da janela depende do desktop de quem roda o teste
            }
            layout.applyCss();
            layout.layout();
        }

        void close() {
            layout.dispose();
            stage.close();
        }
    }

    @Test
    void paneAndFormFollowTheBreakpointContract() throws Exception {
        int[][] sizes = {{1440, 900, 920, 520, 392, 984}, {1600, 1000, 1040, 560, 432, 1104}, {1920, 1080, 1280, 640, 448, 1376}};
        for (int[] s : sizes) {
            double[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(MotionPreference.OFF, s[0], s[1], false);
                Bounds brand = f.layout.brand().localToScene(f.layout.brand().getLayoutBounds());
                Bounds pane = f.layout.pane().localToScene(f.layout.pane().getLayoutBounds());
                Bounds form = f.layout.formHost().localToScene(f.layout.formHost().getLayoutBounds());
                double[] out = {brand.getWidth(), pane.getMinX(), pane.getWidth(), form.getWidth(), form.getMinX()};
                f.close();
                return out;
            });
            String at = s[0] + "x" + s[1];
            assertEquals(s[2], r[0], 0.5, at + " brand region");
            assertEquals(s[2], r[1], 0.5, at + " pane x");
            assertEquals(s[3], r[2], 0.5, at + " pane width");
            assertEquals(s[4], r[3], 0.5, at + " form width");
            assertEquals(s[5], r[4], 0.5, at + " form x (pane x + padding)");
        }
    }

    @Test
    void topRowAndFooterSitAtThePaneEdges() throws Exception {
        double[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF, 1440, 900, false);
            var top = f.layout.lookup(".byx-auth-top");
            var foot = f.layout.lookup(".byx-auth-footer");
            Bounds t = top.localToScene(top.getLayoutBounds());
            Bounds b = foot.localToScene(foot.getLayoutBounds());
            f.close();
            return new double[] {t.getMinY(), b.getMaxY(), t.getMinX(), b.getMinX()};
        });
        assertEquals(24, r[0], 2, ".ptop top 24");
        assertEquals(900 - 22, r[1], 2, ".pfoot bottom 22");
        assertEquals(920 + 64, r[2], 0.5, "top row at the pane padding");
        assertEquals(920 + 64, r[3], 0.5, "footer at the pane padding");
    }

    @Test
    void brandFieldRunsPerModeAndStopsWhenHidden() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            boolean[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 1440, 900, true);
                boolean running = f.layout.brand().running();
                f.stage.hide();
                boolean afterHide = f.layout.brand().running();
                f.close();
                return new boolean[] {running, afterHide};
            });
            assertEquals(p != MotionPreference.OFF, r[0], p + " loop exists (FULL live, REDUCED glow breath, OFF static frame)");
            assertFalse(r[1], p + " no loop while the window is hidden");
        }
    }

    @Test
    void offDrawsOneStaticFrameAtTheReferencePhase() throws Exception {
        double[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF, 1440, 900, true);
            double phase = f.layout.brand().phase();
            double bar = f.layout.brand().barOpacity(0);
            int frames = f.layout.brand().frames();
            f.close();
            return new double[] {phase, bar, frames};
        });
        assertEquals(BrandFieldModel.STATIC_PHASE, r[0], 1e-9);
        assertEquals(0.9, r[1], 1e-6, "static bars at base opacity");
        assertEquals(0, r[2], "nothing runs in OFF");
    }

    @Test
    void modeChangeReevaluatesImmediately() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, 1440, 900, true);
            boolean full = f.layout.brand().running();
            f.motion.preference.set(MotionPreference.OFF);
            boolean off = f.layout.brand().running();
            f.motion.preference.set(MotionPreference.REDUCED);
            boolean reduced = f.layout.brand().running();
            f.close();
            return new boolean[] {full, off, reduced};
        });
        assertTrue(r[0]);
        assertFalse(r[1]);
        assertTrue(r[2]);
    }
}
