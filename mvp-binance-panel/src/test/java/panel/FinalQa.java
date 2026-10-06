package panel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.design.OverlayLayer;
import panel.model.Snapshot;
import panel.shell.ByxShell;
import panel.shell.ShellRouter;
import panel.ui.View;
import panel.user.User;

/**
 * QA final dirigido no app REAL (manual; fora do surefire), um modo por execução, sempre num home temporário:
 * public | onboarding | unsaved | system | legacy. Uso: java ... panel.FinalQa saida modo
 */
public final class FinalQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;
    static Path output;
    static String mode;
    static Path settingsFile;

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        mode = args[1];
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-final-qa-home-");
        Path dir = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(dir.resolve("security.properties"), "security.dev.mode=true\n");
        settingsFile = dir.resolve("settings.properties");
        Files.writeString(settingsFile, "dataSource=REAL\nprojectPath=" + home.resolve("empty-project") + "\ncliPath=/usr/bin/false\npollSeconds=3600\nmotion="
                + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\nonboardingCompleted=" + !mode.equals("onboarding") + "\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        lines.add("RESULT mode=" + mode + " failures=" + failures);
        Files.write(output.resolve("final-" + mode + ".txt"), lines);
        lines.forEach(System.out::println);
        System.exit(failures > 0 ? 1 : 0);
    }

    static void check(boolean ok, String what) {
        lines.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static final class App extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            // prodauth usa a composição de PRODUÇÃO (sem provedor de desenvolvimento) para provar que security.dev.mode=true não muda nada
            return mode.equals("prodauth") ? new panel.app.AppContext() : panel.QaContext.create();
        }

        private AppContext ctx;
        private ShellRouter router;
        private Map<String, View> views;
        private final List<String> routeLog = new ArrayList<>();
        private final List<Step> plan = new ArrayList<>();
        private int index;

        interface Step {
            void run() throws Exception;
        }

        @Override
        public void start(Stage stage) {
            super.start(stage);
            try {
                ctx = (AppContext) field("ctx");
                router = (ShellRouter) field("router");
                @SuppressWarnings("unchecked")
                Map<String, View> v = (Map<String, View>) field("views");
                views = v;
                if (!mode.equals("onboarding")) {
                    ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "final-qa-pass-1".toCharArray(), "+5511999991234");
                }
                invoke("showEntry", String.class, null);
                RouteTrace.attach(router, routeLog, lines);
                switch (mode) {
                    case "public" -> publicMode();
                    case "onboarding" -> onboarding();
                    case "unsaved" -> unsaved();
                    case "system" -> system();
                    case "legacy" -> legacy();
                    case "roundtrip" -> roundtrip();
                    case "prodauth" -> prodAuth();
                    default -> throw new IllegalArgumentException(mode);
                }
                after(600, this::run);
            } catch (Throwable t) {
                fail(t);
            }
        }

        // ---- infra -------------------------------------------------------------------------------------------------

        private Object field(String name) throws Exception {
            Field f = PanelApp.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(this);
        }

        private void invoke(String name, Class<?> type, Object arg) throws Exception {
            Method m = PanelApp.class.getDeclaredMethod(name, type);
            m.setAccessible(true);
            m.invoke(this, arg);
        }

        private void show(String id) throws Exception {
            invoke("show", String.class, id);
        }

        private ByxShell shell() throws Exception {
            return (ByxShell) field("shell");
        }

        private void after(long ms, Step s) {
            PauseTransition p = new PauseTransition(Duration.millis(ms));
            p.setOnFinished(e -> {
                try {
                    s.run();
                } catch (Throwable t) {
                    fail(t);
                }
            });
            p.play();
        }

        private void run() throws Exception {
            if (index >= plan.size()) {
                Platform.exit();
                return;
            }
            plan.get(index++).run();
        }

        private void next(long ms) {
            after(ms, this::run);
        }

        private void fail(Throwable t) {
            t.printStackTrace();
            check(false, "unexpected exception " + t);
            Platform.exit();
        }

        private void login() throws Exception {
            User u = ctx.auth.login("qa-admin", "final-qa-pass-1".toCharArray());
            invoke("afterLogin", User.class, u);
        }

        private void verifyAdmin() throws Exception {
            var flow = ctx.adminAccess.startTwoFactor();
            flow.sendEmailCode();
            flow.verifyEmail(panel.QaContext.dev().lastCode());
            flow.sendSmsCode();
            flow.verifySms(panel.QaContext.dev().lastCode());
            flow.finish(false);
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

        private static String visibleTexts(Node n) {
            StringBuilder b = new StringBuilder();
            collectVisible(n, b);
            return b.toString();
        }

        private static void collectVisible(Node n, StringBuilder b) {
            if (!n.isVisible()) {
                return;
            }
            if (n instanceof Labeled l && l.getText() != null) {
                b.append(l.getText()).append('\n');
            }
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
                collectVisible(sp.getContent(), b);
            }
            if (n instanceof Parent p) {
                p.getChildrenUnmodifiable().forEach(c -> collectVisible(c, b));
            }
        }

        private Button button(Node root, String text) {
            return root.lookupAll(".button").stream().filter(n -> n instanceof Button b && text.equals(b.getText())).map(n -> (Button) n).findFirst().orElse(null);
        }

        private Node dialogLayer() throws Exception {
            return shell().overlay().layer(OverlayLayer.DIALOG);
        }

        private Properties props() throws Exception {
            Properties p = new Properties();
            try (var in = Files.newInputStream(settingsFile)) {
                p.load(in);
            }
            return p;
        }

        // ---- 9. modo público -----------------------------------------------------------------------------------------

        private void publicMode() {
            plan.add(() -> {
                check("auth:login".equals(router.route()), "entry opens the login route: " + router.route());
                String last = "auth:login";
                for (String id : List.of("h-faq", "h-about", "h-help", "h-terms", "h-privacy")) {
                    show(id);
                    check(id.equals(router.route()), "public page opens: " + id);
                    var host = (panel.helpview.PublicHost) field("publicHost");
                    check(host != null && id.equals(host.showing()), "public host shows " + id);
                    check(host != null && host.lookupAll(".byx-rail").isEmpty() && host.lookupAll(".byx-dock").isEmpty(), "no rail or dock in public mode (" + id + ")");
                    last = id;
                }
                for (String id : List.of("t-desk", "t-markets", "overview", "capture", "validation", "t-byx", "t-wallet", "t-treasury", "t-profile", "t-security",
                        "t-sessions", "t-settings", "sys-status", "h-diagnostics", "h-overview", "h-shortcuts", "h-whats-new", "auth:setup")) {
                    show(id);
                    check("h-privacy".equals(router.route()) && router.pending() == null, "no session: " + id + " stays inaccessible (route " + router.route() + ")");
                }
                check(ctx.sessions.user().isEmpty(), "still no session");
                var host = (panel.helpview.PublicHost) field("publicHost");
                Button signIn = button(host, "Sign in");
                check(signIn != null, "public host has Sign in");
                signIn.fire();
                next(500);
            });
            plan.add(() -> {
                check("auth:login".equals(router.route()), "Sign in returns to the login route: " + router.route());
                check(field("publicHost") == null, "public host released");
                next(100);
            });
        }

        // ---- 10. first run + onboarding --------------------------------------------------------------------------------

        private void onboarding() {
            Properties[] before = new Properties[1];
            String[] traded = new String[1];
            plan.add(() -> {
                check("auth:welcome".equals(router.route()), "first install opens the Welcome route: " + router.route());
                check(texts(((panel.authview.AuthScreens) field("authScreens")).node()).contains("Create the administrator account"), "Welcome offers the administrator account");
                show("auth:setup");
                check("auth:setup".equals(router.route()), "Welcome leads to the first-administrator setup");
                ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "final-qa-pass-1".toCharArray(), "+5511999991234");
                invoke("showEntry", String.class, "Administrator created. Sign in to continue.");
                check("auth:login".equals(router.route()), "after setup the login opens");
                before[0] = props();
                Snapshot s = ctx.research.snapshot.get();
                traded[0] = ctx.trading.snapshot.get().trading;
                check("LOCKED".equals(s.validationStatus) && "SEALED".equals(s.finalHoldout), "gates before onboarding: LOCKED / SEALED");
                login();
                next(1500);
            });
            plan.add(() -> {
                var layer = dialogLayer();
                check(shell().overlay().openDialogs() == 1 && layer.lookup("#onboarding") != null, "onboarding opens after the first login");
                var dialog = (panel.systemview.OnboardingDialog) layer.lookup("#onboarding");
                check(dialog.step() == 0 && dialog.backButton().isDisabled(), "starts at step 1 of 6");
                dialog.nextButton().fire();
                check(dialog.step() == 1, "Next advances");
                ToggleButton byx = (ToggleButton) dialog.lookupAll(".byx-choice").stream().filter(n -> ((ToggleButton) n).getText().startsWith("BYX")).findFirst().orElseThrow();
                byx.fire();
                check("BYX".equals(dialog.workspace()), "choice recorded in the dialog only");
                check("TRADING".equals(ctx.settings.primaryWorkspace), "nothing persisted before Finish/Skip");
                dialog.skipButton().fire();
                next(500);
            });
            plan.add(() -> {
                check(shell().overlay().openDialogs() == 0, "Skip closes the dialog");
                check(ctx.settings.onboardingCompleted && "TRADING".equals(ctx.settings.primaryWorkspace), "Skip records completion and keeps the primary workspace");
                check("t-desk".equals(router.route()), "Skip keeps the route: " + router.route());
                show("sys-onboarding");
                next(400);
            });
            plan.add(() -> {
                var dialog = (panel.systemview.OnboardingDialog) dialogLayer().lookup("#onboarding");
                check(dialog != null, "replay opens the dialog again");
                dialog.go(1);
                ((ToggleButton) dialog.lookupAll(".byx-choice").stream().filter(n -> ((ToggleButton) n).getText().startsWith("BYX")).findFirst().orElseThrow()).fire();
                for (int i = 0; i < 5; i++) {
                    dialog.nextButton().fire();
                }
                check(dialog.step() == 5 && dialog.activeDots() == 1, "five fast Next end on step 6 with one active step");
                dialog.nextButton().fire();
                next(600);
            });
            plan.add(() -> {
                check(shell().overlay().openDialogs() == 0, "Finish closes the dialog");
                check("BYX".equals(ctx.settings.primaryWorkspace), "Finish stores the primary workspace");
                check("t-desk".equals(router.route()), "a replay never navigates (only the first onboarding opens the chosen workspace): " + router.route());
                Snapshot s = ctx.research.snapshot.get();
                check("LOCKED".equals(s.validationStatus) && "SEALED".equals(s.finalHoldout), "gates after onboarding: LOCKED / SEALED");
                check(traded[0].equals(ctx.trading.snapshot.get().trading) && "DISABLED".equals(ctx.trading.snapshot.get().trading), "trading untouched (DISABLED)");
                boolean noWallet;
                try {
                    noWallet = ctx.byxWallets.wallets().isEmpty();
                } catch (IllegalStateException unavailable) {
                    noWallet = true; // LOCALNET não configurada: nada para vincular
                }
                check(noWallet, "no wallet linked by onboarding");
                check("UNKNOWN".equals(ctx.byx.snapshot().connection()), "BYX node untouched (no DEVNET, no node start)");
                Properties after = props();
                Properties diff = new Properties();
                Properties defaults = new Properties();
                // Settings.save() materializa os padrões de chaves que o arquivo mínimo não tinha: valor igual ao padrão não é mudança
                defaults.setProperty("reportsPath", "reports/research");
                defaults.setProperty("animatedIcons", "true");
                defaults.setProperty("researchPollSeconds", "20");
                defaults.setProperty("followSystemMotion", "true");
                for (String k : after.stringPropertyNames()) {
                    String old = before[0].containsKey(k) ? before[0].getProperty(k) : defaults.getProperty(k);
                    if (!java.util.Objects.equals(after.getProperty(k), old)) {
                        diff.setProperty(k, after.getProperty(k));
                    }
                }
                check(diff.stringPropertyNames().equals(java.util.Set.of("onboardingCompleted", "primaryWorkspace")),
                        "only the two onboarding preferences changed value on disk: " + diff.stringPropertyNames());
                next(100);
            });
        }

        // ---- 11. alterações não salvas ---------------------------------------------------------------------------------

        private void unsaved() {
            plan.add(() -> {
                login();
                next(1200);
            });
            plan.add(() -> {
                show("t-settings");
                View settings = views.get("t-settings");
                check("t-settings".equals(router.route()), "Settings opens");
                ToggleButton appearance = (ToggleButton) settings.node().lookupAll(".byx-desk-seg-btn").stream().filter(n -> n instanceof ToggleButton b && "Appearance".equals(b.getText())).findFirst().orElseThrow();
                appearance.setSelected(true);
                ToggleButton reduced = (ToggleButton) settings.node().lookupAll(".byx-desk-seg-btn").stream().filter(n -> n instanceof ToggleButton b && "REDUCED".equals(b.getText())).findFirst().orElseThrow();
                reduced.setSelected(true);
                check(settings.hasUnsavedChanges(), "Settings is dirty");
                check(shell().overlay().saveBarVisible(), "the save bar appears on a change");
                show("t-desk");
                next(400);
            });
            plan.add(() -> {
                check("t-settings".equals(router.route()), "leaving a dirty Settings keeps the route until confirmed: " + router.route());
                check(shell().overlay().openDialogs() == 1 && texts(dialogLayer()).contains("Discard changes?"), "confirmation dialog is open");
                Button cancel = button(dialogLayer(), "Cancel");
                check(cancel != null && shell().getScene().getFocusOwner() == cancel, "destructive dialog focuses Cancel");
                cancel.fire();
                next(400);
            });
            plan.add(() -> {
                check("t-settings".equals(router.route()) && router.pending() == null, "Cancel stays and clears the pending request");
                check(views.get("t-settings").hasUnsavedChanges(), "Cancel preserves the draft");
                check("FULL".equals(ctx.settings.motion), "nothing was saved");
                show("t-desk");
                next(400);
            });
            plan.add(() -> {
                button(dialogLayer(), "Discard").fire();
                next(500);
            });
            plan.add(() -> {
                check("t-desk".equals(router.route()), "Discard leaves: " + router.route());
                check(!views.get("t-settings").hasUnsavedChanges() && "FULL".equals(ctx.settings.motion), "Discard removed the draft and saved nothing");
                check(!shell().overlay().saveBarVisible(), "save bar gone after Discard");
                show("t-settings");
                View settings = views.get("t-settings");
                ToggleButton reduced = (ToggleButton) settings.node().lookupAll(".byx-desk-seg-btn").stream().filter(n -> n instanceof ToggleButton b && "REDUCED".equals(b.getText())).findFirst().orElseThrow();
                reduced.setSelected(true);
                check(settings.hasUnsavedChanges() && "FULL".equals(props().getProperty("motion")), "draft is not on disk yet");
                button(shell().overlay().layer(OverlayLayer.SAVEBAR), "Save changes").fire();
                next(500);
            });
            plan.add(() -> {
                check("REDUCED".equals(props().getProperty("motion")) && "REDUCED".equals(ctx.settings.motion), "Save persisted the preference on disk (real persistence)");
                check(ctx.motion.preference.get() == panel.motion.MotionPreference.REDUCED, "the real motion system applied it");
                check(!views.get("t-settings").hasUnsavedChanges(), "clean after Save");
                // Profile
                show("t-profile");
                View profile = views.get("t-profile");
                Button edit = button(profile.node(), "Edit contacts");
                check(edit != null, "Profile offers the real contact edit");
                edit.fire();
                var email = (javafx.scene.control.TextField) profile.node().lookupAll(".text-field").stream().findFirst().orElseThrow();
                email.setText("changed@example.invalid");
                check(profile.hasUnsavedChanges(), "Profile is dirty");
                show("t-desk");
                next(400);
            });
            plan.add(() -> {
                check("t-profile".equals(router.route()) && texts(dialogLayer()).contains("Discard changes?"), "Profile asks before leaving");
                button(dialogLayer(), "Cancel").fire();
                check(views.get("t-profile").hasUnsavedChanges(), "Cancel preserves the Profile draft");
                show("t-desk");
                next(400);
            });
            plan.add(() -> {
                button(dialogLayer(), "Discard").fire();
                next(500);
            });
            plan.add(() -> {
                check("t-desk".equals(router.route()) && !views.get("t-profile").hasUnsavedChanges(), "Discard leaves Profile and clears the edit");
                check(ctx.sessions.user().orElseThrow().user().email().equals("qa@example.invalid"), "contacts unchanged in the account store");
                next(100);
            });
        }

        // ---- 12. system / recovery / erros -----------------------------------------------------------------------------

        private void system() {
            Object[] shellRef = new Object[1];
            int[] entries = new int[1];
            plan.add(() -> {
                login();
                next(1200);
            });
            plan.add(() -> {
                Snapshot up = ctx.research.snapshot.get().copy();
                up.backendOnline = true;
                ctx.research.snapshot.set(up);
                next(1500);
            });
            plan.add(() -> {
                shellRef[0] = shell();
                entries[0] = ctx.transitions.entries();
                routeLog.clear();
                Snapshot down = ctx.research.snapshot.get().copy();
                down.backendOnline = false;
                ctx.research.snapshot.set(down);
                next(1600);
            });
            plan.add(() -> {
                check(shell().globalBar() != null, "backend lost: the slim global bar appears");
                check(texts(shell().globalBar()).contains("Backend connection lost"), "global bar text");
                check("t-desk".equals(router.route()) && routeLog.isEmpty(), "no route change on loss: " + routeLog);
                check(shell() == shellRef[0], "shell not recreated");
                check(ctx.transitions.entries() == entries[0], "no page enter on loss");
                Snapshot up = ctx.research.snapshot.get().copy();
                up.backendOnline = true;
                ctx.research.snapshot.set(up);
                next(1600);
            });
            plan.add(() -> {
                check(shell().globalBar() == null, "restored: the global bar is gone");
                check(shell().overlay().visibleToasts() >= 1, "restored: one success notice");
                check(routeLog.isEmpty() && shell() == shellRef[0] && ctx.transitions.entries() == entries[0], "no route change, rebuild or page enter on restore");
                show("sys-status");
                next(600);
            });
            plan.add(() -> {
                String t = visibleTexts(views.get("sys-status").node());
                check(t.contains("RESTORED") && t.contains("Backend"), "System Status shows the RESTORED recovery chip: " + t.contains("RESTORED"));
                next(3400);
            });
            plan.add(() -> {
                check(!visibleTexts(views.get("sys-status").node()).contains("RESTORED"), "RESTORED returns to CONNECTED after the 3 s hold");
                // Page unavailable
                show("t-desk");
                show("t-nowhere");
                check("sys-unavailable".equals(router.route()), "unknown internal route opens Page unavailable: " + router.route());
                String t = texts(views.get("sys-unavailable").node());
                check(t.contains("Page unavailable") && t.contains("Return to previous") && t.contains("Go to default workspace") && !t.contains("404"), "page unavailable content");
                String still = router.route();
                next(1500);
            });
            plan.add(() -> {
                check("sys-unavailable".equals(router.route()), "Page unavailable never redirects by itself");
                button(views.get("sys-unavailable").node(), "Return to previous").fire();
                check("t-desk".equals(router.route()), "Return to previous goes through the router: " + router.route());
                // Unexpected error
                Method m = PanelApp.class.getDeclaredMethod("onUncaught", Throwable.class);
                m.setAccessible(true);
                m.invoke(this, new IllegalStateException("token=SECRET /Users/qa/private"));
                var overlay = shell().mainOverlay();
                check(overlay instanceof panel.systemview.UnexpectedErrorScreen, "unexpected error fallback shown");
                String t = texts(overlay);
                check(t.contains("Something went wrong") && t.contains("ERR-") && !t.contains("SECRET") && !t.contains("/Users/") && !t.contains("IllegalState") && !t.contains("at panel."), "fallback shows a code, never the exception, a secret or a path");
                check("t-desk".equals(router.route()), "fallback does not change the route");
                button(overlay, "Return").fire();
                check(shell().mainOverlay() == null && "t-desk".equals(router.route()), "Return clears the fallback");
                for (int i = 0; i < 2; i++) {
                    m.invoke(this, new IllegalStateException("again"));
                    var o = shell().mainOverlay();
                    if (i == 0) {
                        button(o, "Return").fire();
                    }
                }
                var third = (panel.systemview.UnexpectedErrorScreen) shell().mainOverlay();
                check(third != null && button(third, "Retry").isDisabled(), "Retry is disabled after repeated failures");
                button(third, "Return").fire();
                next(100);
            });
        }

        // ---- 4/5. round trips, ciclo de vida e dispose -----------------------------------------------------------------------

        private void roundtrip() {
            int[] entries = new int[2];
            plan.add(() -> {
                login();
                next(1200);
            });
            plan.add(() -> {
                verifyAdmin();
                next(300);
            });
            plan.add(() -> {
                for (String id : List.of("t-desk", "overview", "t-byx", "t-desk")) {
                    show(id);
                    check(id.equals(router.route()), "first pass reaches " + id);
                }
                entries[0] = ctx.transitions.entries();
                check(entries[0] >= 3, "first display of each View enters once: " + entries[0]);
                routeLog.clear();
                int expected = 0;
                for (int i = 0; i < 10; i++) {
                    for (String id : List.of("overview", "t-byx", "t-desk")) {
                        show(id);
                        expected++;
                        check(id.equals(router.route()), "round trip " + (i + 1) + " reaches " + id);
                    }
                }
                check(routeLog.size() == expected && routeLog.equals(expectedLog(10)), "10 round trips: exactly the requested routes were committed (" + routeLog.size() + ")");
                check(ctx.transitions.entries() == entries[0], "returning to the same View never replays the heavy enter (" + entries[0] + " -> " + ctx.transitions.entries() + ")");
                check(views.values().stream().filter(v -> v.node().isVisible()).count() == 1, "one visible view after the round trips");
                check(ctx.motion.runningLoops() <= 3, "no loop accumulation after 30 navigations: " + ctx.motion.runningLoops());
                next(300);
            });
            plan.add(() -> {
                entries[1] = ctx.transitions.entries();
                invoke("logout", String.class, null);
                next(600);
            });
            plan.add(() -> {
                check("auth:login".equals(router.route()), "logout reaches the login: " + router.route());
                check(ctx.motion.runningLoops() <= 1, "after logout only the login brand loop can run: " + ctx.motion.runningLoops());
                login();
                next(1500);
            });
            plan.add(() -> {
                check(ctx.transitions.entries() > entries[1], "a new session builds new instances: a new first enter is allowed (" + entries[1] + " -> " + ctx.transitions.entries() + ")");
                check("t-desk".equals(router.route()), "new session opens the default workspace");
                next(100);
            });
        }

        private static List<String> expectedLog(int n) {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                l.addAll(List.of("overview", "t-byx", "t-desk"));
            }
            return l;
        }

        // ---- L10: com security.dev.mode=true, o app normal NÃO usa provedor de desenvolvimento ------------------------------------

        private void prodAuth() {
            plan.add(() -> {
                check("true".equals(java.util.Properties.class.cast(propsOf()).getProperty("security.dev.mode")), "the temp profile really has security.dev.mode=true");
                check(ctx.emailProvider.getClass().getSimpleName().equals("ResendEmailOtpProvider") && ctx.smsProvider.getClass().getSimpleName().equals("TwilioVerifySmsProvider"),
                        "providers are the real ones: " + ctx.emailProvider.getClass().getSimpleName() + "/" + ctx.smsProvider.getClass().getSimpleName());
                check(ctx.developmentLabel == null, "no development label in the normal app");
                login();
                next(1200);
            });
            plan.add(() -> {
                check(!ctx.adminAccess.twoFactorConfigured(), "no two-factor is configured in this profile and the dev flag did not fake it");
                boolean refused = false;
                try {
                    ctx.adminAccess.startTwoFactor();
                } catch (panel.auth.TwoFactorNotConfiguredException e) {
                    refused = true;
                }
                check(refused, "starting admin verification is refused as NOT CONFIGURED (no dev bypass)");
                show("overview");
                next(800);
            });
            plan.add(() -> {
                check(!ctx.adminAccess.hasValidAdminSession(), "no admin elevation exists");
                check(!"overview".equals(router.route()), "Research stayed closed: " + router.route());
                check(!java.nio.file.Files.exists(java.nio.file.Path.of("target/classes/panel/auth/DevOtpProvider.class")), "DevOtpProvider is not in the production classes");
                next(100);
            });
        }

        private Object propsOf() throws Exception {
            java.util.Properties p = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "security.properties"))) {
                p.load(in);
            }
            return p;
        }

        // ---- 22/25. Views legadas e fronteira de CSS ---------------------------------------------------------------------

        private void legacy() {
            plan.add(() -> {
                login();
                next(1200);
            });
            plan.add(() -> {
                verifyAdmin();
                next(300);
            });
            plan.add(() -> {
                List<String> legacy = List.of("t-bot", "t-strategies", "t-signals", "t-portfolio", "t-positions", "t-orders", "t-performance", "t-activity", "t-wallet-verify",
                        "sessions", "dataset", "labels", "features", "hypotheses", "validation", "execution", "paper", "live", "jobs", "logs", "users", "settings");
                var host = shell().content();
                String hostSheets = String.join(",", host.getStylesheets());
                check(hostSheets.contains("panel.css") && hostSheets.contains("byx.css"), "legacy sheets live on the LegacyHost only");
                String sceneSheets = String.join(",", shell().getScene().getStylesheets());
                check(!sceneSheets.contains("panel.css") && !sceneSheets.contains("byx.css"), "the scene loads only V2 sheets");
                for (String id : legacy) {
                    show(id);
                    boolean opened = id.equals(router.route());
                    check(opened, "legacy view opens: " + id + (opened ? "" : " (route " + router.route() + ")"));
                    Node n = views.get(id).node();
                    check(n.isVisible() && inside(n, host), "legacy view " + id + " is inside the LegacyHost (CSS boundary)");
                    long visible = views.values().stream().filter(v -> v.node().isVisible()).count();
                    check(visible == 1, "one visible view after " + id);
                }
                for (String id : List.of("t-desk", "t-markets", "overview", "capture", "t-byx", "t-wallet", "t-benefits", "t-treasury", "t-profile", "t-security", "t-sessions",
                        "t-notifications", "t-account-activity", "t-settings", "h-faq", "h-help", "h-diagnostics", "h-about", "h-overview", "h-whats-new", "h-terms", "h-privacy",
                        "h-shortcuts", "sys-status")) {
                    show(id);
                    Node n = views.get(id).node();
                    check(id.equals(router.route()) && !inside(n, host), "V2 view " + id + " opens outside the LegacyHost");
                    check(n.getStyleClass().stream().noneMatch(c -> c.equals("page") || c.equals("card")) && n.lookupAll(".card").isEmpty() && n.lookupAll(".kv-row").isEmpty(),
                            "V2 view " + id + " uses no legacy classes");
                }
                next(100);
            });
        }

        private static boolean inside(Node n, Node ancestor) {
            for (Node x = n; x != null; x = x.getParent()) {
                if (x == ancestor) {
                    return true;
                }
            }
            return false;
        }
    }
}
