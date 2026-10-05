package panel.helpview;

import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;
import panel.v2.QaShots;

/** QA visual manual do Passo 11 (não roda no surefire): mvn test -Dtest=HelpVisualQa -Dbyx.qa.out=docs/qa/step11 */
class HelpVisualQa {
    @Test
    void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService m = QaShots.motion(MotionPreference.OFF);
                var faq = new FaqScreen(m, HelpContent.faq(), id -> { });
                faq.open("live-off");
                QaShots.shoot("h-faq", faq.node(), m, "faq-1440", 1440, 900);
                var empty = new FaqScreen(m, HelpContent.faq(), id -> { });
                empty.setQuery("zzzz");
                QaShots.shoot("h-faq", empty.node(), m, "faq-empty-1440", 1440, 900);
                QaShots.shoot("h-faq", new FaqScreen(m, HelpContent.faq(), id -> { }).node(), m, "faq-1920", 1920, 1080);
                QaShots.shoot("h-about", new AboutScreen(m, id -> { }, t -> { }, false).node(), m, "about-1440", 1440, 900);
                QaShots.shoot("h-about", new AboutScreen(m, id -> { }, t -> { }, false).node(), m, "about-1920", 1920, 1080);
                QaShots.shoot("h-overview", new OverviewScreen(m, id -> { }).node(), m, "overview-1440", 1440, 900);
                QaShots.shoot("h-help", new SupportScreen(m, id -> { }, false).node(), m, "support-1440", 1440, 900);
                QaShots.shoot("h-diagnostics", new DiagnosticsScreen(m, () -> new DiagnosticsReport().set("Application", "BYX-MVP").set("Version", "0.1.0")
                        .set("Build", "development").set("Environment", "LOCALNET").set("Java", "21").set("Backend", "UNAVAILABLE").set("Market feed", "UNAVAILABLE")
                        .set("BYX node", "AWAITING NODE").set("Authentication", "Signed in").set("Motion mode", "FULL"), t -> { }).node(), m, "diagnostics-1440", 1440, 900);
                QaShots.shoot("h-terms", new LegalScreen("Terms of Use", HelpContent.legal(), true).node(), m, "terms-1440", 1440, 900);
                QaShots.shoot("h-shortcuts", new ShortcutsScreen("⌘").node(), m, "shortcuts-1440", 1440, 900);
                QaShots.shoot("h-whats-new", new WhatsNewScreen(HelpContent.whatsNew()).node(), m, "whats-new-1440", 1440, 900);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
