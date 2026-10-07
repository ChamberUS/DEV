package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.authview.AuthLayout;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** O fundo decorativo do login só anima visível e com foco, num relógio de baixa frequência, e retoma sem salto. */
class BrandPanelTest {
    private static final class Fixture {
        final MotionService motion = new MotionService();
        final AuthLayout layout;
        final Stage stage = new Stage();

        Fixture(MotionPreference p) {
            motion.preference.set(p);
            layout = new AuthLayout(motion, null, () -> { });
            layout.show("Sign in", List.of(new Label("Sign in")));
            Scene s = new Scene(layout, 1440, 900);
            ByxTheme.apply(s);
            stage.setScene(s);
            stage.show();
            layout.brand().setFocusOverride(Boolean.TRUE);
        }

        void close() {
            layout.dispose();
            stage.close();
        }
    }

    @Test
    void pausesWithoutFocusAndResumesFromTheSamePhase() throws Exception {
        Fixture[] f = new Fixture[1];
        FxSupport.fx(() -> { f[0] = new Fixture(MotionPreference.FULL); return null; });
        Thread.sleep(900);
        double[] before = FxSupport.fx(() -> {
            assertTrue(f[0].layout.brand().running());
            f[0].layout.brand().setFocusOverride(Boolean.FALSE);
            return new double[] {f[0].layout.brand().phase(), f[0].layout.brand().frames(), f[0].layout.brand().running() ? 1 : 0};
        });
        assertEquals(0, before[2], "sem foco: nenhum relógio");
        assertTrue(before[1] > 0, "FULL desenhou quadros antes de pausar");
        Thread.sleep(600);
        double[] paused = FxSupport.fx(() -> new double[] {f[0].layout.brand().phase(), f[0].layout.brand().frames()});
        assertEquals(before[0], paused[0], 1e-12, "a fase não anda pausada");
        assertEquals(before[1], paused[1], "nenhum quadro pausado");
        double afterResume = FxSupport.fx(() -> {
            f[0].layout.brand().setFocusOverride(Boolean.TRUE);
            return f[0].layout.brand().phase();
        });
        assertEquals(before[0], afterResume, 1e-12, "retoma exatamente da fase em que parou (sem salto)");
        FxSupport.fx(() -> { f[0].close(); return null; });
    }

    @Test
    void fullRunsAtLowRateNotAtDisplayRate() throws Exception {
        Fixture[] f = new Fixture[1];
        FxSupport.fx(() -> { f[0] = new Fixture(MotionPreference.FULL); return null; });
        int a = FxSupport.fx(() -> { return f[0].layout.brand().frames(); });
        Thread.sleep(2000);
        int b = FxSupport.fx(() -> { return f[0].layout.brand().frames(); });
        FxSupport.fx(() -> { f[0].close(); return null; });
        int perSecond = (b - a) / 2;
        assertTrue(perSecond >= 1, "FULL anima (>= 1 quadro/s), foi " + perSecond);
        assertTrue(perSecond <= 6, "FULL fica longe de 30/60 quadros/s, foi " + perSecond);
    }

    @Test
    void reducedBreathesSlowerAndOffNeverTicks() throws Exception {
        for (MotionPreference m : new MotionPreference[] {MotionPreference.REDUCED, MotionPreference.OFF}) {
            Fixture[] f = new Fixture[1];
            FxSupport.fx(() -> { f[0] = new Fixture(m); return null; });
            Thread.sleep(1500);
            int frames = FxSupport.fx(() -> { return f[0].layout.brand().frames(); });
            FxSupport.fx(() -> { f[0].close(); return null; });
            if (m == MotionPreference.OFF) {
                assertEquals(0, frames, "OFF: estático");
            } else {
                assertTrue(frames > 0 && frames <= 3, "REDUCED: respiração lenta, foi " + frames);
            }
        }
    }

    @Test
    void minimizedWindowStopsTheClock() throws Exception {
        Fixture[] f = new Fixture[1];
        FxSupport.fx(() -> { f[0] = new Fixture(MotionPreference.FULL); return null; });
        boolean[] r = FxSupport.fx(() -> {
            boolean running = f[0].layout.brand().running();
            f[0].stage.setIconified(true);
            return new boolean[] {running, f[0].layout.brand().running()};
        });
        FxSupport.fx(() -> { f[0].close(); return null; });
        assertTrue(r[0]);
        assertFalse(r[1], "minimizada: relógio parado");
    }
}
