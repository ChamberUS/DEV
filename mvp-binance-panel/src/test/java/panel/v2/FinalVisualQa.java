package panel.v2;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.authview.AuthScreens;
import panel.design.ByxTheme;
import panel.helpview.DiagnosticsReport;
import panel.helpview.DiagnosticsScreen;
import panel.helpview.FaqScreen;
import panel.helpview.HelpContent;
import panel.helpview.LegalScreen;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.systemview.OnboardingContent;
import panel.systemview.OnboardingDialog;
import panel.systemview.RecoveryTracker;
import panel.systemview.SystemStatusModel;
import panel.systemview.SystemStatusScreen;
import panel.tradeview.DeskHarness;
import panel.user.User;

/** Matriz visual final manual (não roda no surefire): mvn test -Dtest=FinalVisualQa -Dbyx.qa.out=docs/qa/step14 */
class FinalVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int[][] FULL = {{1440, 900}, {1600, 1000}, {1920, 1080}, {1280, 760}};

    @Test
    void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService m = QaShots.motion(MotionPreference.OFF);
                for (int[] s : FULL) {
                    String sz = "-" + s[0];
                    var faq = new FaqScreen(m, HelpContent.faq(), id -> { });
                    faq.setQuery("live");
                    faq.open("live-off");
                    faq.setQuery("live");
                    QaShots.shoot("h-faq", faq.node(), m, "help-faq" + sz, s[0], s[1]);
                    if (s[0] >= 1600 || s[0] == 1280) {
                        QaShots.shoot("h-diagnostics", new DiagnosticsScreen(m, () -> new DiagnosticsReport().set("Application", "BYX-MVP").set("Version", "0.1.0")
                                .set("Build", "development").set("Environment", "LOCALNET").set("Java", "21").set("JavaFX", "21").set("Operating system", "macOS")
                                .set("Backend", "UNAVAILABLE").set("Market feed", "UNAVAILABLE").set("BYX node", "AWAITING NODE").set("Authentication", "Signed in")
                                .set("Motion mode", "FULL"), t -> { }).node(), m, "help-diagnostics" + sz, s[0], s[1]);
                        QaShots.shoot("h-terms", new LegalScreen("Terms of Use", HelpContent.legal(), true).node(), m, "help-terms" + sz, s[0], s[1]);
                    }
                    Snapshot snap = new Snapshot();
                    snap.backendOnline = true;
                    TraderSnapshot t = new TraderSnapshot();
                    t.feed = "NOT_CONFIGURED";
                    var status = new SystemStatusScreen(m, CLOCK, () -> new SystemStatusModel.Inputs(snap, t,
                            ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured"), true, false, false, true), new RecoveryTracker(), id -> { });
                    QaShots.shoot("sys-status", status.node(), m, "system-status" + sz, s[0], s[1]);
                    var d = new OnboardingDialog(m, OnboardingContent.load(), "TRADING", r -> { });
                    d.go(1);
                    var holder = new javafx.scene.layout.StackPane(new javafx.scene.layout.Region(), d);
                    holder.getStyleClass().addAll("byx-desk", "byx-screen");
                    QaShots.shoot("t-desk", holder, m, "onboarding" + sz, s[0], s[1]);
                    // Login (entrada V2) com o rodapé público
                    var screens = new AuthScreens(m, new AuthScreens.Services() {
                        @Override public User login(String id, char[] pw) {
                            throw new AuthService.LoginException(AuthService.Failure.INVALID_CREDENTIALS, null);
                        }
                        @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { }
                        @Override public void changeOwnPassword(long id, char[] c, char[] n) { }
                        @Override public void endSession() { }
                    }, r -> { }, u -> { }, () -> { }, x -> { }, r -> { }, null);
                    Scene scene = new Scene((javafx.scene.Parent) screens.node(), s[0], s[1]);
                    ByxTheme.apply(scene);
                    screens.show(AuthScreens.LOGIN, null, null);
                    for (int i = 0; i < 4; i++) {
                        screens.node().applyCss();
                        ((javafx.scene.Parent) screens.node()).layout();
                    }
                    javax.imageio.ImageIO.write(panel.ControlGalleryTestAccess.toAwt(scene.snapshot(null)), "png", QaShots.OUT.resolve("login" + sz + ".png").toFile());
                    scene.setRoot(new javafx.scene.layout.Pane());
                    screens.dispose();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
