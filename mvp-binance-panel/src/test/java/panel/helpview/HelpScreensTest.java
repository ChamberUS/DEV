package panel.helpview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.design.RegionState;
import panel.motion.MotionService;
import panel.shell.ShellRoutes;
import panel.shell.ShortcutRegistry;
import panel.tradeview.DeskHarness;

/** Passo 11: conteúdo versionado, FAQ por teclado/busca, diagnóstico por allow-list, legais como placeholder e contrato público. */
class HelpScreensTest {
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
    void faqContentComesFromTheVersionedFileAndFilters() {
        var faq = HelpContent.faq();
        assertEquals("PLACEHOLDER_CONTENT", faq.status());
        assertEquals(9, faq.categoryIds().size());
        assertEquals(20, faq.items().size());
        assertEquals(3, HelpContent.filter(faq, "trading", null).size());
        assertTrue(HelpContent.filter(faq, null, "live trading").size() >= 1);
        assertTrue(HelpContent.filter(faq, null, "zzzz-no-match").isEmpty());
        assertEquals(faq.items().size(), HelpContent.filter(faq, null, "  ").size());
    }

    @Test
    void legalStaysAPlaceholderEverywhere() throws Exception {
        var legal = HelpContent.legal();
        assertEquals("LEGAL_PLACEHOLDER", legal.status());
        assertEquals(8, legal.terms().size());
        assertEquals(7, legal.privacy().size());
        assertTrue(legal.terms().stream().allMatch(s -> s.body().contains("LEGAL_PLACEHOLDER")));
        assertTrue(legal.privacy().stream().allMatch(s -> s.body().contains("LEGAL_PLACEHOLDER")));
        DeskHarness.fx(() -> {
            LegalScreen terms = new LegalScreen("Terms of Use", legal, true);
            show(terms.node());
            String t = texts(terms.node());
            assertTrue(t.contains("LEGAL PLACEHOLDER") && t.contains("not an approved legal document"));
            assertEquals(8, terms.sectionCount());
            terms.scrollTo(7);
            terms.scrollTo(99); // fora do intervalo não faz nada
        });
        assertTrue(HelpContent.whatsNew().status().contains("PLACEHOLDER"));
    }

    @Test
    void diagnosticsUseAnAllowListAndNeverCarrySecrets() {
        DiagnosticsReport r = new DiagnosticsReport();
        assertThrows(IllegalArgumentException.class, () -> r.set("Password", "hunter2"));
        assertThrows(IllegalArgumentException.class, () -> r.set("Authorization header", "Bearer x"));
        r.set("Backend", "OPERATIONAL").set("Authentication", "Signed in · alex@example.com +5511999991234");
        r.set("Wallet", "token=abc123");
        r.set("Java", "21\nPassword: x");
        String text = r.text();
        assertFalse(text.contains("alex@example.com") || text.contains("+5511999991234"));
        assertFalse(text.toLowerCase().contains("password") || text.toLowerCase().contains("token") || text.toLowerCase().contains("bearer")
                || text.toLowerCase().contains("secret") || text.toLowerCase().contains("authorization"));
        assertEquals(DiagnosticsReport.FIELDS.size(), r.rows().size());
        assertTrue(text.startsWith("BYX-MVP diagnostics\n"));
        assertEquals(List.of("Passwords", "Tokens and one-time codes", "Session secrets", "API keys", "Private keys", "Seed phrases", "Authorization headers"),
                DiagnosticsReport.NEVER);
    }

    @Test
    void everyForbiddenFieldAndValueIsRefusedOrRedacted() {
        for (String field : List.of("password", "Password", "token", "OTP", "otp code", "API key", "api_key", "private key", "seed", "seed phrase", "authorization",
                "Authorization header", "session secret", "Session secret", "wallet seed", "mnemonic")) {
            assertThrows(IllegalArgumentException.class, () -> new DiagnosticsReport().set(field, "x"), field + " is not an allowed field");
        }
        DiagnosticsReport r = new DiagnosticsReport();
        int i = 0;
        for (String hostile : List.of("password=hunter2", "token abc.def.ghi", "OTP 123456", "api key sk-live-123", "private key MIIE...", "seed phrase: one two three",
                "Authorization: Bearer eyJ", "session secret 0xdeadbeef")) {
            r.set(DiagnosticsReport.FIELDS.get(7 + i++), hostile);
        }
        String text = r.text().toLowerCase();
        for (String leaked : List.of("hunter2", "abc.def", "123456", "sk-live", "miie", "one two", "eyj", "deadbeef")) {
            assertFalse(text.contains(leaked), "value leaked: " + leaked);
        }
        assertEquals(8, r.values().values().stream().filter("[redacted]"::equals).count());
    }

    @Test
    void diagnosticsScreenCopiesExactlyThePreview() throws Exception {
        DeskHarness.fx(() -> {
            List<String> copied = new ArrayList<>();
            DiagnosticsScreen s = new DiagnosticsScreen(new MotionService(), () -> new DiagnosticsReport().set("Application", "BYX-MVP").set("Version", "0.1.0"), copied::add);
            show(s.node());
            ((javafx.scene.control.Button) s.node().lookupAll(".byx-btn").stream().filter(n -> n instanceof javafx.scene.control.Button b && "Copy diagnostics".equals(b.getText()))
                    .findFirst().orElseThrow()).fire();
            assertEquals(List.of(s.previewText()), copied);
            assertTrue(texts(s.node()).contains("Never included"));
        });
    }

    @Test
    void faqSearchAccordionAndEmptyState() throws Exception {
        DeskHarness.fx(() -> {
            List<String> nav = new ArrayList<>();
            FaqScreen faq = new FaqScreen(new MotionService(), HelpContent.faq(), nav::add);
            show(faq.node());
            int all = faq.heads().size();
            assertEquals(20, all);
            assertTrue(texts(faq.node()).contains("PLACEHOLDER CONTENT"));
            faq.heads().get(0).fire();
            assertEquals("what-is", faq.openId());
            faq.heads().get(0).fire();
            assertEquals(null, faq.openId(), "toggle closes");
            faq.setQuery("live trading");
            assertTrue(faq.heads().size() < all && faq.heads().size() >= 1);
            faq.setQuery("zzzz-no-match");
            assertEquals(0, faq.heads().size());
            assertTrue(texts(faq.node()).contains("No results") && texts(faq.node()).contains("Clear search") && texts(faq.node()).contains("Report a problem"));
            faq.node().lookupAll(".byx-btn").stream().filter(n -> n instanceof javafx.scene.control.Button b && "Report a problem".equals(b.getText()))
                    .map(n -> (javafx.scene.control.Button) n).findFirst().orElseThrow().fire();
            assertEquals(List.of("h-help"), nav);
            faq.open("live-off");
            assertEquals("live-off", faq.openId());
            assertEquals("", faq.query(), "a deep link clears the search so the question is visible");
        });
    }

    @Test
    void faqSearchHighlightsTheMatchedTerm() throws Exception {
        var seg = Highlight.split("Why is Live Trading off? live trading!", "live trading");
        assertEquals(List.of("Why is ", "Live Trading", " off? ", "live trading", "!"), seg.stream().map(x -> (String) x[0]).toList());
        assertEquals(List.of(false, true, false, true, false), seg.stream().map(x -> (Boolean) x[1]).toList());
        assertEquals(1, Highlight.split("abc", "").size());
        assertEquals(1, Highlight.split("abc", "zzz").size());
        DeskHarness.fx(() -> {
            FaqScreen faq = new FaqScreen(new MotionService(), HelpContent.faq(), id -> { });
            show(faq.node());
            assertTrue(faq.node().lookupAll(".byx-hit").isEmpty(), "no query, no highlight");
            faq.setQuery("live trading");
            faq.node().applyCss();
            var hits = faq.node().lookupAll(".byx-hit");
            assertFalse(hits.isEmpty());
            assertTrue(hits.stream().allMatch(n -> ((javafx.scene.text.Text) n).getText().equalsIgnoreCase("live trading")));
            faq.setQuery("");
            assertTrue(faq.node().lookupAll(".byx-hit").isEmpty(), "clearing the search clears the highlight");
        });
    }

    @Test
    void supportNeverPretendsToSend() throws Exception {
        DeskHarness.fx(() -> {
            SupportScreen s = new SupportScreen(new MotionService(), id -> { }, false);
            show(s.node());
            assertEquals(RegionState.UNAVAILABLE, s.reportState());
            String t = texts(s.node());
            assertTrue(t.contains("DEMO ONLY · NOTHING IS SENT") && t.contains("Documentation") && t.contains("Contact support"));
            assertTrue(s.node().lookupAll(".text-field").isEmpty() && s.node().lookupAll(".text-area").isEmpty(), "no form that suggests a send");
            SupportScreen pub = new SupportScreen(new MotionService(), id -> { }, true);
            show(pub.node());
            assertFalse(texts(pub.node()).contains("Open diagnostics"), "diagnostics are not public");
            s.dispose();
            pub.dispose();
        });
    }

    @Test
    void aboutClaimsOnlyProvidedFacts() throws Exception {
        DeskHarness.fx(() -> {
            AboutScreen a = new AboutScreen(new MotionService(), id -> { }, t -> { }, false);
            show(a.node());
            String t = texts(a.node());
            assertTrue(t.contains("Cosmos SDK") && t.contains("ubyx") && t.contains("PLACEHOLDER_CONTENT"));
            for (String banned : new String[] {"licensed", "certified", "ISO ", "SOC 2", "regulated", "customers", "partners", "million"}) {
                assertFalse(t.toLowerCase().contains(banned.toLowerCase()), "About must not claim " + banned);
            }
        });
    }

    @Test
    void shortcutRegistryListsOnlyBoundShortcutsAndTheShellBindsThem() throws Exception {
        for (var g : ShortcutRegistry.GROUPS) {
            for (var e : g.items()) {
                assertFalse(e.boundBy().isBlank(), e.action() + " needs an implementation");
            }
        }
        assertFalse(ShortcutRegistry.GROUPS.stream().flatMap(g -> g.items().stream()).anyMatch(e -> e.keys().contains("R")), "Cmd+R was withdrawn");
        String shell = Files.readString(Path.of("src/main/java/panel/shell/ByxShell.java"));
        assertTrue(shell.contains("KeyCode.K") && shell.contains("KeyCode.COMMA") && shell.contains("DIGIT6") && shell.contains("\"?\""));
    }

    @Test
    void publicContractAllowsOnlyHelpPagesBeforeSignIn() {
        assertEquals(java.util.Set.of("h-about", "h-faq", "h-help", "h-terms", "h-privacy"), ShellRoutes.PUBLIC);
        for (String protectedRoute : new String[] {"t-desk", "t-markets", "t-byx", "t-wallet", "t-treasury", "t-profile", "t-security", "t-sessions", "overview",
                "capture", "t-settings", "h-diagnostics", "h-overview", "h-shortcuts", "h-whats-new"}) {
            assertFalse(ShellRoutes.PUBLIC.contains(protectedRoute), protectedRoute + " is not public");
        }
        assertTrue(ShellRoutes.isResearch("overview") && ShellRoutes.isResearch("capture") && ShellRoutes.isResearch("settings"));
        assertFalse(ShellRoutes.isResearch("h-faq") || ShellRoutes.isResearch("t-desk") || ShellRoutes.isResearch("auth:login"));
        assertEquals(6, ShellRoutes.rail(panel.shell.ShellContext.HELP).size());
        assertEquals(5, ShellRoutes.rail(panel.shell.ShellContext.ACCOUNT).size());
    }

    @Test
    void publicHostHasNoWorkspaceChrome() throws Exception {
        DeskHarness.fx(() -> {
            MotionService m = new MotionService();
            List<String> req = new ArrayList<>();
            Map<String, panel.ui.View> pages = new java.util.LinkedHashMap<>();
            pages.put("h-faq", new FaqScreen(m, HelpContent.faq(), req::add));
            pages.put("h-help", new SupportScreen(m, req::add, true));
            PublicHost host = new PublicHost(m, pages, req::add, () -> req.add("sign-in"));
            show(host);
            host.show("h-faq");
            assertEquals("h-faq", host.showing());
            String t = texts(host);
            assertTrue(t.contains("Sign in") && t.contains("PUBLIC"));
            assertTrue(host.lookupAll(".byx-rail").isEmpty() && host.lookupAll(".byx-dock").isEmpty() && host.lookupAll(".byx-search").isEmpty());
            host.show("h-help");
            assertEquals("h-help", host.showing());
            host.dispose();
        });
    }
}
