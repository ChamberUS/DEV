package panel.researchview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import panel.model.CaptureInfo;
import panel.model.CaptureSnapshot.State;
import panel.model.Snapshot;
import panel.tradeview.DeskHarness;

/**
 * Custo de atualização de Research Overview e Capture (manual; fora do surefire): 400 atualizações com dados
 * mudando, por breakpoint; tempo de update, nós, loops e timers. Uso: java ... panel.researchview.ResearchPerfQa saida.txt
 */
public final class ResearchPerfQa {
    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        for (int[] size : new int[][] {{1440, 900}, {1920, 1080}}) {
            DeskHarness.fx(() -> {
                ResearchHarness h = ResearchHarness.open(size[0], size[1], ResearchFixtures.trainReady(34, true));
                int n = 400;
                long[] up = new long[n];
                long[] lay = new long[n];
                int nodes = h.overview.nodeCount();
                for (int i = 0; i < 50; i++) {
                    h.show(variant(i));
                }
                for (int i = 0; i < n; i++) {
                    h.source.snapshot = variant(1000 + i);
                    long a = System.nanoTime();
                    h.overview.onSnapshot(null);
                    long b = System.nanoTime();
                    h.layout();
                    up[i] = b - a;
                    lay[i] = System.nanoTime() - b;
                }
                Arrays.sort(up);
                Arrays.sort(lay);
                out.add(String.format("overview %dx%d nodes=%d->%d update p50=%.2fms p95=%.2fms max=%.2fms | css+layout p50=%.2fms p95=%.2fms loops=%d",
                        size[0], size[1], nodes, h.overview.nodeCount(), up[n / 2] / 1e6, up[n * 95 / 100] / 1e6, up[n - 1] / 1e6, lay[n / 2] / 1e6,
                        lay[n * 95 / 100] / 1e6, h.motion.runningLoops()));
                long[] idle = new long[n];
                for (int i = 0; i < n; i++) {
                    long a = System.nanoTime();
                    h.overview.onSnapshot(null);
                    idle[i] = System.nanoTime() - a;
                }
                Arrays.sort(idle);
                out.add(String.format("overview %dx%d unchanged poll p50=%.3fms p95=%.3fms applied=%d skipped=%d", size[0], size[1], idle[n / 2] / 1e6,
                        idle[n * 95 / 100] / 1e6, h.overview.applied(), h.overview.skipped()));
                h.close();
            });
            DeskHarness.fx(() -> {
                var clock = new DeskHarness.TestClock();
                var motion = new panel.motion.MotionService();
                CapturePanel p = new CapturePanel(motion, clock);
                var scene = new javafx.scene.Scene(p, size[0] - 68, size[1] - 94);
                panel.design.ByxTheme.apply(scene);
                p.start(() -> { });
                int n = 400;
                long[] up = new long[n];
                p.show(ResearchFixtures.capture(State.RUNNING));
                p.applyCss();
                p.layout();
                int nodes = p.nodeCount();
                for (int i = 0; i < n; i++) {
                    long a = System.nanoTime();
                    p.show(ResearchFixtures.capture(i % 9 == 0 ? State.STALE : State.RUNNING));
                    clock.now = clock.now.plusSeconds(1);
                    p.updateTimers();
                    up[i] = System.nanoTime() - a;
                    p.applyCss();
                    p.layout();
                }
                Arrays.sort(up);
                out.add(String.format("capture %dx%d nodes=%d->%d show+timers p50=%.3fms p95=%.3fms max=%.3fms loops=%d timerRunning=%s", size[0], size[1], nodes,
                        p.nodeCount(), up[n / 2] / 1e6, up[n * 95 / 100] / 1e6, up[n - 1] / 1e6, motion.runningLoops(), p.timerRunning()));
                p.stop();
                out.add(String.format("capture %dx%d after stop: loops=%d timerRunning=%s", size[0], size[1], motion.runningLoops(), p.timerRunning()));
            });
        }
        Files.write(Path.of(args[0]), out);
        out.forEach(System.out::println);
        System.exit(0);
    }

    private static Snapshot variant(int i) {
        Snapshot s = ResearchFixtures.trainReady(34, true);
        s.anchorCount = 209_478L + i;
        s.capture = new CaptureInfo("RUNNING", "CONNECTED", "Binance", "USD-M-FUTURES", "ETHUSDT", "s-active", "21:" + (10 + i % 40), null, null, null, null, null, null, null, null, null);
        if (i % 5 == 0) {
            s.warnings.add("warning " + i);
        }
        return s;
    }
}
