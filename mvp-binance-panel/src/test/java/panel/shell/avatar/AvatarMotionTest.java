package panel.shell.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.shell.avatar.AvatarMotion.Mode;
import panel.shell.avatar.AvatarMotion.Pose;
import panel.shell.avatar.OperationRegistry.Presentation;

/** FULL / REDUCED / OFF behaviour of the avatar's motion engine, deterministic (seeded) and clock-free. */
class AvatarMotionTest {
    private final MascotTokens tk = MascotTokens.shared();

    private AvatarMotion engine(Mode m) {
        AvatarMotion e = new AvatarMotion(tk);
        e.setMode(m);
        e.tick(0, Presentation.NONE);
        return e;
    }

    private static Pose run(AvatarMotion e, long from, long to, Presentation p) {
        Pose last = e.pose();
        for (long t = from; t <= to; t += 16) {
            last = e.tick(t, p);
        }
        return last;
    }

    @Test
    void gazeFollowsThePointerButNeverLeavesTheClamp() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(1, 1);
        Pose p = run(e, 16, 2_000, Presentation.NONE);
        assertEquals(tk.clampX(), p.eyeX(), 0.05);
        assertEquals(tk.clampY(), p.eyeY(), 0.05);
        e.setPointer(-5, -9); // even an out-of-range vector is clamped by construction of the caller; engine keeps the product bounded
        e.setPointer(-1, -1);
        p = run(e, 2_016, 4_000, Presentation.NONE);
        assertTrue(p.eyeX() >= -tk.clampX() - 0.05 && p.eyeY() >= -tk.clampY() - 0.05);
        assertEquals(tk.bodyLean() * p.eyeX(), p.leanX(), 1e-9, "body leans 12% of the eye displacement");
    }

    @Test
    void smoothingFollowsTheTimeConstant() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(1, 0);
        e.tick(1, Presentation.NONE);
        Pose p = null;
        for (long t = 2; t <= 80; t += 2) {
            p = e.tick(t, Presentation.NONE);
        }
        double expected = tk.clampX() * (1 - Math.exp(-1.0)); // one tau
        assertEquals(expected, p.eyeX(), 0.6);
    }

    @Test
    void menuOpenLooksAtTheMenuRegardlessOfThePointer() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(-1, -1);
        e.setMenuOpen(true);
        Pose p = run(e, 16, 2_000, Presentation.NONE);
        assertEquals(tk.menuTargetX(), p.eyeX(), 0.05);
        assertEquals(tk.menuTargetY(), p.eyeY(), 0.05);
        e.setMenuOpen(false);
        e.clearPointer();
        p = run(e, 2_016, 4_500, Presentation.NONE);
        assertTrue(Math.abs(p.eyeY()) < 1.5, "returns to neutral/idle drift after closing");
    }

    @Test
    void loadingTracksAtSixtyPercentAmplitudeAndErrorGoesNeutral() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(1, 1);
        Pose p = run(e, 16, 2_000, Presentation.LOADING);
        assertEquals(tk.clampX() * tk.loadingAmplitude(), p.eyeX(), 0.05);
        p = run(e, 2_016, 4_000, Presentation.ERROR);
        assertEquals(0, p.eyeX(), 0.05, "error look suppresses tracking");
        assertEquals(1, p.errorMix(), 1e-9);
    }

    @Test
    void errorLookSuppressesBlinkingAndRingsConvergeToTheirTargets() {
        AvatarMotion e = engine(Mode.FULL);
        double minScale = 1;
        for (long t = 16; t <= 20_000; t += 16) {
            minScale = Math.min(minScale, e.tick(t, Presentation.ERROR).eyeScaleY());
        }
        // squinted eyes are flat by design (error look) but there is no blink animation on top: steady value
        Pose p = e.tick(20_016, Presentation.ERROR);
        assertEquals(p.eyeScaleY(), e.tick(20_032, Presentation.ERROR).eyeScaleY(), 1e-9);
        AvatarMotion l = engine(Mode.FULL);
        p = run(l, 16, 600, Presentation.LOADING);
        assertEquals(1, p.ringsMix(), 1e-9, "rings enter in 320 ms");
        p = run(l, 616, 1_200, Presentation.NONE);
        assertEquals(0, p.ringsMix(), 1e-9);
    }

    @Test
    void ringsOrbitOncePerConfiguredPeriod() {
        AvatarMotion e = engine(Mode.FULL);
        Pose p = e.tick(600, Presentation.LOADING);
        assertEquals(180, p.ringAngle(), 1e-6);
        p = e.tick(1_200, Presentation.LOADING);
        assertEquals(0, p.ringAngle(), 1e-6);
    }

    @Test
    void clickReactionLastsExactlyTheDesignedFourHundredNinetyMilliseconds() {
        AvatarMotion e = engine(Mode.FULL);
        e.clearPointer();
        long t0 = 10_000;
        e.tick(t0 - 16, Presentation.NONE);
        e.react(t0);
        assertTrue(e.tick(t0 + 50, Presentation.NONE).reacting());
        Pose hold = e.tick(t0 + 200, Presentation.NONE);
        assertTrue(hold.reacting());
        assertTrue(hold.bang() > 0.9, "surprise shows its mark while holding");
        assertTrue(hold.bodyScaleY() < 0.95 && hold.eyeScaleY() > 1.1, "body presses, eyes widen");
        assertTrue(e.tick(t0 + 489, Presentation.NONE).reacting());
        assertFalse(e.tick(t0 + 491, Presentation.NONE).reacting());
        assertEquals(490, tk.reactTotalMs());
    }

    @Test
    void reactionDuringAnOperationIsABodyPressOnly() {
        AvatarMotion e = engine(Mode.FULL);
        e.react(5_000);
        Pose p = e.tick(5_200, Presentation.LOADING);
        assertTrue(p.reacting());
        assertEquals(0, p.bang(), 1e-9, "no '!' while loading");
        assertTrue(p.eyeScaleY() <= 1.07, "no wide eyes while loading (hover/blink only)");
        assertTrue(p.bodyScaleY() < 0.95);
    }

    @Test
    void blinkScheduleIsSeededReproducibleAndWithinTheDesignedWindow() {
        List<Long> a = blinkTimes(new AvatarMotion(tk));
        List<Long> b = blinkTimes(new AvatarMotion(tk));
        assertEquals(a, b, "seed 7 makes the sequence reproducible");
        assertTrue(a.size() >= 5);
        for (int i = 1; i < a.size(); i++) {
            long gap = a.get(i) - a.get(i - 1);
            assertTrue(gap >= tk.blinkMinMs() && gap <= tk.blinkMaxMs() + 2 * 110 + 40, "gap " + gap);
        }
    }

    private List<Long> blinkTimes(AvatarMotion e) {
        List<Long> out = new ArrayList<>();
        boolean closing = false;
        for (long t = 0; t <= 40_000; t += 8) {
            double s = e.tick(t, Presentation.NONE).eyeScaleY();
            if (s < 0.5 && !closing) {
                out.add(t);
                closing = true;
            } else if (s > 0.9) {
                closing = false;
            }
        }
        return out;
    }

    @Test
    void reducedModeHasNoTrackingDriftBlinkOrRingMotion() {
        AvatarMotion e = engine(Mode.REDUCED);
        e.setPointer(1, 1);
        e.setMenuOpen(true);
        Pose p = run(e, 16, 30_000, Presentation.NONE);
        assertEquals(0, p.eyeX(), 1e-6);
        assertEquals(0, p.eyeY(), 1e-6);
        assertFalse(e.continuous(), "no continuous animation in REDUCED");
        // no blink over 30 s
        for (long t = 30_016; t < 60_000; t += 50) {
            assertTrue(e.tick(t, Presentation.NONE).eyeScaleY() > 0.95);
        }
        e.react(61_000);
        assertTrue(e.tick(61_050, Presentation.NONE).reacting(), "100 ms press");
        Pose after = e.tick(61_101, Presentation.NONE);
        assertFalse(after.reacting());
        assertEquals(0, after.bang(), 1e-9, "REDUCED never shows the surprise mark");
        Pose rings = run(e, 62_000, 62_600, Presentation.LOADING);
        assertEquals(0, rings.ringAngle(), 1e-9, "static, partial rings");
        assertTrue(rings.ringAlpha() > 0.3 && rings.ringAlpha() < 1.0);
    }

    @Test
    void offModeIsInstantaneousAndIgnoresReactions() {
        AvatarMotion e = engine(Mode.OFF);
        e.react(1_000);
        assertFalse(e.tick(1_010, Presentation.NONE).reacting());
        Pose p = e.tick(1_020, Presentation.LOADING);
        assertEquals(1, p.ringsMix(), 1e-9, "state changes are immediate");
        p = e.tick(1_030, Presentation.ERROR);
        assertEquals(1, p.errorMix(), 1e-9);
        assertEquals(0, p.eyeX(), 1e-9);
        assertFalse(e.continuous());
    }

    @Test
    void aLongPauseNeverCausesAJump() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(1, 0);
        e.tick(16, Presentation.NONE);
        Pose p = e.tick(60_000, Presentation.NONE); // window was inactive for a minute
        assertTrue(Math.abs(p.eyeX()) < tk.clampX() * 0.65, "advances by at most one capped step, not a teleport");
    }

    @Test
    void switchingToOffReturnsTheEyesToNeutralImmediately() {
        AvatarMotion e = engine(Mode.FULL);
        e.setPointer(1, 1);
        run(e, 16, 1_000, Presentation.NONE);
        e.setMode(Mode.OFF);
        Pose p = e.tick(1_016, Presentation.NONE);
        assertEquals(0, p.eyeX(), 1e-9);
        assertEquals(0, p.eyeY(), 1e-9);
        assertFalse(e.hasPointer());
    }
}
