package panel.mascot;

import java.util.Random;

/**
 * Vida PROCEDURAL do IDLE (barata e quase imperceptível; sem frames): piscar natural (intervalo irregular, às vezes duplo), respiração, deriva vertical mínima, inclinação do corpo e olhares ocasionais
 * para o lado. Determinístico com um {@link Random} injetado e um relógio em ms (testes). O ponteiro (via {@link Gaze}) só vale quando houver ponteiro; sem ele, os olhares ocasionais assumem.
 * FULL = tudo. REDUCED = SÓ o piscar (sem respiração, deriva, inclinação ou seguir o ponteiro). OFF: não se instancia.
 */
public final class IdleLife {
    /** Pose pedida ao rig. gazeX/gazeY em -1..1; blink 0 (aberto)..1 (fechado); breath/drift em -1..1; tiltDeg em graus; curiosity 0..1. */
    public record Pose(double gazeX, double gazeY, double blink, double breath, double drift, double tiltDeg, double curiosity) {
        public static final Pose REST = new Pose(0, 0, 0, 0, 0, 0, 0);
    }

    static final double MAX_BASE_TILT_DEG = 0.6;
    static final double MAX_POINTER_TILT_DEG = 2.6;
    static final long BLINK_CLOSE_MS = 70;
    static final long BLINK_OPEN_MS = 120;
    static final long MIN_BLINK_GAP_MS = 2_400;
    static final long BLINK_JITTER_MS = 4_200;
    static final long POINTER_STALE_MS = 6_000;

    private final Random rnd;
    private final boolean full;
    private final Gaze gaze = new Gaze();
    private long t0 = -1;
    private long last;
    private long nextBlink;
    private long blinkStart = -1;
    private boolean doubleBlinkPending;
    private long lastPointerAt = Long.MIN_VALUE / 2;
    private long nextGlance;
    private long glanceUntil = -1;
    private double glanceX;
    private double glanceY;
    private double gx;
    private double gy;
    private double curiosity;
    private double tilt;
    private final double phase;

    public IdleLife(Random rnd, boolean full) {
        this.rnd = rnd;
        this.full = full;
        this.phase = rnd.nextDouble() * Math.PI * 2;
    }

    public Gaze gaze() {
        return gaze;
    }

    public boolean full() {
        return full;
    }

    /** Ponteiro em px da cena; só vale em FULL. */
    public void pointer(double px, double py, double cx, double cy, double size, long nowMs) {
        if (!full) {
            return;
        }
        gaze.pointer(px, py, cx, cy, size);
        lastPointerAt = nowMs;
    }

    public void clearPointer() {
        gaze.clear();
    }

    /** O mascote está sob o cursor (hover): curiosidade total. */
    public void hover(boolean over) {
        hover = over;
    }

    private boolean hover;

    /** Avança até {@code nowMs} e devolve a pose. Chamadas com tempo que retrocede são ignoradas (relógio estável). */
    public Pose step(long nowMs) {
        if (t0 < 0) {
            t0 = nowMs;
            last = nowMs;
            nextBlink = nowMs + 1_200 + rnd.nextInt(2_000); // primeiro piscar logo, mas não no instante da entrada
            nextGlance = nowMs + 5_000 + rnd.nextInt(6_000);
        }
        long dt = Math.max(0, Math.min(200, nowMs - last));
        last = Math.max(last, nowMs);
        double blink = blink(nowMs);
        if (!full) {
            return new Pose(0, 0, blink, 0, 0, 0, 0);
        }
        double t = (nowMs - t0) / 1000.0;
        boolean pointerLive = gaze.hasPointer() && nowMs - lastPointerAt < POINTER_STALE_MS;
        double targetX = 0;
        double targetY = 0;
        double cur = 0;
        double tiltTarget = MAX_BASE_TILT_DEG * Math.sin(2 * Math.PI * t / 9.1 + phase);
        if (pointerLive) {
            targetX = gaze.targetX();
            targetY = gaze.targetY();
            cur = hover ? 1 : gaze.curiosity();
            tiltTarget += MAX_POINTER_TILT_DEG * cur * gaze.tiltSign();
        } else {
            if (gaze.hasPointer()) {
                gaze.clear(); // ponteiro parado há muito: relaxa
            }
            cur = hover ? 1 : 0;
            if (glanceUntil >= 0 && nowMs < glanceUntil) {
                targetX = glanceX;
                targetY = glanceY;
            } else {
                glanceUntil = -1;
                if (nowMs >= nextGlance) { // olhar ocasional: pequeno, curto, em intervalo irregular
                    glanceX = (rnd.nextDouble() * 2 - 1) * 0.65;
                    glanceY = (rnd.nextDouble() * 2 - 1) * 0.35;
                    glanceUntil = nowMs + 900 + rnd.nextInt(700);
                    nextGlance = glanceUntil + 7_000 + rnd.nextInt(8_000);
                }
            }
        }
        gx = Gaze.smooth(gx, targetX, dt, pointerLive ? Gaze.TAU_MS : 160);
        gy = Gaze.smooth(gy, targetY, dt, pointerLive ? Gaze.TAU_MS : 160);
        curiosity = Gaze.smooth(curiosity, cur, dt, 140);
        tilt = Gaze.smooth(tilt, tiltTarget, dt, 220);
        double breath = Math.sin(2 * Math.PI * t / 4.6 + phase);
        double drift = 0.6 * Math.sin(2 * Math.PI * t / 7.3 + phase * 0.7) + 0.4 * Math.sin(2 * Math.PI * t / 11.9);
        return new Pose(gx, gy, blink, breath, drift, tilt, curiosity);
    }

    /** True enquanto algo muda rápido (piscar, olhar, ponteiro ativo): o chamador pode usar taxa maior; senão, taxa baixa. */
    public boolean busy(long nowMs) {
        return blinkStart >= 0 || glanceUntil >= 0 || gaze.hasPointer() && nowMs - lastPointerAt < POINTER_STALE_MS || hover || Math.abs(gx) > 0.02 || Math.abs(gy) > 0.02;
    }

    private double blink(long now) {
        if (blinkStart < 0) {
            if (now >= nextBlink) {
                blinkStart = now;
                doubleBlinkPending = rnd.nextInt(100) < 14;
            } else {
                return 0;
            }
        }
        long e = now - blinkStart;
        if (e < BLINK_CLOSE_MS) {
            return e / (double) BLINK_CLOSE_MS;
        }
        if (e < BLINK_CLOSE_MS + BLINK_OPEN_MS) {
            return 1 - (e - BLINK_CLOSE_MS) / (double) BLINK_OPEN_MS;
        }
        blinkStart = -1;
        if (doubleBlinkPending) {
            doubleBlinkPending = false;
            nextBlink = now + 150;
        } else {
            nextBlink = now + MIN_BLINK_GAP_MS + rnd.nextInt((int) BLINK_JITTER_MS);
        }
        return 0;
    }
}
