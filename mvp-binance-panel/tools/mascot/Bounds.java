import java.io.*;
import java.util.*;

/** Estatísticas em resolução cheia (gray 1024) por intervalo de frames: bbox da tinta (<235) e centróide da tinta ESCURA (corpo, <60). Uso: ffmpeg ... gray rawvideo | java Bounds.java */
public class Bounds {
    public static void main(String[] a) throws Exception {
        int W = 1024; byte[] buf = new byte[W * W];
        int[][] ranges = {{0,72},{73,150},{151,252},{253,324},{325,390},{391,522},{523,689},{690,800},{801,935},{936,1121},{1122,1266},{1267,1367}};
        double[][] st = new double[ranges.length][6]; // minx,miny,maxx,maxy, sumcx, sumcy
        int[] cnt = new int[ranges.length];
        for (double[] s : st) { s[0] = 9999; s[1] = 9999; s[2] = -1; s[3] = -1; }
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1 << 20));
        for (int f = 0; f < 1368; f++) {
            in.readFully(buf);
            int r = -1; for (int i = 0; i < ranges.length; i++) if (f >= ranges[i][0] && f <= ranges[i][1]) r = i;
            if (r < 0) continue;
            long sx = 0, sy = 0, c = 0; int minx = 9999, miny = 9999, maxx = -1, maxy = -1;
            for (int y = 0; y < W; y++) for (int x = 0; x < W; x++) {
                int v = buf[y * W + x] & 255;
                if (v < 235) { if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y; }
                if (v < 60) { sx += x; sy += y; c++; }
            }
            if (maxx < 0) continue;
            double[] s = st[r];
            s[0] = Math.min(s[0], minx); s[1] = Math.min(s[1], miny); s[2] = Math.max(s[2], maxx); s[3] = Math.max(s[3], maxy);
            if (c > 0) { s[4] += sx / (double) c; s[5] += sy / (double) c; cnt[r]++; }
        }
        for (int i = 0; i < ranges.length; i++)
            System.out.printf(Locale.ROOT, "%4d-%4d bbox x[%4.0f..%4.0f] y[%4.0f..%4.0f] bodyCentroid(%.0f,%.0f)%n", ranges[i][0], ranges[i][1], st[i][0], st[i][2], st[i][1], st[i][3], st[i][4] / Math.max(cnt[i], 1), st[i][5] / Math.max(cnt[i], 1));
    }
}
