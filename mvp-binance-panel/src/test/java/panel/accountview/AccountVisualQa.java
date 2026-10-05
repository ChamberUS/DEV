package panel.accountview;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.security.SecurityAuditService;
import panel.tradeview.DeskHarness;
import panel.v2.QaShots;

/** QA visual manual do Passo 10 (não roda no surefire): mvn test -Dtest=AccountVisualQa -Dbyx.qa.out=docs/qa/step10 */
class AccountVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService m = QaShots.motion(MotionPreference.OFF);
                for (int[] s : new int[][] {{1440, 900}, {1600, 1000}, {1920, 1080}}) {
                    String sz = "-" + s[0];
                    var d = new AccountScreensTest.Stub();
                    d.audit = List.of(new SecurityAuditService.Entry(NOW.minusSeconds(60).toString(), "ADMIN_2FA_SUCCESS", "alex", ""),
                            new SecurityAuditService.Entry(NOW.minusSeconds(120).toString(), "LOGIN_SUCCESS", "alex", ""),
                            new SecurityAuditService.Entry(NOW.minusSeconds(86400).toString(), "LOGIN_FAILED", "alex", ""));
                    var profile = new ProfileScreen(m, d, id -> { }, () -> { });
                    QaShots.shoot("t-profile", profile.node(), m, "profile" + sz, s[0], s[1]);
                    var edit = new ProfileScreen(m, d, id -> { }, () -> { });
                    edit.startEdit();
                    QaShots.shoot("t-profile", edit.node(), m, "profile-edit" + sz, s[0], s[1]);
                    QaShots.shoot("t-security", new SecurityScreen(m, CLOCK, d, id -> { }, () -> null).node(), m, "security" + sz, s[0], s[1]);
                    QaShots.shoot("t-sessions", new SessionsScreen(m, CLOCK, d, () -> null).node(), m, "sessions" + sz, s[0], s[1]);
                    QaShots.shoot("t-notifications", new NotificationsScreen(m).node(), m, "notifications" + sz, s[0], s[1]);
                    QaShots.shoot("t-account-activity", new ActivityScreen(CLOCK, d).node(), m, "activity" + sz, s[0], s[1]);
                    var set = new SettingsScreen(m, d, id -> { }, () -> null);
                    set.select("Appearance");
                    QaShots.shoot("t-settings", set.node(), m, "settings-appearance" + sz, s[0], s[1]);
                    set.select("Trading");
                    QaShots.shoot("t-settings", set.node(), m, "settings-trading" + sz, s[0], s[1]);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
