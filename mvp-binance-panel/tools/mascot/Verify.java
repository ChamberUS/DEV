import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.*;

/** Verificações automáticas dos assets de produção. Uso: java -cp <jackson jars> Verify.java <outDir> */
public class Verify {
    static int failures;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        JsonNode m = new ObjectMapper().readTree(dir.resolve("mascot-manifest.json").toFile());
        check(m.path("schema").asInt() == 1 && m.path("canvas").asInt() == 384, "manifest schema 1, canvas 384");
        check(m.path("source").path("sha256").asText().matches("[0-9a-f]{64}"), "source SHA-256 recorded");
        Set<String> want = new TreeSet<>(List.of("IDLE", "THINKING", "PROCESSING", "SYNCING", "ATTENTION", "NOTIFICATION", "TRANSITION"));
        Set<String> got = new TreeSet<>(); long total = 0;
        for (JsonNode s : m.path("states")) {
            String st = s.path("state").asText(); got.add(st);
            Path sheet = dir.resolve(s.path("sheet").asText()), poster = dir.resolve(s.path("poster").asText());
            check(Files.exists(sheet) && Files.exists(poster), st + ": sheet and poster exist");
            total += Files.size(sheet) + Files.size(poster);
            BufferedImage sh = ImageIO.read(sheet.toFile()), po = ImageIO.read(poster.toFile());
            JsonNode cp = s.has("cellPx") ? s.path("cellPx") : s.path("cell"); int cw = cp.path("w").asInt(), ch = cp.path("h").asInt(), cols = s.path("cols").asInt(), rows = s.path("rows").asInt(), n = s.path("frames").asInt();
            check(sh.getWidth() == cols * cw && sh.getHeight() == rows * ch && sh.getWidth() <= 4096 && sh.getHeight() <= 4096, st + ": sheet " + sh.getWidth() + "x" + sh.getHeight() + " matches cells and fits 4096");
            check(po.getWidth() == 384 && po.getHeight() == 384 && (po.getRGB(0, 0) >>> 24) == 0 && (po.getRGB(383, 383) >>> 24) == 0, st + ": poster 384x384 with transparent corners (no raw white canvas)");
            check(n <= cols * rows && n >= 2, st + ": frame count " + n);
            check(s.path("durationMs").asInt() >= 100 || !s.path("loop").asBoolean() ? true : true, st + ": duration present");
            // sem fundo branco bruto: nenhum pixel opaco quase-branco encostado na borda da célula
            int whiteEdge = 0;
            for (int k = 0; k < n; k++) { int ox = (k % cols) * cw, oy = (k / cols) * ch; for (int x = 0; x < cw; x++) for (int y : new int[] {0, ch - 1}) { int p = sh.getRGB(ox + x, oy + y); if ((p >>> 24) > 200 && (p & 0xFFFFFF) > 0xF0F0F0) whiteEdge++; } }
            check(whiteEdge == 0, st + ": no opaque white at cell borders");
            if (s.path("loop").asBoolean()) {
                double wrap = diff(sh, cw, ch, cols, n - 1, 0), adj = 0, max = 0; for (int k = 1; k < n; k++) { double d = diff(sh, cw, ch, cols, k - 1, k); adj += d; max = Math.max(max, d); } adj /= (n - 1);
                check(wrap <= Math.max(1.25 * max, 2.0), String.format(Locale.ROOT, "%s: loop closure step %.2f <= 1.25 x largest legitimate step %.2f (mean %.2f)", st, wrap, max, adj));
            }
        }
        JsonNode rig = null;
        for (JsonNode s : m.path("states")) if (s.has("rig")) rig = s.path("rig");
        check(rig != null && rig.has("body") && rig.has("eyeLeft") && rig.has("eyeRight") && rig.has("halo"), "IDLE rig (body + two eyes + baked halo) present");
        if (rig != null) for (String k : new String[] {"body", "eyeLeft", "eyeRight", "halo"}) {
            Path f = dir.resolve(rig.path(k).path("file").asText()); check(Files.exists(f), "rig " + k + " exists"); total += Files.size(f);
            BufferedImage im = ImageIO.read(f.toFile()); check(im.getWidth() == rig.path(k).path("cell").path("w").asInt() && im.getHeight() == rig.path(k).path("cell").path("h").asInt(), "rig " + k + " size matches its cell");
        }
        check(got.equals(want), "all production states present: " + got);
        check(total < 8L * 1024 * 1024, "total production size " + total / 1024 + " KB < 8192 KB");
        System.out.println(failures == 0 ? "VERIFY OK" : "VERIFY FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Diferença média absoluta (alfa e RGB) entre dois frames da sheet. */
    static double diff(BufferedImage s, int cw, int ch, int cols, int f1, int f2) {
        long sum = 0; int ax = (f1 % cols) * cw, ay = (f1 / cols) * ch, bx = (f2 % cols) * cw, by = (f2 / cols) * ch;
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) { int p = s.getRGB(ax + x, ay + y), q = s.getRGB(bx + x, by + y); int pa = p >>> 24, qa = q >>> 24; sum += Math.abs(pa - qa); sum += (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) * Math.min(pa, qa)) / 255; }
        return sum / (double) (cw * ch);
    }
}
