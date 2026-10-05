package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import panel.design.ByxButton;
import panel.design.ByxRegion;
import panel.design.ByxTheme;
import panel.design.DesignTokens;
import panel.design.RegionState;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Contrato de 10 estados (P3.10): estados permitidos, LOADING finito, STALE nunca vivo, timers com token. */
class ByxRegionTest {
    private static MotionService motion(MotionPreference p) {
        MotionService m = new MotionService();
        m.preference.set(p);
        return m;
    }

    private static ByxRegion mounted(ByxRegion r) {
        VBox root = new VBox(r);
        ByxTheme.apply(new Scene(root, 600, 400));
        root.applyCss();
        return r;
    }

    private static ByxRegion all(MotionService m) {
        ByxRegion r = new ByxRegion("Gallery", EnumSet.allOf(RegionState.class), m);
        r.setContent(new Label("42.00"));
        return mounted(r);
    }

    private static void waitFx(long ms) throws Exception {
        Thread.sleep(ms);
        FxSupport.fx(() -> { });
    }

    @Test
    void enumMatchesTokens() {
        assertEquals(DesignTokens.get().list("regionStates"), List.of(RegionState.values()).stream().map(Enum::name).toList());
    }

    @Test
    void everyStateRendersInEveryMode() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            for (RegionState s : RegionState.values()) {
                RegionState shown = FxSupport.fx(() -> {
                    ByxRegion r = all(motion(p));
                    r.setOnRetry(() -> { });
                    r.setState(s, ByxRegion.Detail.of("Title", "Text"));
                    r.applyCss();
                    r.layout();
                    RegionState out = r.getChildren().size() == 1 ? r.state() : null;
                    r.dispose();
                    return out;
                });
                assertEquals(s, shown, p + " " + s);
            }
        }
    }

    @Test
    void rejectsStatesTheComponentCannotBeIn() throws Exception {
        FxSupport.fx(() -> {
            ByxRegion holdout = new ByxRegion("Final holdout", EnumSet.of(RegionState.LOCKED), null);
            holdout.setState(RegionState.LOCKED, ByxRegion.Detail.of("Sealed", "FINAL_HOLDOUT stays sealed."));
            assertThrows(IllegalArgumentException.class, () -> holdout.setState(RegionState.READY));
        });
        assertThrows(IllegalArgumentException.class,
                () -> new ByxRegion("Bad", EnumSet.of(RegionState.READY, RegionState.LOADING), null));
    }

    @Test
    void loadingIsNeverInfiniteInAnyMode() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            ByxRegion r = FxSupport.fx(() -> {
                ByxRegion x = all(motion(p));
                x.setOnRetry(() -> { });
                x.setLoadingThresholds(Duration.millis(80), Duration.millis(200));
                x.setState(RegionState.LOADING);
                return x;
            });
            waitFx(130);
            assertTrue(FxSupport.fx(r::slowNoteVisible), p + " slow note after threshold");
            assertEquals(RegionState.LOADING, FxSupport.fx(r::state));
            waitFx(200);
            assertEquals(RegionState.ERROR, FxSupport.fx(r::state), p + " timeout becomes ERROR");
            FxSupport.fx(r::dispose);
        }
    }

    @Test
    void defaultThresholdsComeFromBehaviourTokens() throws Exception {
        boolean slow = FxSupport.fx(() -> {
            ByxRegion r = all(motion(MotionPreference.FULL));
            r.setState(RegionState.LOADING);
            boolean v = r.slowNoteVisible();
            r.dispose();
            return v;
        });
        assertFalse(slow);
        assertEquals(8000, DesignTokens.get().number("behaviour.loadingSlowAfterMs"));
        assertEquals(20000, DesignTokens.get().number("behaviour.loadingTimeoutAfterMs"));
    }

    @Test
    void timerFromAnEarlierStateDoesNothing() throws Exception {
        ByxRegion r = FxSupport.fx(() -> {
            ByxRegion x = all(motion(MotionPreference.FULL));
            x.setLoadingThresholds(Duration.millis(60), Duration.millis(120));
            x.setState(RegionState.LOADING);
            x.setState(RegionState.READY); // dados chegaram
            return x;
        });
        waitFx(250);
        assertEquals(RegionState.READY, FxSupport.fx(r::state));
        // novo LOADING reinicia o relógio: o timer antigo não encurta o novo
        FxSupport.fx(() -> {
            r.setLoadingThresholds(Duration.millis(60), Duration.millis(400));
            r.setState(RegionState.LOADING);
        });
        waitFx(150);
        assertEquals(RegionState.LOADING, FxSupport.fx(r::state));
        FxSupport.fx(r::dispose);
    }

    @Test
    void retryRunsTheCallerAction() throws Exception {
        int calls = FxSupport.fx(() -> {
            AtomicInteger n = new AtomicInteger();
            ByxRegion r = all(motion(MotionPreference.OFF));
            r.setOnRetry(n::incrementAndGet);
            r.setState(RegionState.ERROR);
            ((ByxButton) r.lookup(".byx-btn")).fire();
            return n.get();
        });
        assertEquals(1, calls);
    }

    @Test
    void notConfiguredShowsSetupOnlyWhenReal() throws Exception {
        int[] r = FxSupport.fx(() -> {
            ByxRegion a = all(null);
            a.setState(RegionState.NOT_CONFIGURED);
            ByxRegion b = all(null);
            b.setState(RegionState.NOT_CONFIGURED, ByxRegion.Detail.of(null, null).withAction("Set up", () -> { }));
            ByxRegion c = all(null);
            c.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of(null, null).withAction("Retry", () -> { }));
            return new int[] {a.lookupAll(".byx-btn").size(), b.lookupAll(".byx-btn").size(), c.lookupAll(".byx-btn").size()};
        });
        assertEquals(0, r[0], "no fake setup action");
        assertEquals(1, r[1]);
        assertEquals(0, r[2], "UNAVAILABLE makes no retry promise");
    }

    @Test
    void staleKeepsValueDimmedWithLastUpdate() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            ByxRegion x = all(motion(MotionPreference.FULL));
            x.setState(RegionState.READY);
            x.setState(RegionState.STALE, ByxRegion.Detail.of(null, "14:02:11"));
            boolean keptValue = x.lookupAll(".label").stream().anyMatch(n -> "42.00".equals(((Label) n).getText()));
            return new Object[] {keptValue, x.lastUpdateText(), x.lookup(".byx-stale-chip") != null,
                    x.getPseudoClassStates().stream().anyMatch(pc -> pc.getPseudoClassName().equals("stale"))};
        });
        assertTrue((boolean) r[0]);
        assertEquals("LAST UPDATE 14:02:11", r[1]);
        assertTrue((boolean) r[2]);
        assertTrue((boolean) r[3]);
        double dim = FxSupport.fx(() -> {
            ByxRegion x = all(null);
            x.setState(RegionState.STALE);
            return x.lookupAll(".label").stream().filter(n -> "42.00".equals(((Label) n).getText())).findFirst()
                    .orElseThrow().getParent().getOpacity();
        });
        assertEquals(DesignTokens.get().number("behaviour.staleOpacity"), dim, 0.001);
    }

    @Test
    void shimmerOnlyInFullAndStopsWithState() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            boolean[] r = FxSupport.fx(() -> {
                ByxRegion x = all(motion(p));
                x.setState(RegionState.LOADING);
                boolean during = x.shimmering();
                x.setState(RegionState.READY);
                boolean after = x.shimmering();
                x.dispose();
                return new boolean[] {during, after};
            });
            assertEquals(p == MotionPreference.FULL, r[0], p + " regionLoading");
            assertFalse(r[1], p + " stops with LOADING");
        }
    }

    @Test
    void dataUpdatesInReadyDoNotReplayTheSwap() throws Exception {
        double opacity = FxSupport.fx(() -> {
            ByxRegion x = all(motion(MotionPreference.FULL));
            x.setState(RegionState.LOADING);
            x.setState(RegionState.READY);
            // fim da troca LOADING → READY
            x.getChildren().get(0).setOpacity(1);
            x.setState(RegionState.READY); // atualização de dados
            x.setContent(new Label("43.00"));
            return x.getChildren().get(0).getOpacity();
        });
        assertEquals(1, opacity, 0.0001);
    }
}
