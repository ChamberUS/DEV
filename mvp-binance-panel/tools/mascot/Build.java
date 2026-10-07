import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Pipeline REPRODUZÍVEL dos assets do mascote: vídeo fonte (1024x1024, 30 fps, fundo branco) -> sprite sheets PNG com alfa real + posters + manifesto.
 * Passos por frame: (1) separa o fundo branco por componentes conexos (bordas e "bolsões" entre órbitas viram fundo; os OLHOS, cercados pelo corpo preto, ficam opacos);
 * (2) "color to alpha" contra o branco numa faixa de 3 px em volta do fundo (bordas suaves sem halo branco); (3) recorte quadrado fixo de 960 px, deslocado pelo centro ÓPTICO
 * do estado (centróide da tinta escura do poster, nunca o bbox); (4) redução por média de área em espaço PREMULTIPLICADO até 384 px; (5) loops com crossfade curto no fechamento;
 * (6) sprite sheet recortada no bbox da UNIÃO dos frames do estado (memória proporcional ao conteúdo). Uso: java -Xmx3g Build.java <source.mp4> <outDir> <sourceSha256>
 */
public class Build {
    static final int RING = 7;
    static final int SRC = 1024, CROP = 960, CROP0 = (SRC - CROP) / 2, OUT = 384, PAD = 4;

    record State(String name, int start, int end, boolean loop, int loopStart, int loopEnd, int xfade, int fps, int poster, int durationMs, int holdMs, String use) { }

    // Frames do vídeo fonte (verificados frame a frame; ver mascot-manifest.json "sourceSegments").
    static final List<State> STATES = List.of(
            new State("IDLE", 0, 72, true, 21, 71, 4, 15, 36, 0, 0, "estado neutro, vazio/assistente, galeria"),
            new State("THINKING", 79, 150, true, 95, 140, 0, 20, 100, 0, 0, "consulta deliberada, análise curta, espera local, CONNECTING"),
            new State("PROCESSING", 1266, 1367, true, 1266, 1368, 8, 24, 1300, 0, 0, "tarefa local longa, preparação de dados, operação assíncrona"),
            new State("SYNCING", 866, 940, true, 876, 919, 3, 24, 893, 0, 0, "chain catching up, reconexão/resync, sync de dados"),
            new State("ATTENTION", 253, 300, false, 0, 0, 0, 24, 300, 0, 800, "aviso não fatal; one-shot, depois volta ao estado anterior"),
            new State("NOTIFICATION", 325, 375, false, 0, 0, 0, 24, 375, 0, 1200, "novo resultado/tarefa concluída; one-shot"),
            new State("TRANSITION", 1240, 1266, false, 0, 0, 0, 30, 1266, 450, 0, "mudança importante de contexto/workspace; ~450 ms, não bloqueia"));

    public static void main(String[] a) throws Exception {
        String src = a[0]; Path out = Path.of(a[1]); String sha = a[2];
        Files.createDirectories(out.resolve("states")); Files.createDirectories(out.resolve("posters"));
        // passo 1: centro óptico por estado (centróide da tinta escura do poster)
        Map<Integer, double[]> centroid = new HashMap<>();
        Set<Integer> posterFrames = new TreeSet<>(); for (State s : STATES) posterFrames.add(s.poster);
        decode(src, posterFrames, (f, rgb) -> centroid.put(f, darkCentroid(rgb)));
        // passo 2: todos os frames necessários, já matte + recorte + redução (premultiplicado, 8 bits)
        Map<String, double[]> shift = new HashMap<>();
        for (State s : STATES) { double[] c = centroid.get(s.poster); shift.put(s.name, new double[] {Math.max(-24, Math.min(24, 512 - c[0])), Math.max(-24, Math.min(24, 512 - c[1]))}); }
        Map<Integer, Set<String>> need = new TreeMap<>();
        for (State s : STATES) for (int f : sourceFramesOf(s)) need.computeIfAbsent(f, k -> new HashSet<>()).add(s.name);
        Map<String, byte[]> frames = new HashMap<>(); // "STATE#frame" -> premult RGBA 384x384
        decode(src, need.keySet(), (f, rgb) -> {
            int[] rgba = matte(rgb);
            for (String st : need.get(f)) { double[] sh = shift.get(st); frames.put(st + "#" + f, downscale(rgba, CROP0 - sh[0], CROP0 - sh[1])); }
        });
        // passo 3: sequências finais, sprite sheets, posters, manifesto
        StringBuilder man = new StringBuilder();
        man.append("{\n  \"schema\": 1,\n  \"canvas\": ").append(OUT).append(",\n  \"cropSourcePx\": ").append(CROP).append(",\n  \"source\": {\"file\": \"bloub-default-cycle.mp4\", \"sha256\": \"").append(sha)
                .append("\", \"width\": 1024, \"height\": 1024, \"fps\": 30, \"frames\": 1368, \"durationSeconds\": 45.6},\n  \"states\": [\n");
        for (int si = 0; si < STATES.size(); si++) {
            State s = STATES.get(si);
            List<byte[]> seq = sequence(s, frames);
            int[] bb = unionBounds(seq);
            int cx = Math.max(0, bb[0] - PAD), cy = Math.max(0, bb[1] - PAD), cw = Math.min(OUT, bb[2] + PAD) - cx, ch = Math.min(OUT, bb[3] + PAD) - cy;
            int cols = Math.max(1, Math.min(seq.size(), 4096 / cw)); int rows = (seq.size() + cols - 1) / cols;
            if (rows * ch > 4096) throw new IllegalStateException("sheet too tall for " + s.name());
            BufferedImage sheet = new BufferedImage(cols * cw, rows * ch, BufferedImage.TYPE_INT_ARGB);
            for (int k = 0; k < seq.size(); k++) blit(sheet, seq.get(k), cx, cy, cw, ch, (k % cols) * cw, (k / cols) * ch);
            String sf = "states/" + s.name().toLowerCase() + ".png"; ImageIO.write(sheet, "png", out.resolve(sf).toFile());
            byte[] pf = frames.get(s.name() + "#" + s.poster);
            BufferedImage poster = new BufferedImage(OUT, OUT, BufferedImage.TYPE_INT_ARGB); blit(poster, pf, 0, 0, OUT, OUT, 0, 0);
            String pn = "posters/" + s.name().toLowerCase() + ".png"; ImageIO.write(poster, "png", out.resolve(pn).toFile());
            double t0 = s.start / 30.0, t1 = (s.end + 1) / 30.0;
            int dur = s.durationMs > 0 ? s.durationMs : (int) Math.round(seq.size() * 1000.0 / s.fps);
            man.append(String.format(Locale.ROOT, "    {\"state\": \"%s\", \"sourceStartFrame\": %d, \"sourceEndFrame\": %d, \"sourceStartTime\": %.3f, \"sourceEndTime\": %.3f, \"loop\": %b, \"loopStart\": %d, \"loopEnd\": %d, \"loopCrossfadeFrames\": %d, "
                            + "\"posterFrame\": %d, \"durationMs\": %d, \"holdMs\": %d, \"fps\": %d, \"frames\": %d, \"sheet\": \"%s\", \"poster\": \"%s\", \"cols\": %d, \"rows\": %d, \"cell\": {\"x\": %d, \"y\": %d, \"w\": %d, \"h\": %d}, "
                            + "\"opticalShiftSourcePx\": [%.1f, %.1f], \"recommendedUse\": \"%s\"}%s%n",
                    s.name(), s.start, s.end, t0, t1, s.loop, s.loopStart, s.loopEnd, s.xfade, s.poster, dur, s.holdMs, s.durationMs > 0 ? Math.round(seq.size() * 1000.0 / s.durationMs) : s.fps, seq.size(), sf, pn, cols, rows, cx, cy, cw, ch,
                    shift.get(s.name())[0], shift.get(s.name())[1], s.use, si < STATES.size() - 1 ? "," : ""));
            System.out.printf(Locale.ROOT, "%-12s frames=%3d cell=%dx%d sheet=%dx%d %s %dKB poster=%dKB%n", s.name(), seq.size(), cw, ch, sheet.getWidth(), sheet.getHeight(), sf, Files.size(out.resolve(sf)) / 1024, Files.size(out.resolve(pn)) / 1024);
        }
        man.append("  ],\n  \"sourceSegments\": [\n")
           .append("    {\"segment\": \"IDLE\", \"frames\": [0, 72], \"usedAs\": \"IDLE\"},\n")
           .append("    {\"segment\": \"THINKING_DOTS\", \"frames\": [73, 150], \"note\": \"73-78 blob shrinks to dots (hard cut into the morph at 73); 79-150 dots\", \"usedAs\": \"THINKING (loop 95-140, diff 0.09)\"},\n")
           .append("    {\"segment\": \"READING_EYES\", \"frames\": [151, 252], \"note\": \"dot grows to blob, dash eyes then tall eyes; near-static holds\", \"usedAs\": \"NOT USED (reads as IDLE variant; dedupe)\"},\n")
           .append("    {\"segment\": \"ATTENTION\", \"frames\": [253, 324], \"usedAs\": \"ATTENTION (253-300)\"},\n")
           .append("    {\"segment\": \"NOTIFICATION\", \"frames\": [325, 390], \"usedAs\": \"NOTIFICATION (325-375)\"},\n")
           .append("    {\"segment\": \"ATTENTION_ALT\", \"frames\": [391, 522], \"note\": \"second '!' then collapse to a waiting dot\", \"usedAs\": \"NOT USED (duplicates ATTENTION)\"},\n")
           .append("    {\"segment\": \"APPEAR_MORPH\", \"frames\": [523, 689], \"note\": \"dot, egg, hexagon, triangle with trail\", \"usedAs\": \"NOT USED (shape-shift identity differs from the blob; 5.5 s)\"},\n")
           .append("    {\"segment\": \"ORBIT_A\", \"frames\": [690, 800], \"note\": \"triangle to ball under orbits, not loopable (diff 24)\", \"usedAs\": \"NOT USED (PROCESSING uses ORBIT_B)\"},\n")
           .append("    {\"segment\": \"COMET_1\", \"frames\": [801, 935], \"note\": \"ball shrinks to dot, comet circles it\", \"usedAs\": \"SYNCING (loop 876-919, two comet cycles, 3-frame crossfade)\"},\n")
           .append("    {\"segment\": \"TRIANGLE_TRAIL\", \"frames\": [936, 1121], \"usedAs\": \"NOT USED\"},\n")
           .append("    {\"segment\": \"COMET_2\", \"frames\": [1122, 1266], \"note\": \"comet again, dot, dot grows into the ball (1240-1266)\", \"usedAs\": \"TRANSITION (1240-1266)\"},\n")
           .append("    {\"segment\": \"ORBIT_B\", \"frames\": [1267, 1367], \"note\": \"ball, triangle with orbits, ball (orbits fade)\", \"usedAs\": \"PROCESSING (loop with 8-frame crossfade)\"}\n  ]\n}\n");
        Files.writeString(out.resolve("mascot-manifest.json"), man.toString());
    }

    static List<Integer> sourceFramesOf(State s) {
        Set<Integer> r = new TreeSet<>(); r.add(s.poster);
        if (s.loop) { for (int f = s.loopStart - s.xfade; f < s.loopEnd; f++) r.add(Math.max(0, f)); } else { for (int f = s.start; f <= s.end; f++) r.add(f); }
        return new ArrayList<>(r);
    }

    /** Sequência final do estado (30 fps de origem, já com crossfade de loop), reamostrada para o fps de produção. */
    static List<byte[]> sequence(State s, Map<String, byte[]> fr) {
        List<byte[]> base = new ArrayList<>();
        if (s.loop) {
            int n = s.loopEnd - s.loopStart;
            for (int t = 0; t < n; t++) {
                int f = s.loopStart + t; byte[] cur = fr.get(s.name() + "#" + f);
                int u = t - (n - s.xfade);
                if (s.xfade > 0 && u >= 0) { double w = (u + 1.0) / s.xfade; cur = blend(cur, fr.get(s.name() + "#" + (s.loopStart - s.xfade + u)), w); }
                base.add(cur);
            }
        } else for (int f = s.start; f <= s.end; f++) base.add(fr.get(s.name() + "#" + f));
        if (s.durationMs > 0 || s.fps >= 30) return base;
        int count = (int) Math.max(2, Math.round(base.size() * s.fps / 30.0));
        List<byte[]> r = new ArrayList<>();
        for (int k = 0; k < count; k++) r.add(base.get((int) Math.min(base.size() - 1, Math.round(k * (base.size() - (s.loop ? 0 : 1)) / (double) (s.loop ? count : count - 1)))));
        return r;
    }

    static byte[] blend(byte[] a, byte[] b, double w) {
        byte[] o = new byte[a.length];
        for (int i = 0; i < a.length; i++) o[i] = (byte) Math.round((a[i] & 255) * (1 - w) + (b[i] & 255) * w);
        return o;
    }

    static int[] unionBounds(List<byte[]> seq) {
        int minx = OUT, miny = OUT, maxx = 0, maxy = 0;
        for (byte[] f : seq) for (int y = 0; y < OUT; y++) for (int x = 0; x < OUT; x++) if ((f[(y * OUT + x) * 4 + 3] & 255) > 6) { if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y; }
        return new int[] {minx, miny, maxx + 1, maxy + 1};
    }

    /** premult -> ARGB não premultiplicado no destino. */
    static void blit(BufferedImage dst, byte[] f, int cx, int cy, int cw, int ch, int dx, int dy) {
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
            int i = ((cy + y) * OUT + cx + x) * 4; int al = f[i + 3] & 255; if (al == 0) continue;
            int r = Math.min(255, (f[i] & 255) * 255 / al), g = Math.min(255, (f[i + 1] & 255) * 255 / al), b = Math.min(255, (f[i + 2] & 255) * 255 / al);
            dst.setRGB(dx + x, dy + y, al << 24 | r << 16 | g << 8 | b);
        }
    }

    // ---------------------------------------------------------------- vídeo / matte
    interface FrameSink { void accept(int frame, byte[] rgb); }

    static void decode(String src, Set<Integer> wanted, FrameSink sink) throws Exception {
        int last = Collections.max(wanted);
        Process p = new ProcessBuilder("nice", "-n", "10", "ffmpeg", "-v", "error", "-threads", "2", "-i", src, "-frames:v", String.valueOf(last + 1), "-pix_fmt", "rgb24", "-f", "rawvideo", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        DataInputStream in = new DataInputStream(new BufferedInputStream(p.getInputStream(), 1 << 22));
        byte[] buf = new byte[SRC * SRC * 3];
        for (int f = 0; f <= last; f++) { in.readFully(buf); if (wanted.contains(f)) sink.accept(f, buf.clone()); }
        p.destroy();
    }

    static double[] darkCentroid(byte[] rgb) {
        long sx = 0, sy = 0, c = 0;
        for (int y = 0; y < SRC; y++) for (int x = 0; x < SRC; x++) { int i = (y * SRC + x) * 3; if (Math.max(Math.max(rgb[i] & 255, rgb[i + 1] & 255), rgb[i + 2] & 255) < 60) { sx += x; sy += y; c++; } }
        return new double[] {sx / (double) c, sy / (double) c};
    }

    /** RGB (fundo branco) -> int[] ARGB não premultiplicado em 1024x1024. */
    static int[] matte(byte[] rgb) {
        int N = SRC * SRC; int[] label = new int[N]; boolean[] white = new boolean[N];
        for (int p = 0; p < N; p++) white[p] = Math.min(Math.min(rgb[p * 3] & 255, rgb[p * 3 + 1] & 255), rgb[p * 3 + 2] & 255) >= 240;
        boolean[] bg = new boolean[N]; int[] queue = new int[N]; int comp = 0;
        for (int s = 0; s < N; s++) {
            if (!white[s] || label[s] != 0) continue;
            comp++; int qh = 0, qt = 0; queue[qt++] = s; label[s] = comp; boolean border = false; int size = 0;
            while (qh < qt) {
                int p = queue[qh++]; size++; int x = p % SRC, y = p / SRC;
                if (x == 0 || y == 0 || x == SRC - 1 || y == SRC - 1) border = true;
                if (x > 0 && white[p - 1] && label[p - 1] == 0) { label[p - 1] = comp; queue[qt++] = p - 1; }
                if (x < SRC - 1 && white[p + 1] && label[p + 1] == 0) { label[p + 1] = comp; queue[qt++] = p + 1; }
                if (y > 0 && white[p - SRC] && label[p - SRC] == 0) { label[p - SRC] = comp; queue[qt++] = p - SRC; }
                if (y < SRC - 1 && white[p + SRC] && label[p + SRC] == 0) { label[p + SRC] = comp; queue[qt++] = p + SRC; }
            }
            boolean isBg = border;
            if (!border) { // bolsão: é OLHO só se o anel a 3 px for (quase) todo corpo escuro; senão é fundo preso entre órbitas
                int dark = 0, total = 0;
                for (int k = 0; k < qt; k++) {
                    int p = queue[k], x = p % SRC, y = p / SRC;
                    for (int[] d : new int[][] {{RING, 0}, {-RING, 0}, {0, RING}, {0, -RING}}) {
                        int xx = x + d[0], yy = y + d[1]; if (xx < 0 || yy < 0 || xx >= SRC || yy >= SRC) continue;
                        int q = yy * SRC + xx; if (label[q] == comp) continue; total++;
                        int lum = ((rgb[q * 3] & 255) * 3 + (rgb[q * 3 + 1] & 255) * 6 + (rgb[q * 3 + 2] & 255)) / 10; if (lum < 110) dark++;
                    }
                }
                isBg = size > 60000 || total == 0 || dark < 0.80 * total;
                if (System.getenv("MASCOT_DEBUG") != null) System.err.printf("pocket size=%d dark=%d/%d -> %s%n", size, dark, total, isBg ? "BG" : "EYE");
            }
            if (isBg) for (int k = 0; k < qt; k++) bg[queue[k]] = true;
        }
        boolean[] near = bg.clone(); // dilatação chebyshev 3 px
        for (int pass = 0; pass < 3; pass++) {
            boolean[] nx = near.clone();
            for (int y = 1; y < SRC - 1; y++) for (int x = 1; x < SRC - 1; x++) { int p = y * SRC + x; if (!near[p] && (near[p - 1] || near[p + 1] || near[p - SRC] || near[p + SRC] || near[p - SRC - 1] || near[p - SRC + 1] || near[p + SRC - 1] || near[p + SRC + 1])) nx[p] = true; }
            near = nx;
        }
        int[] out = new int[N];
        for (int p = 0; p < N; p++) {
            int r = rgb[p * 3] & 255, g = rgb[p * 3 + 1] & 255, b = rgb[p * 3 + 2] & 255;
            if (!near[p]) { out[p] = 0xFF000000 | r << 16 | g << 8 | b; continue; }
            double al = (255 - Math.min(r, Math.min(g, b))) / 255.0;
            if (bg[p] && al < 0.06 || al < 0.02) { out[p] = 0; continue; }
            int rr = (int) Math.max(0, Math.min(255, 255 - (255 - r) / al)), gg = (int) Math.max(0, Math.min(255, 255 - (255 - g) / al)), bb = (int) Math.max(0, Math.min(255, 255 - (255 - b) / al));
            out[p] = (int) Math.round(al * 255) << 24 | rr << 16 | gg << 8 | bb;
        }
        return out;
    }

    /** Média de área em espaço premultiplicado: janela de 912 px com origem (ox,oy) -> 384x384 premult RGBA 8 bits. */
    static byte[] downscale(int[] argb, double ox, double oy) {
        byte[] o = new byte[OUT * OUT * 4]; double sc = CROP / (double) OUT;
        for (int y = 0; y < OUT; y++) for (int x = 0; x < OUT; x++) {
            double x0 = ox + x * sc, x1 = x0 + sc, y0 = oy + y * sc, y1 = y0 + sc, sr = 0, sg = 0, sb = 0, sa = 0, wt = 0;
            for (int yy = (int) Math.floor(y0); yy < Math.ceil(y1); yy++) for (int xx = (int) Math.floor(x0); xx < Math.ceil(x1); xx++) {
                double w = (Math.min(x1, xx + 1) - Math.max(x0, xx)) * (Math.min(y1, yy + 1) - Math.max(y0, yy)); wt += w;
                if (xx < 0 || yy < 0 || xx >= SRC || yy >= SRC) continue;
                int v = argb[yy * SRC + xx]; double al = (v >>> 24) / 255.0;
                sa += al * w; sr += ((v >> 16) & 255) * al * w; sg += ((v >> 8) & 255) * al * w; sb += (v & 255) * al * w;
            }
            int i = (y * OUT + x) * 4; o[i] = (byte) Math.round(sr / wt); o[i + 1] = (byte) Math.round(sg / wt); o[i + 2] = (byte) Math.round(sb / wt); o[i + 3] = (byte) Math.round(255 * sa / wt);
        }
        return o;
    }
}
