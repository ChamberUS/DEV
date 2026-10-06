package panel.systemview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.design.RegionState;
import panel.design.StatusState;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;

/** Passo 12: estados reais de sistema, recuperação representada, erros sem vazamento, onboarding sem efeito colateral. */
class SystemScreensTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static SystemStatusModel.Inputs inputs(boolean backend, String feed, boolean session) {
        Snapshot s = new Snapshot();
        s.backendOnline = backend;
        TraderSnapshot t = new TraderSnapshot();
        t.feed = feed;
        return new SystemStatusModel.Inputs(s, t, ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured"), session, false, false, false);
    }

    private static SystemStatusModel.Component find(List<SystemStatusModel.Component> l, String id) {
        return l.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static void show(Node n) {
        Scene s = new Scene((Parent) n, 1440, 900);
        ByxTheme.apply(s);
        n.applyCss();
        ((Parent) n).layout();
    }

    private static String texts(Node n) {
        StringBuilder b = new StringBuilder();
        collect(n, b);
        return b.toString();
    }

    private static void collect(Node n, StringBuilder b) {
        if (n instanceof Labeled l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), b);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, b));
        }
    }

    @Test
    void systemStatusNeverInventsHealth() {
        var comps = SystemStatusModel.components(inputs(false, null, false));
        assertEquals(List.of("backend", "feed", "capture", "research", "node", "service", "wallet", "auth"), comps.stream().map(SystemStatusModel.Component::id).toList());
        assertEquals(StatusState.UNAVAILABLE, find(comps, "backend").state());
        assertEquals(StatusState.UNKNOWN, find(comps, "feed").state(), "an unreadable feed is UNKNOWN, never OPERATIONAL");
        assertEquals(StatusState.UNKNOWN, find(comps, "capture").state());
        assertEquals(StatusState.UNAVAILABLE, find(comps, "auth").state());
        assertTrue(comps.stream().noneMatch(c -> c.state() == StatusState.OPERATIONAL));
        var ok = SystemStatusModel.components(inputs(true, "NOT_CONFIGURED", true));
        assertEquals(StatusState.OPERATIONAL, find(ok, "backend").state());
        assertEquals(StatusState.UNAVAILABLE, find(ok, "feed").state());
        assertTrue(find(ok, "feed").expected(), "nothing configured is the neutral expected UNAVAILABLE");
        assertEquals(StatusState.OPERATIONAL, find(ok, "auth").state());
        assertTrue(find(ok, "backend").retry() && find(ok, "node").retry());
        assertFalse(find(ok, "feed").retry() || find(ok, "capture").retry() || find(ok, "wallet").retry(), "no Retry where no real attempt exists");
        assertEquals("—", SystemStatusModel.age(null, NOW));
        assertEquals("30s ago", SystemStatusModel.age(NOW.minusSeconds(30), NOW));
    }

    @Test
    void recoveryRepresentsRealTransitionsOnly() {
        RecoveryTracker t = new RecoveryTracker();
        assertEquals(RecoveryTracker.Event.NONE, t.update("feed", StatusState.UNAVAILABLE, NOW));
        assertFalse(t.lost("feed"), "a service that was never up did not drop");
        assertEquals(RecoveryTracker.Event.NONE, t.update("backend", StatusState.OPERATIONAL, NOW));
        assertEquals(RecoveryTracker.Event.LOST, t.update("backend", StatusState.UNAVAILABLE, NOW.plusSeconds(1)));
        assertEquals(RecoveryTracker.State.DISCONNECTED, t.state("backend"));
        assertEquals(RecoveryTracker.Event.NONE, t.update("backend", StatusState.UNAVAILABLE, NOW.plusSeconds(2)), "no repeated event");
        t.retryStarted("backend");
        assertEquals(RecoveryTracker.Event.RETRY_FAILED, t.retryFinished("backend", StatusState.UNAVAILABLE));
        assertEquals(RecoveryTracker.State.RETRY_FAILED, t.state("backend"));
        assertTrue(t.lost("backend"), "RETRY FAILED keeps the bar and Retry");
        assertEquals(RecoveryTracker.Event.RESTORED, t.update("backend", StatusState.OPERATIONAL, NOW.plusSeconds(10)));
        assertEquals(RecoveryTracker.State.RESTORED, t.state("backend"));
        assertEquals(RecoveryTracker.Event.NONE, t.update("backend", StatusState.OPERATIONAL, NOW.plusSeconds(11)));
        assertEquals(RecoveryTracker.State.RESTORED, t.state("backend"), "restored is held for 3 s");
        t.update("backend", StatusState.OPERATIONAL, NOW.plusSeconds(10).plus(RecoveryTracker.RESTORED_HOLD));
        assertEquals(RecoveryTracker.State.CONNECTED, t.state("backend"));
        t.update("node", StatusState.OPERATIONAL, NOW);
        t.update("node", StatusState.RECONNECTING, NOW.plusSeconds(1));
        assertEquals(RecoveryTracker.State.RECONNECTING, t.state("node"), "RECONNECTING only when the service reports it");
        assertEquals(List.of("node"), t.lostServices());
    }

    @Test
    void firstRunAndStartupContracts() {
        var F = FirstRunModel.Situation.class;
        assertEquals(FirstRunModel.Situation.FIRST_INSTALL, FirstRunModel.resolve(true, false, false, false));
        assertEquals(FirstRunModel.Situation.RETURNING_USER, FirstRunModel.resolve(false, true, false, false));
        assertEquals(FirstRunModel.Situation.NO_SESSION, FirstRunModel.resolve(false, false, false, false));
        assertEquals(FirstRunModel.Situation.VALID_SESSION, FirstRunModel.resolve(false, true, true, false));
        assertEquals(FirstRunModel.Situation.SESSION_EXPIRED, FirstRunModel.resolve(false, true, true, true));
        assertTrue(FirstRunModel.showsWelcome(FirstRunModel.Situation.FIRST_INSTALL));
        assertFalse(FirstRunModel.showsWelcome(FirstRunModel.Situation.RETURNING_USER) || FirstRunModel.showsWelcome(FirstRunModel.Situation.NO_SESSION));
        assertTrue(FirstRunModel.showsOnboarding(false, true));
        assertFalse(FirstRunModel.showsOnboarding(true, true) || FirstRunModel.showsOnboarding(false, false));
        StartupModel m = new StartupModel();
        long first = m.begin();
        assertFalse(m.showsStartup(first, Duration.ofMillis(799)), "fast startup shows no screen");
        assertTrue(m.showsStartup(first, Duration.ofMillis(800)));
        long second = m.begin();
        assertFalse(m.ready(first), "an older start never wins");
        assertTrue(m.ready(second));
        assertFalse(m.showsStartup(second, Duration.ofSeconds(5)), "ready: no startup screen");
        assertEquals(4, StartupModel.STEPS.size());
    }

    @Test
    void regionMatrixLimitsEachRegionToItsRealStates() {
        assertEquals(19, RegionMatrix.all().size());
        assertEquals(java.util.Set.of(RegionState.LOCKED), RegionMatrix.of("Final holdout"));
        assertTrue(RegionMatrix.allows("Sessions list", RegionState.UNAVAILABLE));
        assertFalse(RegionMatrix.allows("Order book", RegionState.LOCKED));
        assertFalse(RegionMatrix.allows("Notifications", RegionState.UNAVAILABLE),
                "the reference matrix has no UNAVAILABLE for Notifications; the product keeps UNAVAILABLE on purpose (no backend: not EMPTY)");
    }

    @Test
    void errorArchitectureAndReferenceCodeLeakNothing() {
        assertEquals(9, ErrorArchitecture.values().length);
        assertEquals(List.of(ErrorArchitecture.AUTH, ErrorArchitecture.UNEXPECTED),
                java.util.Arrays.stream(ErrorArchitecture.values()).filter(e -> e.blocking).toList(), "not every error is a modal");
        String code = ErrorArchitecture.referenceCode(new IllegalStateException("token=abc /Users/x/secret"), NOW);
        assertTrue(code.matches("ERR-[0-9A-F]{6}"), code);
        assertEquals(code, ErrorArchitecture.referenceCode(new IllegalStateException("something else"), NOW), "derived from class and time, never the message");
    }

    @Test
    void onboardingHasSixStepsAndOnlyStoresTheChoice() throws Exception {
        DeskHarness.fx(() -> {
            List<OnboardingDialog.Result> results = new ArrayList<>();
            OnboardingDialog d = new OnboardingDialog(new MotionService(), OnboardingContent.load(), "TRADING", results::add);
            show(d);
            assertEquals(6, OnboardingContent.load().steps().size());
            assertEquals(0, d.step());
            assertTrue(d.backButton().isDisabled());
            for (int i = 0; i < 5; i++) {
                d.nextButton().fire(); // cinco Next rápidos
            }
            assertEquals(5, d.step());
            assertEquals(1, d.activeDots(), "one active step");
            assertEquals("Finish", d.nextButton().getText());
            assertFalse(d.skipButton().isVisible(), "Skip is not offered on the last step");
            d.nextButton().fire();
            d.nextButton().fire(); // duplo clique não gera dois resultados
            assertEquals(List.of(new OnboardingDialog.Result("TRADING", true)), results);
        });
        DeskHarness.fx(() -> {
            List<OnboardingDialog.Result> results = new ArrayList<>();
            OnboardingDialog d = new OnboardingDialog(new MotionService(), OnboardingContent.load(), "TRADING", results::add);
            show(d);
            d.go(1);
            ((javafx.scene.control.ToggleButton) d.lookupAll(".byx-choice").stream().filter(n -> ((javafx.scene.control.ToggleButton) n).getText().startsWith("BYX")).findFirst().orElseThrow()).fire();
            assertEquals("BYX", d.workspace());
            d.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            assertEquals(List.of(new OnboardingDialog.Result("BYX", false)), results, "Esc = Skip");
        });
        for (String f : List.of("OnboardingDialog", "OnboardingContent")) {
            String src = Files.readString(Path.of("src/main/java/panel/systemview/" + f + ".java"));
            for (String banned : List.of("trading =", "ctx.", "byxWallets", "DEVNET()", "FINAL_HOLDOUT =", "validationStatus", "ProcessBuilder", "sign(", "broadcast")) {
                assertFalse(src.contains(banned), f + " must not touch " + banned);
            }
        }
    }

    @Test
    void errorScreensAreSafeAndDoNotRedirect() throws Exception {
        DeskHarness.fx(() -> {
            List<String> nav = new ArrayList<>();
            PageUnavailableScreen p = new PageUnavailableScreen(new MotionService(), () -> null, nav::add, "t-desk");
            show(p.node());
            p.setRequested("t-nowhere");
            assertFalse(p.backVisible(), "Return to previous only when a previous route exists");
            assertTrue(texts(p.node()).contains("Page unavailable") && texts(p.node()).contains("Go to default workspace"));
            assertTrue(nav.isEmpty(), "it never redirects by itself");
            PageUnavailableScreen q = new PageUnavailableScreen(new MotionService(), () -> "t-desk", nav::add, "t-desk");
            show(q.node());
            q.onShow();
            assertTrue(q.backVisible());
            UnexpectedErrorScreen u = new UnexpectedErrorScreen(new MotionService(), "ERR-ABC123", "the same problem happened again", () -> nav.add("retry"), () -> { }, () -> { });
            show(u);
            String t = texts(u);
            assertTrue(t.contains("Something went wrong") && t.contains("ERR-ABC123") && t.contains("Retry is off"));
            assertFalse(t.contains("Exception") || t.contains("at java") || t.contains("/Users/"));
            assertTrue(u.retryButton().isDisabled());
            var perm = ErrorPatterns.permissionRequired("Research", "Administrators only.", "Ask an administrator.");
            show(perm);
            assertTrue(texts(perm).contains("Permission required"));
            assertEquals(36, ErrorPatterns.globalBar("x").getPrefHeight());
        });
    }

    @Test
    void systemStatusUpdatesInPlaceAndStopsWhenHidden() throws Exception {
        DeskHarness.fx(() -> {
            MotionService m = new MotionService();
            RecoveryTracker tracker = new RecoveryTracker();
            boolean[] backend = {false};
            List<String> retries = new ArrayList<>();
            SystemStatusScreen s = new SystemStatusScreen(m, CLOCK, () -> inputs(backend[0], "NOT_CONFIGURED", true), tracker, retries::add);
            show(s.node());
            assertEquals(8, s.rowsList().size());
            assertFalse(s.timerRunning());
            s.onShow();
            assertTrue(s.timerRunning());
            int rebuilds = s.rebuilds();
            backend[0] = true;
            for (int i = 0; i < 20; i++) {
                s.onSnapshot(null);
                s.onShow();
            }
            assertEquals(rebuilds, s.rebuilds(), "status updates reuse the rows");
            String t = texts(s.node());
            assertTrue(t.contains("Backend") && t.contains("BYX node") && t.contains("Authentication"));
            s.onHide();
            assertFalse(s.timerRunning());
            s.dispose();
            assertEquals(0, m.runningLoops());
        });
    }
}
