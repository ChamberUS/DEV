package panel.mascot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** V2.1P-1: olhar (zona morta, clamp, histerese, suavização), vida procedural do IDLE, guia (cooldown/primeira visita), prioridade e guards (sem hook global, sem rede, sem segurança). Tudo puro. */
class MascotPresencePureTest {
    private static final double SIZE = 96;

    // ------------------------------------------------------------------------------------------------ olhar
    @Test
    void deadZoneKeepsTheGazeStraightWhenTheCursorIsOnTopOfTheMascot() {
        Gaze g = new Gaze();
        g.pointer(500 + 0.05 * SIZE, 400, 500, 400, SIZE);
        assertEquals(0, g.targetX(), 1e-9);
        assertEquals(0, g.targetY(), 1e-9);
        assertEquals(1.0, g.curiosity(), 1e-9, "on top of the mascot: full curiosity");
    }

    @Test
    void theTargetIsAlwaysInsideTheUnitCircleNoMatterHowFarTheCursorIs() {
        Gaze g = new Gaze();
        for (double[] p : new double[][] {{1e7, 0}, {-1e7, 5}, {3, 1e9}, {-4e5, -4e5}, {Double.MAX_VALUE / 4, 1}, {800, 600}}) {
            g.clear();
            g.pointer(p[0], p[1], 500, 400, SIZE);
            assertTrue(Math.hypot(g.targetX(), g.targetY()) <= 1.0 + 1e-9, "clamped for " + p[0] + "," + p[1]);
            assertTrue(!Double.isNaN(g.targetX()) && !Double.isNaN(g.targetY()));
        }
        g.clear();
        g.pointer(2000, 400, 500, 400, SIZE);
        assertEquals(1.0, g.targetX(), 1e-9);
        assertEquals(0.0, g.targetY(), 1e-9);
        g.clear();
        g.pointer(500, -2000, 500, 400, SIZE);
        assertEquals(-1.0, g.targetY(), 1e-9, "up is negative");
    }

    @Test
    void tinyPointerMovementsAreIgnoredSoTheGazeDoesNotTremble() {
        Gaze g = new Gaze();
        g.pointer(900, 400, 500, 400, SIZE);
        double x = g.targetX();
        double y = g.targetY();
        for (int i = 0; i < 50; i++) {
            g.pointer(900 + (i % 2) * 1.0, 400 + (i % 3) * 1.0, 500, 400, SIZE); // < 3% of the size
        }
        assertEquals(x, g.targetX(), 0, "sub-threshold jitter never changes the target");
        assertEquals(y, g.targetY(), 0);
        g.pointer(900, 700, 500, 400, SIZE);
        assertTrue(g.targetY() > y, "a real move updates it");
    }

    @Test
    void smoothingConvergesMonotonicallyAndDoesNotDependOnTheFrameRate() {
        double a = 0;
        double prev = 0;
        for (int i = 0; i < 40; i++) {
            a = Gaze.smooth(a, 1, 25, Gaze.TAU_MS);
            assertTrue(a >= prev && a <= 1.0, "monotonic, never overshoots");
            prev = a;
        }
        assertTrue(a > 0.98, "converges");
        double coarse = Gaze.smooth(0, 1, 100, Gaze.TAU_MS);
        double fine = 0;
        for (int i = 0; i < 4; i++) {
            fine = Gaze.smooth(fine, 1, 25, Gaze.TAU_MS);
        }
        assertEquals(coarse, fine, 1e-9, "four 25 ms steps equal one 100 ms step");
    }

    @Test
    void proximityAndTiltFollowTheSideOfTheCursor() {
        Gaze g = new Gaze();
        g.pointer(500 + 90, 400, 500, 400, SIZE); // ~0.94 x size away
        assertTrue(g.curiosity() > 0 && g.curiosity() < 1);
        assertEquals(1, g.tiltSign());
        g.clear();
        g.pointer(500 - 90, 400, 500, 400, SIZE);
        assertEquals(-1, g.tiltSign());
        g.clear();
        g.pointer(500 + 5 * SIZE, 400, 500, 400, SIZE);
        assertEquals(0, g.curiosity(), 1e-9, "far away: no curiosity");
    }

    // ------------------------------------------------------------------------------------------------ vida do IDLE
    @Test
    void blinksAreNaturalIrregularAndRareNotEverySecondsExactly() {
        IdleLife life = new IdleLife(new Random(7), true);
        List<Long> starts = new ArrayList<>();
        boolean closed = false;
        double max = 0;
        for (long t = 0; t < 120_000; t += 10) {
            double b = life.step(t).blink();
            max = Math.max(max, b);
            if (b > 0 && !closed) {
                starts.add(t);
                closed = true;
            } else if (b == 0) {
                closed = false;
            }
        }
        assertTrue(starts.size() >= 12 && starts.size() <= 45, "a blink every few seconds on average: " + starts.size());
        assertEquals(1.0, max, 0.2, "a blink closes the eyes");
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < starts.size(); i++) {
            gaps.add(starts.get(i) - starts.get(i - 1));
        }
        assertTrue(gaps.stream().distinct().count() > gaps.size() / 2, "intervals are irregular, not a fixed period");
        assertTrue(gaps.stream().filter(g -> g < 2_000).count() <= gaps.size() / 4 + 1, "only occasional double blinks are quicker than 2 s");
    }

    @Test
    void breathingDriftAndTiltStayTinyAndBounded() {
        IdleLife life = new IdleLife(new Random(3), true);
        for (long t = 0; t < 60_000; t += 17) {
            IdleLife.Pose p = life.step(t);
            assertTrue(Math.abs(p.breath()) <= 1.0 && Math.abs(p.drift()) <= 1.0, "breath/drift are unit-bounded");
            assertTrue(Math.abs(p.tiltDeg()) <= IdleLife.MAX_BASE_TILT_DEG + 1e-9, "no pointer: only the barely visible base tilt: " + p.tiltDeg());
            assertTrue(Math.abs(p.gazeX()) <= 0.7 && Math.abs(p.gazeY()) <= 0.4, "occasional glances are small");
            assertTrue(p.blink() >= 0 && p.blink() <= 1);
        }
    }

    @Test
    void thePointerMovesTheGazeButOnlyWithinTheLimitsAndRelaxesWhenItGoesStale() {
        IdleLife life = new IdleLife(new Random(11), true);
        long t = 0;
        life.step(t);
        life.pointer(5_000, 400, 500, 400, SIZE, t);
        IdleLife.Pose p = null;
        for (int i = 0; i < 60; i++) {
            t += 20;
            p = life.step(t);
        }
        assertTrue(p.gazeX() > 0.9 && p.gazeX() <= 1.0, "looks right, never beyond the clamp: " + p.gazeX());
        assertTrue(Math.abs(p.tiltDeg()) <= IdleLife.MAX_BASE_TILT_DEG + IdleLife.MAX_POINTER_TILT_DEG + 1e-9);
        t += IdleLife.POINTER_STALE_MS + 1_000; // ponteiro parado há muito
        for (int i = 0; i < 80; i++) {
            t += 20;
            p = life.step(t);
        }
        assertTrue(Math.abs(p.gazeX()) < 0.8, "a stale pointer no longer pulls the gaze");
        life.hover(true);
        for (int i = 0; i < 40; i++) {
            t += 20;
            p = life.step(t);
        }
        assertTrue(p.curiosity() > 0.9, "hovering the mascot: visual curiosity");
    }

    @Test
    void reducedMotionOnlyBlinksAndNeverFollowsOrDrifts() {
        IdleLife life = new IdleLife(new Random(5), false);
        double maxBlink = 0;
        life.pointer(900, 400, 500, 400, SIZE, 0);
        for (long t = 0; t < 30_000; t += 20) {
            IdleLife.Pose p = life.step(t);
            maxBlink = Math.max(maxBlink, p.blink());
            assertEquals(0, p.gazeX(), 0);
            assertEquals(0, p.gazeY(), 0);
            assertEquals(0, p.breath(), 0);
            assertEquals(0, p.drift(), 0);
            assertEquals(0, p.tiltDeg(), 0);
            assertEquals(0, p.curiosity(), 0);
        }
        assertTrue(maxBlink > 0.9, "the occasional blink is allowed in REDUCED");
    }

    @Test
    void sameSeedSameBehaviourDifferentSeedDifferentBehaviour() {
        List<Double> a = new ArrayList<>();
        List<Double> b = new ArrayList<>();
        List<Double> c = new ArrayList<>();
        IdleLife x = new IdleLife(new Random(1), true);
        IdleLife y = new IdleLife(new Random(1), true);
        IdleLife z = new IdleLife(new Random(2), true);
        for (long t = 0; t < 20_000; t += 40) {
            a.add(x.step(t).blink() + x.step(t).breath());
            b.add(y.step(t).blink() + y.step(t).breath());
            c.add(z.step(t).blink() + z.step(t).breath());
        }
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    // ------------------------------------------------------------------------------------------------ guia
    @Test
    void automaticHintsHaveACooldownFirstVisitsComeOncePerSessionAndAskingAlwaysAnswers() {
        AtomicLong now = new AtomicLong(1_000_000);
        MascotGuide g = new MascotGuide(new StaticMascotGuideProvider(), now::get);
        assertTrue(g.offer(MascotContext.PAYMENT_NOT_FOUND).isPresent());
        assertTrue(g.offer(MascotContext.PAYMENT_NOT_FOUND).isEmpty(), "the same hint never repeats immediately");
        now.addAndGet(MascotGuide.COOLDOWN_MS - 1);
        assertTrue(g.offer(MascotContext.PAYMENT_NOT_FOUND).isEmpty());
        assertTrue(g.offer(MascotContext.CERTIFICATE_NOT_FOUND).isPresent(), "cooldown is per context");
        now.addAndGet(2);
        assertTrue(g.offer(MascotContext.PAYMENT_NOT_FOUND).isPresent(), "after the cooldown it may speak again");
        assertTrue(g.offer(MascotContext.FIRST_VISIT_CHAIN_DATA).isPresent());
        now.addAndGet(10 * MascotGuide.COOLDOWN_MS);
        assertTrue(g.offer(MascotContext.FIRST_VISIT_CHAIN_DATA).isEmpty(), "first-visit guidance: once per session, never again");
        assertTrue(g.ask(MascotContext.FIRST_VISIT_CHAIN_DATA).isPresent(), "an explicit click is always answered");
        assertTrue(g.ask(MascotContext.CHAIN_OFFLINE).isPresent());
        assertTrue(g.ask(MascotContext.CHAIN_OFFLINE).isPresent());
        g.resetSession();
        assertTrue(g.offer(MascotContext.FIRST_VISIT_CHAIN_DATA).isPresent());
    }

    @Test
    void theGuideIsLocalShortProfessionalAndNeverCoversErrorsOrSecurity() throws Exception {
        StaticMascotGuideProvider p = new StaticMascotGuideProvider();
        for (MascotContext c : MascotContext.values()) {
            String n = c.name();
            assertFalse(n.contains("AUTH") || n.contains("PASSWORD") || n.contains("MFA") || n.contains("SECRET") || n.contains("OTP") || n.contains("PERMISSION") || n.contains("SECURITY"), "no security context: " + n);
            p.hint(c).ifPresent(h -> {
                assertTrue(h.text().length() <= 140 && h.text().length() >= 12, c + " is short");
                assertFalse(h.text().contains("!") || h.text().matches(".*[\\p{So}\\p{Cs}].*"), c + ": professional tone, no exclamations or emoji");
                assertFalse(h.text().toLowerCase().matches(".*(password|secret|token|mfa|otp|enable|please|you must|hurry).*"), c + ": never pressures, never asks to enable anything");
            });
        }
        assertTrue(p.hint(MascotContext.CHAIN_MISMATCH).isEmpty(), "a network mismatch is never softened by the mascot");
        assertEquals("The local BYX node is offline.", p.hint(MascotContext.CHAIN_OFFLINE).orElseThrow().text());
        assertEquals("No payment was found with that ID.", p.hint(MascotContext.PAYMENT_NOT_FOUND).orElseThrow().text());
        assertEquals(MascotState.ATTENTION, p.hint(MascotContext.CERTIFICATE_NOT_FOUND).orElseThrow().reaction());
        assertEquals(Optional.of(MascotContext.CHAIN_SYNCING), MascotContext.forChain("SYNCING"));
        assertTrue(MascotContext.forChain("NOPE").isEmpty() && MascotContext.forChain(null).isEmpty());
        String src = Files.readString(Path.of("src/main/java/panel/mascot/StaticMascotGuideProvider.java")).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
        assertFalse(src.contains("http") || src.contains("java.net") || src.contains("openai") || src.toLowerCase().contains("llm"), "the guide depends on no network, LLM or server");
    }

    // ------------------------------------------------------------------------------------------------ prioridade / políticas
    @Test
    void presencePriorityIsOrderedAndThePointerNeverOverridesAFunctionalState() {
        MascotPriority[] o = MascotPriority.values();
        assertEquals(List.of(MascotPriority.IDLE, MascotPriority.POINTER, MascotPriority.CONTEXT_GUIDE, MascotPriority.ATTENTION_NOTIFICATION, MascotPriority.FUNCTIONAL, MascotPriority.ERROR_SECURITY), List.of(o));
        assertFalse(MascotPriority.mayShow(MascotPriority.POINTER, MascotPriority.FUNCTIONAL));
        assertFalse(MascotPriority.mayShow(MascotPriority.CONTEXT_GUIDE, MascotPriority.ATTENTION_NOTIFICATION), "an automatic hint never interrupts a one-shot");
        assertFalse(MascotPriority.mayShow(MascotPriority.ATTENTION_NOTIFICATION, MascotPriority.FUNCTIONAL));
        assertFalse(MascotPriority.mayShow(MascotPriority.FUNCTIONAL, MascotPriority.ERROR_SECURITY));
        assertTrue(MascotPriority.mayShow(MascotPriority.FUNCTIONAL, MascotPriority.CONTEXT_GUIDE));
        assertTrue(MascotPriority.mayShow(MascotPriority.POINTER, MascotPriority.IDLE));
        assertFalse(MascotPriority.mayShow(MascotPriority.POINTER, MascotPriority.POINTER), "a tie yields to what is already there");
        assertEquals(MascotPriority.FUNCTIONAL, MascotPriority.of(MascotState.THINKING));
        assertEquals(MascotPriority.FUNCTIONAL, MascotPriority.of(MascotState.SYNCING));
        assertEquals(MascotPriority.ATTENTION_NOTIFICATION, MascotPriority.of(MascotState.ATTENTION));
        assertEquals(MascotPriority.IDLE, MascotPriority.of(MascotState.IDLE));
    }

    @Test
    void sizesAndAnchorsAreClosedSemanticSets() {
        assertEquals(64, MascotSize.SMALL.px());
        assertEquals(96, MascotSize.MEDIUM.px());
        assertEquals(144, MascotSize.LARGE.px());
        assertEquals(List.of(MascotAnchor.TOP_RIGHT, MascotAnchor.BOTTOM_RIGHT, MascotAnchor.INLINE, MascotAnchor.CENTER_EMPTY_STATE), List.of(MascotAnchor.values()));
    }

    @Test
    void noGlobalInputHookNoNetworkNoSecurityContextAnywhereInTheMascotPackage() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/panel/mascot"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
                for (String banned : new String[] {"java.awt", "MouseInfo", "java.awt.Robot", "GlobalScreen", "jnativehook", "CGEvent", "NSEvent", "Accessibility", "AXUIElement", "getDefaultToolkit", "com.sun.jna", "ProcessBuilder"}) {
                    assertFalse(src.contains(banned), f.getFileName() + " must not use " + banned + " (only the mouse events JavaFX already delivers to the app window)");
                }
                assertFalse(src.contains("java.net.http") || src.contains("HttpClient") || src.contains("Socket") || src.contains("URLConnection") || src.contains("openConnection") || src.contains("InetAddress"), f.getFileName() + ": the mascot has no network dependency (java.net.URL is only the classpath resource handle)");
            }
        }
    }

    @Test
    void processingAssetIsMuchSmallerAndTheRigExists() throws Exception {
        long processing = Files.size(Path.of("src/main/resources/panel/mascot/states/processing.png"));
        assertTrue(processing < 3_000_000, "PROCESSING sheet was 4.6 MB, now " + processing / 1024 + " KB");
        MascotManifest m = MascotManifest.load(MascotManifest.RESOURCE);
        var e = m.entry(MascotState.PROCESSING).orElseThrow();
        assertEquals(0.75, e.sheetScale(), 1e-9);
        assertEquals(20, e.fps());
        assertTrue(m.entry(MascotState.IDLE).orElseThrow().rig() != null, "IDLE has a body + two eyes rig");
    }
}
