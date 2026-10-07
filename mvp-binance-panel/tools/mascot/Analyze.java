import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Análise frame a frame do vídeo fonte (gray 128x128 via ffmpeg): área de tinta, centróide e diferença entre frames. Uso: java Analyze.java small.raw */
public class Analyze {
    public static void main(String[] a) throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(a[0]));
        int W = 128, n = raw.length / (W * W);
        int[] area = new int[n]; double[] cx = new double[n], cy = new double[n], diff = new double[n];
        for (int f = 0; f < n; f++) {
            long sx = 0, sy = 0; int c = 0;
            for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
                int v = raw[f * W * W + y * W + x] & 255;
                if (v < 200) { c++; sx += x; sy += y; }
                if (f > 0) diff[f] += Math.abs(v - (raw[(f - 1) * W * W + y * W + x] & 255));
            }
            area[f] = c; cx[f] = c == 0 ? 0 : sx / (double) c; cy[f] = c == 0 ? 0 : sy / (double) c; diff[f] /= W * W;
        }
        double mean = Arrays.stream(diff).average().orElse(0);
        System.out.printf("frames=%d meanDiff=%.3f%n", n, mean);
        System.out.println("# frames with diff > 6x mean (hard cuts / fast morphs)");
        for (int f = 1; f < n; f++) if (diff[f] > mean * 6) System.out.printf("f=%d t=%.3f diff=%.2f area=%d%n", f, f / 30.0, diff[f], area[f]);
        System.out.println("# runs of tiny ink (<260 px at 128 canvas)");
        int s = -1;
        for (int f = 0; f <= n; f++) {
            boolean tiny = f < n && area[f] < 260;
            if (tiny && s < 0) s = f;
            if (!tiny && s >= 0) { System.out.printf("tiny f=%d..%d t=%.2f..%.2f minArea=%d%n", s, f - 1, s / 30.0, f / 30.0, Arrays.stream(area, s, f).min().getAsInt()); s = -1; }
        }
        try (PrintWriter w = new PrintWriter("series.csv")) {
            w.println("frame,time,area,cx,cy,diff");
            for (int f = 0; f < n; f++) w.printf(Locale.ROOT, "%d,%.3f,%d,%.1f,%.1f,%.2f%n", f, f / 30.0, area[f], cx[f], cy[f], diff[f]);
        }
    }
}
