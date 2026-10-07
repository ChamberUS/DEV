import java.nio.file.*;
import java.util.*;

/** Procura pontos de loop: para cada intervalo, os pares (i,j) com j-i >= minLen em que a frame j ~ frame i (menor diferença média, gray 128). Uso: java Loops.java small.raw */
public class Loops {
    static byte[] raw; static final int W = 128, P = W * W;
    static double d(int a, int b) { long s = 0; for (int k = 0; k < P; k++) s += Math.abs((raw[a * P + k] & 255) - (raw[b * P + k] & 255)); return s / (double) P; }
    public static void main(String[] a) throws Exception {
        raw = Files.readAllBytes(Path.of(a[0]));
        Object[][] cands = {{"IDLE", 0, 72, 12}, {"THINKING", 79, 150, 20}, {"PROCESSING-dash", 158, 198, 15}, {"PROCESSING-tall", 199, 252, 15}, {"PROCESSING-all", 158, 252, 40}, {"ATTENTION-hold", 262, 324, 15}, {"NOTIF-hold", 335, 390, 15},
                {"SYNC-final", 1260, 1367, 60}, {"SYNC-first", 684, 800, 60}, {"COMET-2", 1122, 1270, 40}, {"COMET-1", 866, 940, 30}};
        for (Object[] c : cands) {
            int s = (int) c[1], e = (int) c[2], min = (int) c[3];
            List<double[]> r = new ArrayList<>();
            for (int i = s; i <= e; i++) for (int j = i + min; j <= e; j++) r.add(new double[] {d(i, j), i, j});
            r.sort(Comparator.comparingDouble(x -> x[0] - 0.002 * (x[2] - x[1])));
            System.out.print(c[0] + " [" + s + ".." + e + "] static-ref d(s,e)=" + String.format(Locale.ROOT, "%.2f", d(s, e)) + " -> ");
            for (int k = 0; k < Math.min(3, r.size()); k++) System.out.printf(Locale.ROOT, "(i=%d,j=%d len=%d diff=%.2f) ", (int) r.get(k)[1], (int) r.get(k)[2], (int) (r.get(k)[2] - r.get(k)[1]), r.get(k)[0]);
            System.out.println();
        }
    }
}
