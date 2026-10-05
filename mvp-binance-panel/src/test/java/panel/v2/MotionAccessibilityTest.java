package panel.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import panel.byxview.ByxData;
import panel.byxview.NetworkScreen;
import panel.design.ByxTheme;
import panel.helpview.AboutScreen;
import panel.helpview.FaqScreen;
import panel.helpview.HelpContent;
import panel.helpview.LegalScreen;
import panel.helpview.OverviewScreen;
import panel.helpview.ShortcutsScreen;
import panel.helpview.SupportScreen;
import panel.helpview.WhatsNewScreen;
import panel.model.ByxSnapshot;
import panel.motion.MotionPolicy;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;
import panel.systemview.OnboardingContent;
import panel.systemview.OnboardingDialog;
import panel.systemview.RecoveryTracker;
import panel.systemview.StartupModel;
import panel.tradeview.DeskHarness;

/** Passo 13 (direcionado): tokens, FULL/REDUCED/OFF com o mesmo estado final, loops, política do sistema, teclado, foco, contraste e fronteira legada. */
class MotionAccessibilityTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static JsonNode tokens() throws Exception {
        return new ObjectMapper().readTree(Files.readString(Path.of("src/main/resources/design/BYX_MOTION_TOKENS.json"))).path("tokens");
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
    void systemPolicyOnlyReducesAndNeverLoosens() {
        for (boolean follow : new boolean[] {true, false}) {
            for (boolean system : new boolean[] {true, false}) {
                assertEquals(follow && system ? MotionPreference.REDUCED : MotionPreference.FULL, MotionPolicy.effective(MotionPreference.FULL, follow, system));
                assertEquals(MotionPreference.REDUCED, MotionPolicy.effective(MotionPreference.REDUCED, follow, system), "an explicit REDUCED never changes");
                assertEquals(MotionPreference.OFF, MotionPolicy.effective(MotionPreference.OFF, follow, system), "an explicit OFF is never raised by the system");
            }
        }
        assertTrue(MotionPolicy.systemApplied(MotionPreference.FULL, true, true));
        assertFalse(MotionPolicy.systemApplied(MotionPreference.OFF, true, true));
        assertFalse(MotionPolicy.systemApplied(MotionPreference.FULL, false, true));
    }

    @Test
    void logicalTimersMatchTheMotionTokens() throws Exception {
        JsonNode t = tokens();
        assertEquals(t.path("restoredChipHold").path("durationMs").asLong(), RecoveryTracker.RESTORED_HOLD.toMillis());
        assertEquals(t.path("startupExit").path("durationMs").asLong(), StartupModel.FADE_OUT.toMillis());
        for (String token : List.of("onboardingStepEnter", "globalBarShow", "accordionExpand", "settingsSaveBarShow", "dialogOpen")) {
            assertTrue(t.has(token) && t.path(token).path("durationMs").asLong() > 0, token + " exists in BYX_MOTION_TOKENS");
        }
        // as Views V2 novas não criam durações próprias: todo movimento passa por motion.duration(token)
        for (String pkg : List.of("byxview", "accountview", "helpview", "systemview")) {
            try (var files = Files.list(Path.of("src/main/java/panel/" + pkg))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(f);
                    Matcher m = Pattern.compile("(FadeTransition|TranslateTransition|ScaleTransition|KeyValue)\\(").matcher(src);
                    assertFalse(m.find(), f.getFileName() + " animates outside MotionService");
                }
            }
        }
    }

    @Test
    void reducedModeFollowsTheOfficialMatrixNotABlindRule() throws Exception {
        JsonNode t = tokens();
        JsonNode modes = new ObjectMapper().readTree(Files.readString(Path.of("src/main/resources/design/BYX_MOTION_TOKENS.json"))).path("modes");
        assertEquals("spinner only", modes.path("REDUCED").path("loops").asText(), "the matrix allows only the spinner as a loop in REDUCED");
        assertEquals(800, t.path("loading").path("reducedMs").asInt(), "the spinner keeps its 800 ms in REDUCED (essential feedback)");
        for (String loop : List.of("shimmer", "breathing", "statePulse")) {
            assertEquals("none", t.path(loop).path("reducedMs").asText(), loop + " has no REDUCED motion");
        }
        MotionService m = new MotionService();
        m.preference.set(MotionPreference.REDUCED);
        assertTrue(m.token("loading").runs(), "REDUCED: the spinner runs");
        assertFalse(m.token("breathing").runs() || m.token("statePulse").runs() || m.token("shimmer").runs(), "REDUCED: breathing, pulse and shimmer do not");
        m.preference.set(MotionPreference.OFF);
        assertFalse(m.token("loading").runs(), "OFF: static glyph");
        // Brand field (matriz P3.20): em REDUCED só o brilho respira (8 s, 12 fps); em OFF nada
        assertEquals(8000, (int) panel.authview.BrandFieldModel.REDUCED_GLOW_PERIOD_MS);
        assertEquals(12, panel.authview.BrandFieldModel.REDUCED_FPS);
    }

    private static ByxData byx() {
        return (ByxData) Proxy.newProxyInstance(ByxData.class.getClassLoader(), new Class<?>[] {ByxData.class}, (p, m, a) -> switch (m.getName()) {
            case "network" -> ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured");
            case "sessionActive", "admin" -> false;
            case "wallets", "entitlements" -> List.of();
            case "gasGrant" -> Optional.empty();
            default -> null;
        });
    }

    private static String render(MotionPreference mode, MotionService[] out) throws Exception {
        MotionService m = new MotionService();
        m.preference.set(mode);
        out[0] = m;
        StringBuilder all = new StringBuilder();
        List<panel.ui.View> views = new ArrayList<>();
        NetworkScreen net = new NetworkScreen(m, CLOCK, byx());
        views.add(net);
        views.add(new FaqScreen(m, HelpContent.faq(), id -> { }));
        views.add(new AboutScreen(m, id -> { }, t -> { }, false));
        views.add(new OverviewScreen(m, id -> { }));
        views.add(new SupportScreen(m, id -> { }, false));
        views.add(new LegalScreen("Terms of Use", HelpContent.legal(), true));
        views.add(new ShortcutsScreen("⌘"));
        views.add(new WhatsNewScreen(HelpContent.whatsNew()));
        for (panel.ui.View v : views) {
            show(v.node());
            v.onShow();
            all.append(texts(v.node()));
        }
        OnboardingDialog d = new OnboardingDialog(m, OnboardingContent.load(), "TRADING", r -> { });
        show(d);
        for (int i = 0; i < 5; i++) {
            d.nextButton().fire();
        }
        all.append(texts(d));
        return all.toString();
    }

    @Test
    void fullReducedAndOffEndInTheSameLogicalState() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService[] ref = new MotionService[1];
                String full = render(MotionPreference.FULL, ref);
                assertEquals(1, ref[0].runningLoops(), "FULL: only the network ring (awaiting node) loops");
                String reduced = render(MotionPreference.REDUCED, ref);
                assertEquals(0, ref[0].runningLoops(), "REDUCED has no continuous loop");
                String off = render(MotionPreference.OFF, ref);
                assertEquals(0, ref[0].runningLoops(), "OFF has no loop");
                assertEquals(full, reduced);
                assertEquals(full, off);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void onboardingStepChangeNeverLeavesDisplacementInReducedOrOff() throws Exception {
        DeskHarness.fx(() -> {
            for (MotionPreference mode : new MotionPreference[] {MotionPreference.REDUCED, MotionPreference.OFF}) {
                MotionService m = new MotionService();
                m.preference.set(mode);
                OnboardingDialog d = new OnboardingDialog(m, OnboardingContent.load(), "TRADING", r -> { });
                show(d);
                d.nextButton().fire();
                javafx.scene.Node body = d.getChildren().get(1);
                m.reference.settleTree(body);
                assertEquals(0, body.getTranslateX(), 0.01, mode + ": no displacement");
                assertEquals(1, d.activeDots());
            }
        });
    }

    @Test
    void segmentedControlsAndShortcutsHonorTheKeyboard() throws Exception {
        DeskHarness.fx(() -> {
            List<String> changes = new ArrayList<>();
            Kit.Segmented seg = new Kit.Segmented(List.of("Sessions", "Trusted devices", "Other"), "Sessions", changes::add);
            seg.disable("Other", true);
            javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(seg);
            show(root);
            seg.buttons().get(0).requestFocus();
            seg.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false));
            assertEquals("Trusted devices", seg.selected());
            seg.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false));
            assertEquals("Sessions", seg.selected(), "wraps and skips the disabled option");
            seg.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, false, false, false, false));
            assertEquals("Trusted devices", seg.selected());
            assertEquals(List.of("Trusted devices", "Sessions", "Trusted devices"), changes);
        });
    }

    @Test
    void helpKeyOpensTheShortcutsDialogOnlyWhenNotTypingAndEscClosesIt() throws Exception {
        DeskHarness.fx(() -> {
            MotionService m = new MotionService();
            ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
            ByxShell shell = new ByxShell(router, m, new LegacyHost());
            TextField field = new TextField();
            shell.v2Content().getChildren().add(field);
            shell.showV2(true);
            router.request("t-desk");
            Scene scene = new Scene(shell, 1440, 900);
            ByxTheme.apply(scene);
            shell.applyCss();
            shell.layout();
            field.requestFocus();
            KeyEvent q = new KeyEvent(KeyEvent.KEY_TYPED, "?", "", KeyCode.UNDEFINED, false, false, false, false);
            javafx.event.Event.fireEvent(field, q);
            assertEquals(0, shell.overlay().openDialogs(), "typing '?' in a field is just text");
            shell.requestFocus();
            javafx.event.Event.fireEvent(shell, new KeyEvent(KeyEvent.KEY_TYPED, "?", "", KeyCode.UNDEFINED, false, false, false, false));
            assertEquals(1, shell.overlay().openDialogs());
            javafx.event.Event.fireEvent(shell, new KeyEvent(KeyEvent.KEY_TYPED, "?", "", KeyCode.UNDEFINED, false, false, false, false));
            assertEquals(1, shell.overlay().openDialogs(), "no second dialog on top");
            javafx.scene.Node focus = scene.getFocusOwner();
            assertTrue(focus != null && isInside(focus, shell.overlay().layer(panel.design.OverlayLayer.DIALOG)), "focus is trapped inside the dialog");
            focus.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertEquals(0, shell.overlay().openDialogs(), "Esc closes the topmost layer");
            shell.dispose();
        });
    }

    private static boolean isInside(javafx.scene.Node n, javafx.scene.Node ancestor) {
        for (javafx.scene.Node x = n; x != null; x = x.getParent()) {
            if (x == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---- contraste das novas telas (tokens, sem repetir a auditoria das 23 combinações) -----------------------------

    private static double lin(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double lum(int rgb) {
        return 0.2126 * lin(((rgb >> 16) & 255) / 255.0) + 0.7152 * lin(((rgb >> 8) & 255) / 255.0) + 0.0722 * lin((rgb & 255) / 255.0);
    }

    private static int over(int fg, int alpha, int bg) {
        double a = alpha / 255.0;
        int r = (int) Math.round(((fg >> 16) & 255) * a + ((bg >> 16) & 255) * (1 - a));
        int g = (int) Math.round(((fg >> 8) & 255) * a + ((bg >> 8) & 255) * (1 - a));
        int b = (int) Math.round((fg & 255) * a + (bg & 255) * (1 - a));
        return (r << 16) | (g << 8) | b;
    }

    private static double ratio(int fg, int bg) {
        double a = lum(fg) + 0.05;
        double b = lum(bg) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    @Test
    void newScreensKeepAaContrastAndNeverDependOnColorAlone() {
        int bg0 = 0x0B0E16, bg1 = 0x121723, bg2 = 0x1A2030;
        Map<String, Double> pairs = new java.util.LinkedHashMap<>();
        pairs.put("secondary on bg1 (descriptions)", ratio(0xAAB3C7, bg1));
        pairs.put("tertiary on bg1 (dim text, table heads)", ratio(0x8791A8, bg1));
        pairs.put("tertiary on bg2 (lowest pair)", ratio(0x8791A8, bg2));
        pairs.put("primary on selected tier/choice", ratio(0xEEF1F8, over(0x7C96FF, 0x1F, bg2)));
        pairs.put("banner warning (global bar)", ratio(0xF2CB7A, over(0xE9B44C, 0x18, bg0)));
        pairs.put("banner error (inline)", ratio(0xFFB0B0, over(0xFF7A7A, 0x18, bg0)));
        pairs.put("badge positive", ratio(0x42D79B, over(0x42D79B, 0x1F, bg1)));
        pairs.put("badge warning", ratio(0xE9B44C, over(0xE9B44C, 0x1F, bg1)));
        pairs.put("badge negative", ratio(0xFF7A7A, over(0xFF7A7A, 0x1F, bg1)));
        pairs.put("badge info", ratio(0x5CC8FF, over(0x5CC8FF, 0x1F, bg1)));
        pairs.forEach((name, r) -> assertTrue(r >= 4.5, name + " = " + String.format("%.2f", r)));
    }

    // ---- fronteira legada --------------------------------------------------------------------------------------------

    @Test
    void legacyViewInventoryIsExactlyTheDocumentedOne() throws Exception {
        String src = Files.readString(Path.of("src/main/java/panel/app/PanelApp.java"));
        Matcher v2 = Pattern.compile("V2_VIEWS = java.util.Set.of\\((.*?)\\);", Pattern.DOTALL).matcher(src);
        assertTrue(v2.find());
        Set<String> v2ids = new TreeSet<>();
        Matcher ids = Pattern.compile("\"([^\"]+)\"").matcher(v2.group(1));
        while (ids.find()) {
            v2ids.add(ids.group(1));
        }
        Set<String> registered = new TreeSet<>();
        Matcher put = Pattern.compile("views\\.put\\(\"([^\"]+)\"").matcher(src);
        while (put.find()) {
            registered.add(put.group(1));
        }
        registered.removeAll(v2ids);
        assertEquals(new TreeSet<>(Set.of("t-bot", "t-strategies", "t-signals", "t-portfolio", "t-positions", "t-orders", "t-performance", "t-activity",
                "t-wallet-verify", "sessions", "dataset", "labels", "features", "hypotheses", "validation", "execution", "paper", "live", "jobs", "logs", "users",
                "settings")), registered, "every other View is V2; the rest is LEGACY / NO V2 REFERENCE and listed in the status doc");
    }
}
