package panel.systemview;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import panel.design.ByxOverlayHost;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;
import panel.v2.QaShots;

/** QA visual manual do Passo 12 (não roda no surefire): mvn test -Dtest=SystemVisualQa -Dbyx.qa.out=docs/qa/step12 */
class SystemVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService m = QaShots.motion(MotionPreference.OFF);
                Snapshot s = new Snapshot();
                s.backendOnline = true;
                TraderSnapshot t = new TraderSnapshot();
                t.feed = "NOT_CONFIGURED";
                ByxSnapshot net = new ByxSnapshot("LIVE_NODE", "LOCALNET", "ONLINE", "VERIFIED", "FRESH", false, "byx-local-1", "128904", NOW.minusSeconds(4),
                        "byx1abc", java.math.BigInteger.ONE, "BYX", 6, NOW.minusSeconds(4), "ok");
                RecoveryTracker tracker = new RecoveryTracker();
                tracker.update("node", panel.design.StatusState.OPERATIONAL, NOW);
                tracker.update("node", panel.design.StatusState.UNAVAILABLE, NOW);
                for (int[] size : new int[][] {{1440, 900}, {1920, 1080}}) {
                    String sz = "-" + size[0];
                    var down = new SystemStatusScreen(m, CLOCK, () -> new SystemStatusModel.Inputs(s, t, ByxSnapshot.unknown("LIVE_NODE", "LOCALNET", "OFFLINE", "x"), true, false, false, true),
                            tracker, id -> { });
                    QaShots.shoot("sys-status", down.node(), m, "status-node-lost" + sz, size[0], size[1]);
                    var up = new SystemStatusScreen(m, CLOCK, () -> new SystemStatusModel.Inputs(s, t, net, true, true, true, true), new RecoveryTracker(), id -> { });
                    QaShots.shoot("sys-status", up.node(), m, "status" + sz, size[0], size[1]);
                }
                QaShots.shoot("sys-unavailable", new PageUnavailableScreen(m, () -> "t-desk", id -> { }, "t-desk").node(), m, "page-unavailable-1440", 1440, 900);
                var o = new OnboardingDialog(m, OnboardingContent.load(), "TRADING", r -> { });
                o.go(1);
                var holder = new javafx.scene.layout.StackPane(new javafx.scene.layout.Region(), o);
                holder.getStyleClass().addAll("byx-desk", "byx-screen");
                QaShots.shoot("t-desk", holder, m, "onboarding-workspace-1440", 1440, 900);
                var u = new UnexpectedErrorScreen(m, "ERR-4F2A91", null, () -> { }, () -> { }, () -> { });
                QaShots.shoot("t-desk", u, m, "unexpected-error-1440", 1440, 900);
                QaShots.shoot("t-desk", new StartupScreen(2), m, "startup-1440", 1440, 900);
                var inline = ErrorPatterns.inlineComponent(m, "The chart could not load.", () -> { });
                var perm = ErrorPatterns.permissionRequired("Research", "This area is for administrators.", "Ask an administrator for access.");
                var box = new javafx.scene.layout.VBox(14, ErrorPatterns.globalBar("Backend connection lost. Values shown are the last known ones."), inline, perm);
                box.setPadding(new javafx.geometry.Insets(28));
                box.getStyleClass().addAll("byx-desk", "byx-screen");
                QaShots.shoot("t-desk", box, m, "error-patterns-1440", 1440, 900);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
