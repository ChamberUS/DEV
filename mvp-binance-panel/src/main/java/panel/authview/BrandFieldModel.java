package panel.authview;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Modelo do brand field (handoff P3.20; mesmas constantes de js/brand-field.js). Sem JavaFX: coordenadas
 * normalizadas 0..1, PRNG mulberry32 com semente 7, grade 7x6 = 42 nós, 60 triângulos, 101 arestas.
 * Topologia fixa: nunca triangula por quadro.
 */
public final class BrandFieldModel {
    public static final int COLS = 6;
    public static final int ROWS = 5;
    public static final int SEED = 7;
    public static final double PERIOD_MS = 9000;
    public static final double PERIOD_LOADING_MS = 4500;
    public static final int FPS = 30;
    public static final int REDUCED_FPS = 12;
    public static final double EDGE_ALPHA = 0.16;
    public static final double FRONT_ALPHA = 0.28;
    public static final double FRONT_WIDTH = 0.12;
    public static final int STARS = 80;
    public static final double STATIC_PHASE = 0.62;
    public static final double REDUCED_GLOW_PERIOD_MS = 8000;
    public static final double[] BAR_BASE = {0.9, 0.6, 0.4, 0.28, 0.18, 0.1};

    public record Node(double bx, double by, double ox, double oy, double w, double p1, double p2) {
    }

    public record Triangle(int a, int b, int c, double fa, double ph, double sp) {
    }

    public record Star(double x, double y, double s, double ph, double sp) {
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Triangle> triangles = new ArrayList<>();
    private final List<int[]> edges = new ArrayList<>();
    private final List<Star> stars = new ArrayList<>();

    /** mulberry32, idêntico ao JS (aritmética de 32 bits). */
    static final class Rng {
        private int a;

        Rng(int seed) {
            a = seed;
        }

        double next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    public BrandFieldModel() {
        Rng r = new Rng(SEED);
        double orbitMin = 0.015;
        double orbitMax = 0.036;
        double driftMin = 18000;
        double driftMax = 40000;
        for (int j = 0; j <= ROWS; j++) {
            for (int i = 0; i <= COLS; i++) {
                double bx = -0.08 + 1.16 * i / COLS + (r.next() - 0.5) * 0.06;
                double by = -0.08 + 1.16 * j / ROWS + (r.next() - 0.5) * 0.06;
                double ox = orbitMin + r.next() * (orbitMax - orbitMin);
                double oy = orbitMin + r.next() * (orbitMax - orbitMin);
                double w = 2 * Math.PI / (driftMin + r.next() * (driftMax - driftMin));
                double p1 = r.next() * 6.283;
                double p2 = r.next() * 6.283;
                nodes.add(new Node(bx, by, ox, oy, w, p1, p2));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int j = 0; j < ROWS; j++) {
            for (int i = 0; i < COLS; i++) {
                int a = ix(i, j);
                int b = ix(i + 1, j);
                int c = ix(i, j + 1);
                int d = ix(i + 1, j + 1);
                int[][] pair = r.next() < 0.5 ? new int[][] {{a, b, d}, {a, d, c}} : new int[][] {{a, b, c}, {b, d, c}};
                for (int[] t : pair) {
                    triangles.add(new Triangle(t[0], t[1], t[2], 0.03 + r.next() * 0.07, r.next() * 6.283, 0.00012 + r.next() * 0.0002));
                    edge(seen, t[0], t[1]);
                    edge(seen, t[1], t[2]);
                    edge(seen, t[2], t[0]);
                }
            }
        }
        for (int k = 0; k < STARS; k++) {
            stars.add(new Star(r.next(), r.next(), 0.6 + r.next() * 0.8, r.next() * 6.283, 0.0004 + r.next() * 0.0008));
        }
    }

    private static int ix(int i, int j) {
        return j * (COLS + 1) + i;
    }

    private void edge(Set<String> seen, int a, int b) {
        String key = a < b ? a + "-" + b : b + "-" + a;
        if (seen.add(key)) {
            edges.add(new int[] {a, b});
        }
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Triangle> triangles() {
        return triangles;
    }

    public List<int[]> edges() {
        return edges;
    }

    public List<Star> stars() {
        return stars;
    }

    /** Posição do nó no instante t (ms). */
    public static double[] position(Node p, double t) {
        return new double[] {p.bx() + p.ox() * Math.cos(p.w() * t + p.p1()), p.by() + p.oy() * Math.sin(0.83 * p.w() * t + p.p2())};
    }

    public static double g(double x) {
        return Math.exp(-x * x);
    }

    /** Opacidade da barra n na fase p; wave=false devolve a base (REDUCED/OFF). */
    public static double barOpacity(int n, double p, boolean wave) {
        double boost = wave ? 1 + 0.9 * g((p - (0.5 + 0.06 * n)) / 0.07) : 1;
        return Math.min(1, BAR_BASE[n] * boost);
    }

    /** Fase mestre acumulada: p += dt / período (9 s, 4,5 s enquanto um formulário carrega). */
    public static double advance(double phase, double dtMs, boolean loading) {
        double p = phase + dtMs / (loading ? PERIOD_LOADING_MS : PERIOD_MS);
        return p - Math.floor(p);
    }
}
