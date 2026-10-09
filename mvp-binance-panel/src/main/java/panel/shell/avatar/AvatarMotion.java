package panel.shell.avatar;

import java.util.Random;

/**
 * Deterministic motion engine of the avatar (layers L0–L3 of {@code BYX_MASCOT_MOTION.md}). Pure: no JavaFX, no timers, no clock — the caller
 * passes {@code nowMs} to {@link #tick}. The same inputs always produce the same {@link Pose} (the blink PRNG is seeded with the design's seed 7),
 * which is what makes the FULL/REDUCED/OFF behaviour testable without a window.
 *
 * <p>Precedence: L3 click reaction &gt; L2 operation (rings or error look) &gt; L1 gaze &gt; L0 idle.
 * FULL = tracking, idle drift, blinking, 490 ms reaction, animated rings. REDUCED = no tracking/drift/blink, 100 ms press only, static rings.
 * OFF = nothing animates; state changes are instantaneous.
 */
public final class AvatarMotion {
    public enum Mode { FULL, REDUCED, OFF }

    /**
     * One frame of the avatar. Eye offsets are in design units (box of 100); scales are multipliers; the *Mix values are 0..1 blends.
     * {@code ringAlpha} is the opacity of the rings (partial in REDUCED/OFF, where they are static).
     */
    public record Pose(double eyeX, double eyeY, double leanX, double leanY, double eyeScaleX, double eyeScaleY, double bodyScaleX, double bodyScaleY,
            double bang, double errorMix, double ringsMix, double ringAlpha, double ringAngle, double ringTilt, boolean reacting) {
        public static final Pose NEUTRAL = new Pose(0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, false);
    }

    private static final double BLINK_CLOSED = 0.08;
    private static final long BLINK_HALF_MS = 110;
    /** MOTION §6: in REDUCED the click reaction is a body press of 100 ms and nothing else. */
    static final long REDUCED_PRESS_MS = 100;
    private final MascotTokens tk;
    private final Random rng;
    private Mode mode = Mode.FULL;

    private double gx;
    private double gy;
    private long lastMs = Long.MIN_VALUE;
    private double ptrX = Double.NaN;
    private double ptrY = Double.NaN;
    private boolean menuOpen;
    private boolean hover;
    private boolean pressed;
    private long reactStart = -1;
    private long blinkAt;
    private long blinkStart = -1;
    private double hoverMix;
    private double pressMix;
    private double ringsMix;
    private double errorMix;
    private boolean l2Loading;
    private boolean l2Error;
    private Pose pose = Pose.NEUTRAL;

    public AvatarMotion(MascotTokens tokens) {
        this.tk = tokens;
        this.rng = new Random(tokens.blinkSeed());
        this.blinkAt = -1; // scheduled relative to the first tick (the clock's origin is the caller's business)
    }

    private long nextBlinkGap() {
        return tk.blinkMinMs() + (long) (rng.nextDouble() * (tk.blinkMaxMs() - tk.blinkMinMs()));
    }

    // ------------------------------------------------------------------ inputs

    public void setMode(Mode m) {
        if (m == mode) {
            return;
        }
        mode = m;
        if (m != Mode.FULL) {
            ptrX = Double.NaN;
            ptrY = Double.NaN;
            blinkStart = -1;
        }
        if (m == Mode.OFF) {
            reactStart = -1;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** Pointer inside the window as a vector in the unit disc (0,0 = over the avatar). Ignored outside FULL. */
    public void setPointer(double nx, double ny) {
        ptrX = nx;
        ptrY = ny;
    }

    public void clearPointer() {
        ptrX = Double.NaN;
        ptrY = Double.NaN;
    }

    public boolean hasPointer() {
        return !Double.isNaN(ptrX);
    }

    public void setMenuOpen(boolean open) {
        menuOpen = open;
    }

    public void setHover(boolean on) {
        hover = on;
    }

    public void setPressed(boolean on) {
        pressed = on;
    }

    /** L3: the click reaction. Never delays anything; OFF ignores it. */
    public void react(long nowMs) {
        if (mode != Mode.OFF) {
            reactStart = nowMs;
        }
    }

    public Pose pose() {
        return pose;
    }

    // ------------------------------------------------------------------ step

    private long dur(long full, long reduced) {
        return switch (mode) {
            case FULL -> full;
            case REDUCED -> reduced;
            case OFF -> 0;
        };
    }

    private static double approach(double cur, double target, double dtMs, long durationMs) {
        if (durationMs <= 0) {
            return target;
        }
        double step = dtMs / durationMs;
        return cur < target ? Math.min(target, cur + step) : Math.max(target, cur - step);
    }

    private static double easeOut(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 1 - (1 - c) * (1 - c) * (1 - c);
    }

    /** Advances to {@code nowMs} with the current operation presentation and returns the frame. */
    public Pose tick(long nowMs, OperationRegistry.Presentation op) {
        double dt = lastMs == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(50, nowMs - lastMs)); // a long pause (inactive window) never makes a jump
        if (blinkAt < 0) {
            blinkAt = nowMs + nextBlinkGap();
        }
        lastMs = nowMs;
        l2Loading = op == OperationRegistry.Presentation.LOADING;
        l2Error = op == OperationRegistry.Presentation.ERROR;
        boolean full = mode == Mode.FULL;

        // ---- L1 gaze target (design units)
        double tx = 0;
        double ty = 0;
        if (full && !l2Error) {
            if (menuOpen) {
                tx = tk.menuTargetX();
                ty = tk.menuTargetY();
            } else if (!Double.isNaN(ptrX)) {
                double amp = l2Loading ? tk.loadingAmplitude() : 1;
                tx = ptrX * tk.clampX() * amp;
                ty = ptrY * tk.clampY() * amp;
            } else { // L0 idle drift: slow, tiny, deterministic
                tx = Math.sin(nowMs / 3100.0) * 1.1;
                ty = Math.sin(nowMs / 4300.0 + 1.0) * 0.7;
            }
        }
        if (mode == Mode.OFF) {
            gx = tx;
            gy = ty;
        } else {
            double a = 1 - Math.exp(-dt / (full ? tk.gazeTauMs() : tk.reducedGazeBack()));
            gx += (tx - gx) * a;
            gy += (ty - gy) * a;
        }

        // ---- L0 blink (FULL, not in the error look)
        double blink = 1;
        if (full && !l2Error) {
            if (blinkStart < 0 && nowMs >= blinkAt) {
                blinkStart = nowMs;
            }
            if (blinkStart >= 0) {
                long t = nowMs - blinkStart;
                if (t < BLINK_HALF_MS) {
                    blink = 1 - (1 - BLINK_CLOSED) * (t / (double) BLINK_HALF_MS);
                } else if (t < 2 * BLINK_HALF_MS) {
                    blink = BLINK_CLOSED + (1 - BLINK_CLOSED) * ((t - BLINK_HALF_MS) / (double) BLINK_HALF_MS);
                } else {
                    blinkStart = -1;
                    blinkAt = nowMs + nextBlinkGap();
                }
            }
        } else {
            blinkStart = -1;
            if (nowMs >= blinkAt) {
                blinkAt = nowMs + nextBlinkGap();
            }
        }

        // ---- hover / press mixes
        hoverMix = approach(hoverMix, hover ? 1 : 0, dt, dur(tk.hover(), tk.reducedHover()));
        pressMix = approach(pressMix, pressed ? 1 : 0, dt, dur(tk.press(), tk.reducedPress()));

        // ---- L3 reaction
        double reactX = 1;
        double reactY = 1;
        double reactEye = 1;
        double bang = 0;
        boolean reacting = false;
        if (reactStart >= 0) {
            long t = nowMs - reactStart;
            if (full) {
                long p1 = tk.reactPressMs();
                long p2 = p1 + tk.reactHoldMs();
                long end = p2 + tk.reactReturnMs();
                if (t >= end) {
                    reactStart = -1;
                } else {
                    reacting = true;
                    double k = t < p1 ? easeOut(t / (double) p1) : t < p2 ? 1 : 1 - easeOut((t - p2) / (double) tk.reactReturnMs());
                    reactY = 1 - 0.10 * k; // body presses
                    reactX = 1 + 0.05 * k;
                    if (!l2Loading && !l2Error) { // during an operation: body press only (no wide eyes, no "!")
                        reactEye = 1 + 0.18 * k;
                        bang = t >= p1 * 0.5 && t < p2 ? 1 : k > 0.3 ? k : 0;
                    }
                }
            } else { // REDUCED: a 100 ms press and nothing else
                if (t >= REDUCED_PRESS_MS) {
                    reactStart = -1;
                } else {
                    reacting = true;
                    reactY = 1 - 0.06;
                }
            }
        }

        // ---- L2 operation blends
        double ringTarget = l2Loading ? 1 : 0;
        ringsMix = approach(ringsMix, ringTarget, dt, dur(tk.enter(), tk.reducedEnter()));
        double errTarget = l2Error ? 1 : 0;
        errorMix = approach(errorMix, errTarget, dt, l2Error ? dur(tk.enter() / 2, tk.reducedEnter()) : dur(tk.gazeBack(), tk.reducedGazeBack()));

        double angle = 0;
        double tilt = 0;
        double ringAlpha = ringsMix;
        if (full) {
            angle = 360.0 * ((nowMs % tk.orbitMsPerRev()) / (double) tk.orbitMsPerRev());
            double ph = (nowMs % (2 * tk.tiltMs())) / (double) tk.tiltMs(); // 0..2 triangle: alternating tilt every tiltMs
            tilt = ph < 1 ? ph : 2 - ph;
        } else {
            ringAlpha = ringsMix * 0.6; // static, partial ring
        }

        double widen = 1 + 0.06 * hoverMix;
        double eyeScaleY = Math.max(0.05, blink * widen * reactEye) * (1 - 0.82 * errorMix); // error look squints the eyes flat
        double lean = tk.bodyLean();
        pose = new Pose(gx, gy, gx * lean, gy * lean, widen * (1 + 0.04 * (reactEye - 1)), eyeScaleY, reactX * (1 + 0.02 * pressMix),
                reactY * (1 - 0.06 * pressMix), bang * (1 - errorMix), errorMix, ringsMix, ringAlpha, angle, tilt, reacting);
        return pose;
    }

    /** True while something still changes with time other than the FULL idle drift (so REDUCED/OFF/idle can stop their timer). */
    public boolean animating() {
        if (reactStart >= 0 || blinkStart >= 0 || l2Loading || l2Error) {
            return true;
        }
        if (Math.abs(ringsMix - (l2Loading ? 1 : 0)) > 1e-6 || Math.abs(errorMix - (l2Error ? 1 : 0)) > 1e-6) {
            return true;
        }
        if (Math.abs(hoverMix - (hover ? 1 : 0)) > 1e-6 || Math.abs(pressMix - (pressed ? 1 : 0)) > 1e-6) {
            return true;
        }
        return mode == Mode.FULL && (Math.abs(gx) > 1e-3 || Math.abs(gy) > 1e-3 || hasPointer() || menuOpen);
    }

    /** FULL never settles (idle drift and blinking); REDUCED/OFF settle when {@link #animating()} is false. */
    public boolean continuous() {
        return mode == Mode.FULL;
    }

    public double gazeX() {
        return gx;
    }

    public double gazeY() {
        return gy;
    }
}
