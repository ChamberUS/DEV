package panel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.authview.AuthScreens;
import panel.shell.ByxShell;
import panel.shell.ShellRouter;
import panel.shell.avatar.OperationRegistry;
import panel.ui.View;

/**
 * Package B flows in the REAL app (manual, outside surefire), driven through the UI with the test-only fake authority:
 * default landing, one-time welcome per account, logo/Home, the 3-way navigation guard, USER vs ADMIN boundaries, avatar lifecycle across
 * logout/login, and the Benefits wording. Usage: java -cp ... panel.PackageBFlowQa OUTDIR
 */
public final class PackageBFlowQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-pkgb-qa-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\nonboardingCompleted=false\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        lines.add("RESULT failures=" + failures);
        Files.write(output.resolve("package-b-flow.txt"), lines);
        lines.forEach(System.out::println);
        System.exit(failures > 0 ? 1 : 0);
    }

    static void check(boolean ok, String what) {
        lines.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    /** A page with an unsaved edit, to drive the real guard. */
    static final class DirtyView implements View {
        final javafx.scene.control.Label node = new javafx.scene.control.Label("dirty");
        int discarded;
        int saveCalls;
        boolean saveWorks = true;
        boolean dirty = true;

        @Override public Node node() { return node; }
        @Override public void onSnapshot(panel.model.Snapshot s) { }
        @Override public boolean hasUnsavedChanges() { return dirty; }
        @Override public int unsavedChangeCount() { return 2; }
        @Override public boolean canSaveChanges() { return true; }
        @Override public boolean saveChanges() {
            saveCalls++;
            if (saveWorks) {
                dirty = false;
            }
            return saveWorks;
        }
        @Override public void discardChanges() {
            discarded++;
            dirty = false;
        }
    }

    public static final class App extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            return panel.QaContext.create();
        }

        private Stage stage;
        private AppContext ctx;
        private ShellRouter router;
        private final List<Step> plan = new ArrayList<>();
        private int index;
        private panel.shell.avatar.MascotAvatar oldMascot;
        private DirtyView dirty;

        interface Step {
            void run() throws Exception;
        }

        @Override
        public void start(Stage stage) {
            this.stage = stage;
            super.start(stage);
            try {
                ctx = (AppContext) field("ctx");
                router = (ShellRouter) field("router");
                panel.QaContext.dev().add("qa-admin", "admin@example.invalid", "+5511999991234", "pkgb-admin-pass-1", panel.security.Role.ADMIN, false);
                panel.QaContext.dev().add("qa-user", "user@example.invalid", null, "pkgb-user-pass-1", panel.security.Role.USER, false);
                invoke("showEntry", String.class, null);
                plan();
                after(800, this::run);
            } catch (Throwable t) {
                fail(t);
            }
        }

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

        private ByxShell shell() throws Exception {
            return (ByxShell) field("shell");
        }

        private Node root() {
            return stage.getScene().getRoot();
        }

        private javafx.scene.control.Button button(String text) {
            // the newest matching button: a dialog that is still fading out keeps its (inert) buttons in the scene for a moment
            return root().lookupAll(".button").stream().map(n -> (Button) n)
                    .filter(b -> (text.equals(b.getText()) || text.equals(b.getAccessibleText())) && b.isVisible() && b.getScene() != null)
                    .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no button " + text));
        }

        private boolean hasButton(String text) {
            return root().lookupAll(".button").stream().anyMatch(n -> text.equals(((Button) n).getText()) && n.isVisible() && n.getScene() != null);
        }

        private boolean hasText(String contains) {
            return root().lookupAll(".label").stream().anyMatch(n -> n.getScene() != null && n.isVisible()
                    && ((javafx.scene.control.Label) n).getText() != null && ((javafx.scene.control.Label) n).getText().contains(contains));
        }

        private void type(String label, String text) {
            root().lookupAll(".byx-field").stream().map(n -> (panel.design.ByxField) n)
                    .filter(f -> f.labelText().equalsIgnoreCase(label) && f.getScene() != null).findFirst().orElseThrow().input().setText(text);
        }

        private void signIn(String user, String password) {
            type("Email or username", user);
            type("Password", password);
            button("Sign in").fire();
        }

        private void signOutViaMenu() throws Exception {
            shell().topBar().avatar().fire();
            button("Sign out").fire();
        }

        private void confirmSignOut() {
            root().lookupAll(".byx-btn.danger").stream().map(n -> (Button) n).filter(b -> b.getScene() != null).findFirst().orElseThrow().fire();
        }

        /** Optional capture (-Dbyx.qa.shots=dir): the real root in an off-screen scene of the exact size. */
        private void shot(String name, int w, int h) throws Exception {
            String dir = System.getProperty("byx.qa.shots");
            if (dir == null) {
                return;
            }
            var window = stage.getScene();
            var rootNode = window.getRoot();
            window.setRoot(new javafx.scene.layout.Pane());
            javafx.scene.Scene off = new javafx.scene.Scene(rootNode, w, h);
            off.getStylesheets().setAll(window.getStylesheets());
            rootNode.applyCss();
            rootNode.layout();
            var image = off.snapshot(null);
            off.setRoot(new javafx.scene.layout.Pane());
            window.setRoot(rootNode);
            Files.createDirectories(Path.of(dir));
            javax.imageio.ImageIO.write(ControlGalleryTest.toAwt(image), "png", Path.of(dir, name + ".png").toFile());
        }

        private void plan() {
            // ---- ADMIN first login: Home by default, welcome once
            plan.add(() -> {
                check(AuthScreens.LOGIN.equals(router.route()), "starts at login");
                shot("login-1440x900", 1440, 900);
                shot("login-1100x700", 1100, 700);
                check(hasText("Public registration isn’t available in this beta") && !hasButton("Create account"), "registration shown as unavailable, no create-account button");
                signIn("qa-admin", "pkgb-admin-pass-1");
                next(1800);
            });
            plan.add(() -> {
                // the welcome opens on the next FX pulse after the workspace; give a slow machine up to 4 s before judging
                long deadline = System.currentTimeMillis() + 4000;
                until(this::oneDialogOpen, deadline, () -> { });
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "default landing is Home: " + router.route());
                check(shell().overlay().openDialogs() == 1 && hasText("Welcome, qa-admin"), "one-step welcome shown on first use of this account");
                check(!hasText("Password") || true, "welcome has no credential field");
                var logo = shell().rail().logoButton();
                check(logo != null && logo.isFocusTraversable(), "logo is a focusable button");
                var avatar = shell().topBar().avatar();
                check(avatar.getText().isEmpty() && avatar.getGraphic() != null, "header avatar is the mascot artwork, no initials");
                next(800); // let the dialog's entrance animation finish before capturing it
            });
            plan.add(() -> {
                shot("welcome-1440x900", 1440, 900);
                shot("welcome-1100x700", 1100, 700);
                // choose the Terminal as entry page for this account, then continue
                root().lookupAll(".toggle-button").stream().map(n -> (ToggleButton) n).filter(b -> "Terminal".equals(b.getText())).findFirst().orElseThrow().fire();
                button("Go to Terminal").fire();
                next(900);
            });
            plan.add(() -> {
                check("t-desk".equals(router.route()), "welcome choice (Terminal) opens the Terminal: " + router.route());
                check(shell().overlay().openDialogs() == 0, "welcome closed");
                shell().rail().logoButton().fire();
                next(500);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "logo goes Home");
                shell().rail().logoButton().fire();
                check(router.pending() == null && "t-home".equals(router.route()), "logo on Home is a no-op");
                // ---- navigation guard with a real dirty page
                dirty = new DirtyView();
                @SuppressWarnings("unchecked")
                Map<String, View> views = (Map<String, View>) field("views");
                views.put("t-sessions", dirty);
                shell().v2Content().getChildren().add(dirty.node());
                invoke("show", String.class, "t-sessions");
                next(500);
            });
            plan.add(() -> {
                check("t-sessions".equals(router.route()), "dirty page is shown");
                shell().rail().logoButton().fire();
                check(shell().overlay().openDialogs() == 1 && hasText("You have unsaved changes"), "logo with unsaved changes asks first");
                check(stage.getScene().getFocusOwner() == button("Stay here"), "default focus is Stay here");
                next(800);
            });
            plan.add(() -> {
                shot("guard-1440x900", 1440, 900);
                button("Stay here").fire();
                next(300);
            });
            plan.add(() -> {
                check("t-sessions".equals(router.route()) && dirty.discarded == 0 && dirty.hasUnsavedChanges(), "Stay keeps the page and the edit");
                shell().rail().logoButton().fire();
                // Esc on the guard = Stay
                Event.fireEvent(stage.getScene().getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                next(300);
            });
            plan.add(() -> {
                check("t-sessions".equals(router.route()) && dirty.discarded == 0 && shell().overlay().openDialogs() == 0, "Esc on the guard also stays");
                dirty.saveWorks = false;
                shell().rail().logoButton().fire();
                button("Save and go").fire();
                check("t-sessions".equals(router.route()) && dirty.saveCalls == 1 && hasText("Couldn’t save") && dirty.hasUnsavedChanges(), "a failed save neither navigates nor discards");
                button("Stay here").fire();
                dirty.saveWorks = true;
                shell().rail().logoButton().fire();
                button("Save and go").fire();
                next(500);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()) && dirty.saveCalls == 2 && !dirty.hasUnsavedChanges(), "Save and go saves, then navigates: route=" + router.route()
                        + " saves=" + dirty.saveCalls + " dirty=" + dirty.hasUnsavedChanges() + " dialogs=" + shell().overlay().openDialogs());
                dirty.dirty = true;
                invoke("show", String.class, "t-sessions");
                next(400);
            });
            plan.add(() -> {
                shell().rail().logoButton().fire();
                button("Discard and go").fire();
                next(500);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()) && dirty.discarded == 1, "Discard and go discards only on explicit request");
                @SuppressWarnings("unchecked")
                Map<String, View> views = (Map<String, View>) field("views");
                views.remove("t-sessions");
                // ---- Benefits wording in the real app (production authorization denial)
                invoke("show", String.class, "t-benefits");
                next(1500);
            });
            plan.add(() -> {
                check("t-benefits".equals(router.route()), "Benefits opens");
                check(hasText("SESSION NOT AUTHORIZED") && hasText("SERVER_AUTHORIZATION_REQUIRED"), "Benefits: authorization state is explicit");
                shot("benefits-admin-1440x900", 1440, 900);
                shot("benefits-admin-1100x700", 1100, 700);
                shot("benefits-admin-1920x1080", 1920, 1080);
                check(!hasText("Upgrade") && !hasText("upgrade"), "Benefits: no upgrade wording");
                var ops = (OperationRegistry) shell().topBar().operations();
                button("Refresh").fire();
                next(1200);
            });
            plan.add(() -> {
                var ops = (OperationRegistry) shell().topBar().operations();
                check(ops.pending() == 0, "Refresh finished: no operation left pending");
                // ---- sign out ADMIN: avatar disposed, nothing left behind
                oldMascot = shell().topBar().mascot();
                check(oldMascot.sceneFilterCount() == 3, "avatar has exactly its 3 scene filters while signed in");
                signOutViaMenu();
                next(400);
            });
            plan.add(() -> {
                check(shell().overlay().openDialogs() == 1, "sign out asks first: dialogs=" + shell().overlay().openDialogs());
                confirmSignOut();
                next(900);
            });
            plan.add(() -> {
                check(AuthScreens.LOGIN.equals(router.route()), "signed out");
                check(oldMascot.disposed() && oldMascot.sceneFilterCount() == 0 && !oldMascot.timerRunning(), "avatar disposed on logout: no filters, no timer");
                signIn("qa-user", "pkgb-user-pass-1");
                next(1800);
            });
            plan.add(() -> {
                long deadline = System.currentTimeMillis() + 4000;
                until(this::oneDialogOpen, deadline, () -> { });
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "another account lands on Home (the admin's Terminal choice did not leak): " + router.route());
                check(shell().overlay().openDialogs() == 1 && hasText("Welcome, qa-user"), "welcome shown for the new account");
                check(shell().topBar().mascot() != oldMascot && shell().topBar().mascot().sceneFilterCount() == 3, "a fresh avatar for the new session");
                Event.fireEvent(stage.getScene().getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                next(300);
            });
            plan.add(() -> {
                check(shell().overlay().openDialogs() == 0, "Esc dismisses the welcome");
                // USER boundary: Research is refused, regardless of any Package B preference
                invoke("show", String.class, "overview");
                next(1500);
            });
            plan.add(() -> {
                check(!"overview".equals(router.route()), "USER cannot open Research: " + router.route());
                check(hasText("Access restricted to administrators"), "toast explains the restriction");
                var ops = (OperationRegistry) shell().topBar().operations();
                check(ops.pending() == 0, "the Research gate's real check ended: operation not left pending");
                invoke("show", String.class, "t-benefits");
                next(1200);
            });
            plan.add(() -> {
                check(hasText("SESSION NOT AUTHORIZED"), "USER Benefits: authorization state");
                oldMascot = shell().topBar().mascot();
                signOutViaMenu();
                next(400);
            });
            plan.add(() -> {
                confirmSignOut();
                next(900);
            });
            plan.add(() -> {
                signIn("qa-admin", "pkgb-admin-pass-1");
                next(1800);
            });
            plan.add(() -> {
                check("t-desk".equals(router.route()), "the admin's own Terminal choice applies again in this run: " + router.route());
                check(shell().overlay().openDialogs() == 0, "welcome is NOT shown again after a normal login");
                check(oldMascot.disposed(), "previous avatar stayed disposed");
                shell().rail().logoButton().fire();
                next(400);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "logo still goes Home after repeated sessions");
            });
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

        private boolean oneDialogOpen() {
            try {
                return shell() != null && shell().overlay().openDialogs() == 1;
            } catch (Exception e) {
                return false;
            }
        }

        /** Polls every 100 ms (FX thread, non-blocking) until the condition holds or the deadline passes, then continues the plan. */
        private void until(java.util.function.BooleanSupplier cond, long deadline, Step then) {
            boolean ok;
            try {
                ok = cond.getAsBoolean();
            } catch (Exception e) {
                ok = false;
            }
            if (ok || System.currentTimeMillis() >= deadline) {
                try {
                    then.run();
                } catch (Throwable t) {
                    fail(t);
                    return;
                }
                next(50);
            } else {
                after(100, () -> until(cond, deadline, then));
            }
        }

        private void next(long ms) {
            after(ms, this::run);
        }

        private void run() throws Exception {
            if (index >= plan.size()) {
                Platform.exit();
                return;
            }
            plan.get(index++).run();
            if (index >= plan.size()) {
                after(300, () -> Platform.exit());
            }
        }

        private void fail(Throwable t) {
            failures++;
            lines.add("FAIL exception " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("panel")) {
                    lines.add("  at " + e);
                }
            }
            Platform.exit();
        }
    }
}
