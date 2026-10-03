package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import panel.auth.AccessDecision;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.motion.ViewTransitionService;
import panel.motion.icon.AnimatedIcon;
import panel.motion.icon.AnimationAsset;
import panel.motion.icon.AnimationRepository;
import panel.motion.icon.IconPaths;
import panel.motion.icon.SvgIcon;
import panel.security.AccessDeniedException;
import panel.ui.motion.BotAvatar;
import panel.ui.toast.ToastQueue;

class MotionIconTest {
    private static MotionService motion(MotionPreference p) {
        MotionService m = new MotionService();
        m.preference.set(p);
        return m;
    }

    @Test
    void tokensAreScaledByPreference() {
        assertEquals(MotionTokens.EMPHASIS, motion(MotionPreference.FULL).scale(MotionTokens.EMPHASIS));
        assertEquals(MotionTokens.REDUCED_MAX, motion(MotionPreference.REDUCED).scale(MotionTokens.EMPHASIS));
        assertEquals(MotionTokens.MICRO, motion(MotionPreference.REDUCED).scale(MotionTokens.MICRO));
        assertEquals(Duration.ZERO, motion(MotionPreference.OFF).scale(MotionTokens.EMPHASIS));
    }

    @Test
    void fullMotionStartsHiddenAndOffsetThenAnimates() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            Region r = new Region();
            m.fadeSlideIn(r, 0, 10, MotionTokens.STANDARD);
            assertEquals(0, r.getOpacity());
            assertEquals(10, r.getTranslateY());
        });
    }

    @Test
    void reducedMotionOnlyFadesWithoutDisplacement() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.REDUCED);
            Region r = new Region();
            m.fadeSlideIn(r, 0, 10, MotionTokens.STANDARD);
            assertEquals(0, r.getOpacity());
            assertEquals(0, r.getTranslateY());
            m.shiftTo(r, 2, 2, MotionTokens.MICRO);
            assertEquals(0, r.getTranslateX());
            assertNull(m.loop(r, () -> new javafx.animation.Timeline()));
        });
    }

    @Test
    void offMotionIsInstantAndHasNoLoops() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.OFF);
            Region r = new Region();
            m.fadeSlideIn(r, 0, 10, MotionTokens.STANDARD);
            assertEquals(1, r.getOpacity());
            assertEquals(0, r.getTranslateY());
            assertNull(m.loop(r, () -> new javafx.animation.Timeline()));
            assertEquals(0, m.loopCount());
            assertFalse(m.iconsAnimated());
        });
    }

    @Test
    void repeatedHoverDoesNotAccumulateAnimations() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            Region r = new Region();
            for (int i = 0; i < 500; i++) {
                m.shiftTo(r, i % 2 == 0 ? 2 : 0, 0, MotionTokens.MICRO);
                m.fadeTo(r, i % 2 == 0 ? 1 : 0.5, MotionTokens.FAST);
            }
            assertEquals(0, m.loopCount());
            assertEquals(1, r.getProperties().keySet().stream().filter(k -> "motion.anim".equals(k)).count());
        });
    }

    @Test
    void loopsAreLimitedToFullAndStoppedWhenPreferenceDrops() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            Region r = new Region();
            assertNotNull(m.loop(r, () -> new javafx.animation.Timeline()));
            assertEquals(1, m.loopCount());
            m.preference.set(MotionPreference.OFF);
            assertEquals(0, m.loopCount());
        });
    }

    @Test
    void loopsOfHiddenPagesOrHiddenWindowArePaused() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            javafx.scene.layout.Pane root = new javafx.scene.layout.Pane();
            Region r = new Region();
            root.getChildren().add(r);
            new javafx.scene.Scene(root);
            m.loop(r, () -> new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(1))));
            assertEquals(1, m.runningLoops());
            root.setVisible(false);
            m.refreshLoops();
            assertEquals(0, m.runningLoops());
            root.setVisible(true);
            m.setActive(false);
            assertEquals(0, m.runningLoops());
            m.setActive(true);
            assertEquals(1, m.runningLoops());
        });
    }

    @Test
    void workspaceTransitionKeepsOnlyTargetAndFadingPreviousVisible() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            ViewTransitionService vt = new ViewTransitionService(m);
            List<Node> all = new ArrayList<>(List.of(new Region(), new Region(), new Region()));
            all.forEach(n -> n.setVisible(false));
            vt.show(all, all.get(0), false);
            vt.show(all, all.get(1), true);
            vt.show(all, all.get(2), false);
            assertTrue(all.get(2).isVisible());
            assertFalse(all.get(0).isVisible());
        });
    }

    @Test
    void offTransitionIsInstant() throws Exception {
        FxSupport.fx(() -> {
            ViewTransitionService vt = new ViewTransitionService(motion(MotionPreference.OFF));
            List<Node> all = List.of(new Region(), new Region());
            vt.show(all, all.get(0), false);
            vt.show(all, all.get(1), true);
            assertTrue(all.get(1).isVisible());
            assertFalse(all.get(0).isVisible());
            assertEquals(1, all.get(1).getOpacity());
        });
    }

    @Test
    void transitionsDoNotChangeAuthorization() throws Exception {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        f.auth.login("alice", "temporary-pass-1".toCharArray());
        FxSupport.fx(() -> {
            ViewTransitionService vt = new ViewTransitionService(motion(MotionPreference.FULL));
            List<Node> all = List.of(new Region(), new Region());
            vt.show(all, all.get(0), false);
            vt.show(all, all.get(1), true);
        });
        assertEquals(AccessDecision.FORBIDDEN_NOT_ADMIN, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
    }

    @Test
    void bundledLottieAssetsRenderNatively() throws Exception {
        String[] r = FxSupport.fx(() -> {
            AnimationRepository repo = new AnimationRepository(motion(MotionPreference.FULL));
            AnimatedIcon check = repo.icon("check", 32, "ok");
            AnimatedIcon bot = repo.icon("bot", 32, "ok");
            check.play();
            bot.loop();
            boolean playing = bot.renderer().equals("lottie4j");
            bot.stop();
            return new String[] {check.renderer(), bot.renderer(), String.valueOf(playing)};
        });
        assertEquals("lottie4j", r[0]);
        assertEquals("lottie4j", r[1]);
    }

    @Test
    void missingAndInvalidLottieAssetsFallBackToNativeIcon() throws Exception {
        IconPaths.CATALOG.put("t-missing", new AnimationAsset("t-missing", "M4 4h16v16H4z", SvgIcon.Kind.POP, "/animations/does-not-exist.json"));
        IconPaths.CATALOG.put("t-invalid", new AnimationAsset("t-invalid", "M4 4h16v16H4z", SvgIcon.Kind.POP, "/animations/invalid.json"));
        try {
            String[] r = FxSupport.fx(() -> {
                AnimationRepository repo = new AnimationRepository(motion(MotionPreference.FULL));
                AnimatedIcon a = repo.icon("t-missing", 24, "ok");
                AnimatedIcon b = repo.icon("t-invalid", 24, "ok");
                a.play();
                b.play();
                return new String[] {a.renderer(), b.renderer(), String.valueOf(repo.failed("/animations/does-not-exist.json")), String.valueOf(repo.failed("/animations/invalid.json"))};
            });
            assertEquals("svg-animated", r[0]);
            assertEquals("svg-animated", r[1]);
            assertEquals("true", r[2]);
            assertEquals("true", r[3]);
        } finally {
            IconPaths.CATALOG.remove("t-missing");
            IconPaths.CATALOG.remove("t-invalid");
        }
    }

    @Test
    void disablingAnimatedIconsUsesStaticFrames() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = motion(MotionPreference.FULL);
            m.animatedIcons.set(false);
            AnimationRepository repo = new AnimationRepository(m);
            repo.setLottieEnabled(false);
            AnimatedIcon i = repo.icon("check", 24, "ok");
            i.play();
            assertEquals("svg-animated", i.renderer());
            assertEquals(1, i.node().getOpacity());
            assertEquals(0, repo.cached());
        });
    }

    @Test
    void animationsDoNotBlockTheFxThread() throws Exception {
        FxSupport.fx(() -> {
            AnimationRepository repo = new AnimationRepository(motion(MotionPreference.FULL));
            for (String n : IconPaths.CATALOG.keySet()) {
                repo.icon(n, 24, "ok").play();
            }
        });
        long t0 = System.nanoTime();
        FxSupport.fx(() -> { });
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500);
    }

    @Test
    void toastQueueEvictsOldestBeyondLimit() {
        ToastQueue<String> q = new ToastQueue<>(3);
        List<String> evicted = new ArrayList<>();
        for (String s : List.of("a", "b", "c", "d", "e")) {
            q.add(s, evicted::add);
        }
        assertEquals(List.of("a", "b"), evicted);
        assertEquals(3, q.size());
    }

    @Test
    void botStatesParseFromBackendText() {
        assertEquals(BotAvatar.State.MONITORING, BotAvatar.State.parse("RESEARCH / MONITORING"));
        assertEquals(BotAvatar.State.OFFLINE, BotAvatar.State.parse(null));
        assertEquals(BotAvatar.State.ERROR, BotAvatar.State.parse("error"));
    }
}
