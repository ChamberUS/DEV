package panel.tradeview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import panel.model.TraderSnapshot;

/**
 * Medição de custo de atualização do Desk (manual; fora do surefire): 1000 ticks com book, trades, candles e
 * uPnL mudando, por breakpoint. Registra tempo de update, tempo de CSS+layout por tick e nós. Sem benchmark
 * sintético além disso. Uso: java ... panel.tradeview.DeskPerfQa saida.txt
 */
public final class DeskPerfQa {
    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        for (int[] size : new int[][] {{1440, 900}, {1600, 1000}, {1920, 1080}}) {
            DeskHarness.fx(() -> {
                DeskHarness d = DeskHarness.open(size[0], size[1], DeskFixtures.liveWithAccount(0));
                for (int i = 0; i < 50; i++) { // aquecimento
                    d.show(DeskFixtures.liveWithAccount(i));
                }
                int ticks = 400;
                long[] update = new long[ticks];
                long[] pulse = new long[ticks];
                int nodes = d.desk.nodeCount();
                for (int i = 0; i < ticks; i++) {
                    TraderSnapshot t = DeskFixtures.liveWithAccount(1000 + i);
                    t.positionRows.getFirst()[5] = String.format("%+.2f", i * 0.01);
                    d.current = t;
                    long a = System.nanoTime();
                    d.desk.onSnapshot(null);
                    long b = System.nanoTime();
                    d.layout();
                    long c = System.nanoTime();
                    update[i] = b - a;
                    pulse[i] = c - b;
                }
                java.util.Arrays.sort(update);
                java.util.Arrays.sort(pulse);
                out.add(String.format("%dx%d mode=%s ticks=%d nodes=%d->%d update p50=%.2fms p95=%.2fms max=%.2fms | css+layout p50=%.2fms p95=%.2fms max=%.2fms",
                        size[0], size[1], d.desk.mode(), ticks, nodes, d.desk.nodeCount(), update[ticks / 2] / 1e6, update[ticks * 95 / 100] / 1e6,
                        update[ticks - 1] / 1e6, pulse[ticks / 2] / 1e6, pulse[ticks * 95 / 100] / 1e6, pulse[ticks - 1] / 1e6));
                // poll sem mudança (o caso comum hoje): custo de um snapshot idêntico
                long[] idle = new long[ticks];
                for (int i = 0; i < ticks; i++) {
                    d.current = DeskFixtures.liveWithAccount(5);
                    long a = System.nanoTime();
                    d.desk.onSnapshot(null);
                    idle[i] = System.nanoTime() - a;
                }
                java.util.Arrays.sort(idle);
                out.add(String.format("%dx%d unchanged poll update p50=%.3fms p95=%.3fms", size[0], size[1], idle[ticks / 2] / 1e6, idle[ticks * 95 / 100] / 1e6));
                out.add(String.format("%dx%d loops=%d applied=%d skipped=%d", size[0], size[1], d.motion.runningLoops(), d.desk.applied(), d.desk.skipped()));
                d.close();
            });
        }
        Files.write(Path.of(args[0]), out);
        out.forEach(System.out::println);
        System.exit(0);
    }
}
