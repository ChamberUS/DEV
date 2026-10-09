package panel.researchview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;
import panel.model.Snapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.security.AccessDeniedException;
import panel.service.CaptureMonitorService;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;
import panel.tradeview.DeskHarness;
import panel.tradeview.DeskMode;
import panel.tradeview.DeskNodes;

/** Capture V2: ciclo de vida (monitor e timer só visível + autorizado), estados reais, uma animação por RUNNING, gates. */
class CaptureScreenTest {
    private static final class Env {
        final MotionService motion = new MotionService();
        final DeskHarness.TestClock clock = new DeskHarness.TestClock();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicBoolean session = new AtomicBoolean(true);
        volatile CaptureSnapshot fixture = ResearchFixtures.capture(State.RUNNING);
        final CaptureMonitorService service = new CaptureMonitorService(() -> {
            reads.incrementAndGet();
            return fixture;
        }, () -> () -> {
            if (!allowed.get()) {
                throw new AccessDeniedException("denied");
            }
        }, () -> allowed.get() && session.get());
        Snapshot snapshot = ResearchFixtures.trainReady(5, true);
        final CaptureScreen screen = new CaptureScreen(motion, clock, service,
                () -> allowed.get() && session.get(), () -> snapshot);
        final ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
        final ByxShell shell = new ByxShell(router, motion, new LegacyHost());
        Scene scene;

        Env() {
            screen.node().setVisible(false); // como no app: a View só é exibida pelo roteador
            shell.v2Content().getChildren().add(screen.node());
            shell.showV2(true);
            router.request("capture");
        }

        void size(int w, int h) {
            if (scene != null) {
                scene.setRoot(new javafx.scene.layout.Pane());
            }
            scene = new Scene(shell, w, h);
            ByxTheme.apply(scene);
            layout();
        }

        /** Exibe a View como o roteador faria, mas sem o monitor de fundo (os testes de renderização não leem nada). */
        void display() {
            screen.node().setVisible(true);
            service.stop();
            reads.set(0);
            layout();
        }

        void layout() {
            for (int i = 0; i < 3; i++) {
                shell.applyCss();
                shell.layout();
            }
        }

        Bounds rect(Node n) {
            return n.localToScene(n.getLayoutBounds());
        }

        void close() {
            screen.dispose();
            service.close();
            shell.dispose();
        }
    }

    private static void rect(String what, Bounds b, double x, double y, double w, double h) {
        assertEquals(x, b.getMinX(), 1, what + " x");
        assertEquals(y, b.getMinY(), 1, what + " y");
        assertEquals(w, b.getWidth(), 1, what + " w");
        if (h > 0) {
            assertEquals(h, b.getHeight(), 1, what + " h");
        }
    }

    @Test
    void monitorAndTimerExistOnlyWhileVisibleAndAuthorised() throws Exception {
        Env e = DeskHarness.fx(Env::new);
        DeskHarness.fx(e.screen::onShow);
        try {
            DeskHarness.fx(() -> {
                e.size(1440, 900);
                assertFalse(e.screen.observing());
                e.screen.node().setVisible(true);
                e.screen.onSnapshot(null);
                assertTrue(e.screen.observing());
                assertTrue(e.screen.panel().timerRunning(), "visible: one timer");
            });
            Thread.sleep(1500); // o monitor lê no worker e publica na thread FX
            DeskHarness.fx(() -> {
                assertTrue(e.reads.get() >= 1, "the read-only monitor ran");
                assertEquals(State.RUNNING, e.screen.panel().snapshot().state());
                e.screen.node().setVisible(false);
                assertFalse(e.screen.observing());
                assertFalse(e.screen.panel().timerRunning(), "hidden: timer stopped");
                assertEquals(0, e.motion.runningLoops(), "hidden: no animation");
                assertEquals(null, e.screen.panel().snapshot(), "hidden: the card is cleared");
            });
            int reads = e.reads.get();
            Thread.sleep(1200);
            assertEquals(reads, e.reads.get(), "hidden: the monitor does not read");
            DeskHarness.fx(() -> {
                for (int i = 0; i < 20; i++) { // 20 ciclos: um timer, nunca acumula
                    e.screen.node().setVisible(true);
                    e.screen.onSnapshot(null);
                    assertTrue(e.screen.panel().timerRunning());
                    e.screen.node().setVisible(false);
                    assertFalse(e.screen.panel().timerRunning());
                }
                e.screen.node().setVisible(true);
                e.screen.onSnapshot(null);
                assertTrue(e.screen.panel().timerRunning());
                e.screen.dispose();
                assertFalse(e.screen.panel().timerRunning(), "dispose: zero timers");
                assertFalse(e.screen.observing());
            });
        } finally {
            DeskHarness.fx(e::close);
        }
    }

    @Test
    void deniedOrExpiredAdminNeverStartsAnything() throws Exception {
        Env e = DeskHarness.fx(Env::new);
        DeskHarness.fx(e.screen::onShow);
        try {
            DeskHarness.fx(() -> {
                e.size(1440, 900);
                e.allowed.set(false);
                e.screen.node().setVisible(true);
                e.screen.onSnapshot(null);
                assertFalse(e.screen.observing());
                assertFalse(e.screen.panel().timerRunning());
                e.allowed.set(true);
                e.screen.onSnapshot(null);
                assertTrue(e.screen.observing());
                e.session.set(false); // sessão de admin expirou
                e.screen.onSnapshot(null);
                assertFalse(e.screen.observing());
                assertFalse(e.screen.panel().timerRunning());
            });
        } finally {
            DeskHarness.fx(e::close);
        }
    }

    @Test
    void realStatesAreTheOnesTheMonitorReportsAndGapsAreNotReportedNotZero() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            CapturePanel p = e.screen.panel();
            for (State st : State.values()) {
                p.show(ResearchFixtures.capture(st));
                assertEquals(st.name(), p.stateBadge().getText());
                assertEquals("Not reported", p.events().valueLabel().getText(), "events are not reported, never 0");
                assertEquals("Not reported", p.retry().valueLabel().getText());
                assertTrue(p.integrity().stream().allMatch(r -> r.valueLabel().getText().equals("NOT REPORTED")));
                assertTrue(p.trackNotes().stream().allMatch(l -> l.getText().equals("Not reported")), "no history: never 'None recorded'");
            }
            assertEquals(4, p.legend().size(), "only the four states the monitor knows");
            assertEquals(List.of("RUNNING", "STALE", "STOPPED", "UNKNOWN"), p.legend().stream().map(javafx.scene.control.Label::getText).toList());
            p.show(ResearchFixtures.capture(State.RUNNING));
            assertEquals("ETHUSDT · Binance USD-M Futures", p.title().getText());
            assertTrue(p.uptime().getText().matches("\\d\\d:\\d\\d:\\d\\d"));
            assertTrue(p.sessionBarText().getText().startsWith("Running · "));
            assertTrue(p.legend().get(0).getStyleClass().contains("current"));
            assertTrue(p.legend().get(1).getStyleClass().contains("dim"));
            p.show(ResearchFixtures.capture(State.UNKNOWN));
            assertFalse(p.sessionBar().isManaged(), "unknown has no session bar");
            p.clear();
            assertEquals("N/A", p.title().getText());
            assertEquals("UNKNOWN", p.stateBadge().getText());
            e.close();
        });
    }

    @Test
    void runningHasExactlyOneAnimationAndItFollowsTheState() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            CapturePanel p = e.screen.panel();
            p.start(() -> { });
            p.show(ResearchFixtures.capture(State.RUNNING));
            e.motion.refreshLoops();
            assertEquals(1, e.motion.runningLoops());
            for (int i = 0; i < 100; i++) {
                p.show(ResearchFixtures.capture(State.RUNNING));
            }
            assertEquals(1, e.motion.runningLoops(), "100 RUNNING results keep one instance");
            for (int i = 0; i < 20; i++) {
                p.show(ResearchFixtures.capture(State.STOPPED));
                assertEquals(0, e.motion.runningLoops());
                p.show(ResearchFixtures.capture(State.RUNNING));
                assertEquals(1, e.motion.runningLoops());
            }
            p.show(ResearchFixtures.capture(State.STALE));
            assertEquals(0, e.motion.runningLoops());
            p.show(ResearchFixtures.capture(State.RUNNING));
            p.stop();
            assertEquals(0, e.motion.runningLoops(), "stopping the card removes it");
            e.close();
        });
    }

    @Test
    void resultsUpdateInPlaceAndTheTimerReadsNothing() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1920, 1080);
            e.display();
            CapturePanel p = e.screen.panel();
            p.start(() -> { });
            p.show(ResearchFixtures.capture(State.RUNNING));
            int nodes = p.nodeCount();
            Set<Node> before = new java.util.HashSet<>();
            DeskNodes.walk(p, before::add);
            for (int i = 0; i < 150; i++) {
                p.show(ResearchFixtures.capture(i % 7 == 0 ? State.STALE : State.RUNNING));
                e.clock.now = e.clock.now.plusSeconds(1);
                p.updateTimers();
            }
            Set<Node> after = new java.util.HashSet<>();
            DeskNodes.walk(p, after::add);
            assertEquals(nodes, p.nodeCount());
            assertEquals(before, after, "the detail grid is built once and updated in place");
            assertEquals(0, e.reads.get(), "the presentation timer and show() perform no reads");
            e.close();
        });
    }

    @Test
    void geometryMatchesTheReferenceAt1440And1920() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            e.screen.panel().show(ResearchFixtures.capture(State.RUNNING));
            e.layout();
            Node root = e.screen.panel();
            assertEquals(DeskMode.COMPACT, e.screen.panel().mode());
            rect("header", e.rect(root.lookup("#capture-header")), 88, 70, 1332, 82);
            rect("process", e.rect(root.lookup("#capture-process")), 88, 166, 462, 116);
            rect("session", e.rect(root.lookup("#capture-session")), 564, 166, 462, 116);
            rect("growth", e.rect(root.lookup("#capture-growth")), 88, 296, 462, 0);
            rect("storage", e.rect(root.lookup("#capture-storage")), 564, 296, 462, 0);
            rect("scientific integrity column", e.rect(root.lookup("#capture-side")), 1040, 166, 380, 0);
            Bounds timeline = e.rect(root.lookup("#capture-timeline"));
            assertEquals(e.rect(root.lookup("#capture-growth")).getMaxY() + 14, timeline.getMinY(), 1, "timeline sits under growth/storage");
            rect("timeline", timeline, 88, timeline.getMinY(), 938, 0);
            e.size(1920, 1080);
            e.display();
            e.screen.panel().show(ResearchFixtures.capture(State.RUNNING));
            e.layout();
            double mainW = 1920 - 68 - 40;
            rect("header", e.rect(root.lookup("#capture-header")), 88, 70, mainW, 82);
            rect("side column", e.rect(root.lookup("#capture-side")), 88 + mainW - 460, 166, 460, 0);
            Bounds tall = e.rect(root.lookup("#capture-timeline"));
            assertTrue(tall.getHeight() > timeline.getHeight() + 80, "1920 has taller timeline lanes");
            e.close();
        });
    }

    @Test
    void theTimelineNeverStretchesToTheWholeHeight() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            e.screen.panel().show(ResearchFixtures.capture(State.RUNNING));
            e.layout();
            Bounds timeline = e.rect(e.screen.panel().lookup("#capture-timeline"));
            assertEquals(280, timeline.getHeight(), 6, "reference timeline height at 1440");
            assertTrue(timeline.getMaxY() < 900 - 38 - 16, "the lane block ends above the dock padding: it is not stretched");
            e.close();
        });
    }

    @Test
    void captureDetailsOnlyOffersAViewToggleAndNoStartStopOrRecovery() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            List<ButtonBase> buttons = DeskNodes.all(e.screen.node(), ButtonBase.class);
            assertEquals(1, buttons.size(), "only the 'Process / storage details' toggle");
            assertEquals("Process / storage details", buttons.get(0).getText());
            buttons.get(0).fire();
            assertTrue(e.screen.panel().detailsBody().isManaged(), "details body shown after the toggle");
            String all = String.join(" | ", DeskNodes.visibleTexts(e.screen.panel()));
            assertTrue(all.contains("Read-only monitoring. Start, stop and recovery are handled outside the app."), all);
            assertTrue(all.contains("READ-ONLY"), all);
            e.close();
        });
    }

    @Test
    void everyCaptureStateEndsIdenticallyInFullReducedAndOff() throws Exception {
        List<List<String>> perMode = new ArrayList<>();
        for (MotionPreference mode : MotionPreference.values()) {
            List<String> digests = new ArrayList<>();
            DeskHarness.fx(() -> {
                Env e = new Env();
                e.motion.preference.set(mode);
                e.size(1600, 1000);
                e.screen.panel().start(() -> { });
                for (State st : State.values()) {
                    e.screen.panel().show(ResearchFixtures.capture(st));
                    e.layout();
                    digests.add(String.join("\n", DeskNodes.digest(e.screen.panel())));
                }
                if (mode != MotionPreference.FULL) {
                    assertEquals(0, e.motion.runningLoops());
                }
                e.close();
            });
            perMode.add(digests);
        }
        for (int m = 1; m < perMode.size(); m++) {
            assertEquals(perMode.get(0), perMode.get(m), MotionPreference.values()[m] + " ends in the FULL state");
        }
    }

    @Test
    void theCaptureScreenIsV2OnlyWithNoLegacyStyle() throws Exception {
        DeskHarness.fx(() -> {
            Env e = new Env();
            e.size(1440, 900);
            e.display();
            assertEquals(ByxTheme.urls(), e.scene.getStylesheets());
            assertTrue(e.shell.content().getChildren().isEmpty());
            for (String legacy : List.of("card", "card-title", "badge", "badge-ok", "badge-purple", "muted", "kv-value", "metric", "capture-now")) {
                DeskNodes.walk(e.screen.node(), n -> assertFalse(n.getStyleClass().contains(legacy), legacy));
            }
            assertNotEquals(null, e.screen.panel().lookup("#capture-now"));
            e.close();
        });
    }
}
