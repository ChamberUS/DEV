package panel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.authview.AuthScreens;
import panel.authview.LoginController;
import panel.design.ByxField;
import panel.shell.ByxShell;
import panel.shell.ShellRouter;

/**
 * Fluxos de autenticação V2 no app REAL (manual; fora do surefire), dirigidos pela UI: primeiro uso, credencial
 * inválida, Esc, "esqueci a senha" sem backend, bloqueio por tentativas, login, gate das telas de entrada,
 * sessão expirada com retorno, sair com confirmação e troca obrigatória de senha.
 * Uso: java ... panel.PackageC2AuthFlowQa <saída>; modo de motion por -Dbyx.qa.motion.
 */
public final class PackageC2AuthFlowQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-auth-qa-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        String mode = System.getProperty("byx.qa.motion", "FULL");
        lines.add("RESULT mode=" + mode + " failures=" + failures);
        Files.write(output.resolve("auth-" + mode.toLowerCase() + ".txt"), lines);
        lines.forEach(System.out::println);
        if (failures > 0) {
            System.exit(1);
        }
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
            return panel.QaContext.create();
        }

        private Stage stage;
        private AppContext ctx;
        private ShellRouter router;
        private final List<Step> plan = new ArrayList<>();
        private int index;

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

        private AuthScreens screens() throws Exception {
            return (AuthScreens) field("authScreens");
        }

        private ByxShell shell() throws Exception {
            return (ByxShell) field("shell");
        }

        private Node root() {
            return stage.getScene().getRoot();
        }

        private ByxField fieldNamed(String label) {
            return root().lookupAll(".byx-field").stream().map(n -> (ByxField) n)
                    .filter(f -> f.labelText().equalsIgnoreCase(label) && f.getScene() != null).findFirst().orElseThrow(
                            () -> new AssertionError("no field " + label));
        }

        private void type(String label, String text) {
            fieldNamed(label).input().setText(text);
        }

        private Button button(String text) {
            return root().lookupAll(".button").stream().map(n -> (Button) n)
                    .filter(b -> (text.equals(b.getText()) || text.equals(b.getAccessibleText())) && b.isVisible() && b.getScene() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no button " + text));
        }

        private boolean hasText(String text) {
            return root().lookupAll(".label").stream().anyMatch(n -> text.equals(((javafx.scene.control.Label) n).getText()));
        }

        private void key(KeyCode code) {
            Node target = stage.getScene().getFocusOwner() != null ? stage.getScene().getFocusOwner() : root();
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        }

        /** Captura opcional (-Dbyx.qa.shots=dir): raiz real numa cena fora da tela do tamanho exato. */
        private final java.util.Set<String> shotNames=new java.util.HashSet<>();
        private void shot(String name, int w, int h) throws Exception {
            String dir=System.getProperty("byx.qa.shots");if(dir==null)return;
            if(!shotNames.add(name.replaceAll("-\\d+x\\d+$","")))return;
            var language=panel.i18n.Strings.language();var theme=panel.design.ByxTheme.mode();
            for(var lang:panel.i18n.Strings.Lang.values())for(var mode:java.util.List.of(panel.design.ThemeMode.DARK,panel.design.ThemeMode.LIGHT))for(var size:new int[][]{{1920,1080},{1440,900},{1100,700}}){
                panel.i18n.Strings.use(lang);panel.design.ByxTheme.select(mode);var window=stage.getScene();var rootNode=window.getRoot();window.setRoot(new javafx.scene.layout.Pane());var off=new javafx.scene.Scene(rootNode,size[0],size[1]);off.getStylesheets().setAll(window.getStylesheets());
                try(var locale=new panel.i18n.LocaleView(off)){rootNode.applyCss();rootNode.layout();Files.createDirectories(Path.of(dir));String key=name.replaceAll("-\\d+x\\d+$","");javax.imageio.ImageIO.write(ControlGalleryTest.toAwt(off.snapshot(null)),"png",Path.of(dir,mode.name().toLowerCase()+"-"+lang.tag+"-"+key+"-"+size[0]+"x"+size[1]+".png").toFile());}
                finally{off.setRoot(new javafx.scene.layout.Pane());window.setRoot(rootNode);}
            }
            panel.i18n.Strings.use(language);panel.design.ByxTheme.select(theme);
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

        private void signIn(String user, String password) {
            type("Email or username", user);
            type("Password", password);
            button("Sign in").fire();
        }

        private void plan() {
            plan.add(() -> {
                check(AuthScreens.LOGIN.equals(router.route()) && !ctx.auth.firstRun(), "frozen Service client opens login, not local administrator setup");
                router.request(AuthScreens.SETUP);
                check(AuthScreens.LOGIN.equals(router.route()), "unavailable setup cannot bypass the entry gate");
                panel.QaContext.dev().add("qa-admin", "qa@example.invalid", "+5511999991234", "auth-qa-pass-1", panel.security.Role.ADMIN, false);
                check(ctx.sessions.user().isEmpty(), "seeding the isolated authority grants no UI session");
                shot("auth-login-default-1440x900", 1440, 900);
                signIn("qa-admin", "wrong-password-1");
                check(screens().loginController().state() == LoginController.State.LOADING, "loading while the service runs");
                next(900);
            });
            plan.add(() -> {
                check(screens().loginController().state() == LoginController.State.INVALID, "invalid credentials state");
                check(hasText("Email, username or password is incorrect."), "neutral error copy");
                shot("auth-login-invalid-1440x900", 1440, 900);
                shot("auth-login-invalid-1600x1000", 1600, 1000);
                shot("auth-login-invalid-1920x1080", 1920, 1080);
                check(ctx.sessions.user().isEmpty(), "no session after a failure");
                key(KeyCode.ESCAPE);
                check(screens().loginController().state() == LoginController.State.DEFAULT, "Esc returns to default");
                button("Forgot password?").fire();
                check(AuthScreens.FORGOT.equals(router.route()), "forgot is a route");
                check(root().lookupAll(".byx-banner").stream().anyMatch(n -> n.getAccessibleText().contains("Nothing was sent")),
                        "forgot never claims an email was sent");
                shot("auth-forgot-1440x900", 1440, 900);
                button("Back to sign in").fire();
                check(AuthScreens.LOGIN.equals(router.route()), "back to login through the router");
                attempts = 0; // bloqueio real a seguir: falhas pela UI para outro identificador
                next(50);
            });
            plan.add(this::rateLimitLoop);
            plan.add(() -> {
                check(screens().loginController().state() == LoginController.State.RATE_LIMITED, "rate limited by the synthetic authority");
                Button countdown = root().lookupAll(".byx-btn").stream().map(n -> (Button) n)
                        .filter(b -> b.getText().startsWith("Try again in")).findFirst().orElseThrow();
                check(countdown.isDisabled(), "countdown button disabled: " + countdown.getText());
                shot("auth-login-rate-limited-1440x900", 1440, 900);
                key(KeyCode.ESCAPE); // Esc não fura o bloqueio
                check(screens().loginController().state() == LoginController.State.RATE_LIMITED, "Esc does not bypass the lockout");
                // gate: rota do app sem sessão é negada
                router.request("t-desk");
                check(AuthScreens.LOGIN.equals(router.route()), "app routes are denied without a session");
                lines.add("INFO rate-limit retryAfter=" + screens().loginController().retryAfter());
                next(screens().loginController().retryAfter().toMillis() + 1500); // wait for the authority-reported countdown
            });
            plan.add(() -> {
                check(screens().loginController().state() == LoginController.State.DEFAULT, "lockout ends after the real retryAfter");
                signIn("qa-admin", "auth-qa-pass-1");
                next(1200);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()) && shell() != null, "login opens the default workspace through the router");
                router.request(AuthScreens.LOGIN);
                check("t-home".equals(router.route()), "entry routes are denied with a session");
                router.request("t-wallet");
                next(400);
            });
            // sessão expirada (P3.11)
            plan.add(() -> {
                check("t-wallet".equals(router.route()), "on Wallet before expiry");
                ctx.sessions.logout(); // a sessão real some com o app aberto
                next(1600);
            });
            plan.add(() -> {
                check(shell().overlay().openDialogs() == 1, "session expired dialog is open");
                check(hasText("Session expired"), "dialog title");
                shot("auth-session-expired-1440x900", 1440, 900);
                key(KeyCode.ESCAPE);
                check(shell().overlay().openDialogs() == 1, "Esc does nothing on the session dialog");
                next(2500);
            });
            plan.add(() -> {
                check("t-wallet".equals(router.route()), "nothing navigates behind the session dialog");
                button("Sign in again").fire();
                check(AuthScreens.LOGIN.equals(router.route()), "sign in again opens login");
                check(hasText("Your session expired. Sign in again to continue."), "expiry notice on login");
                signIn("qa-admin", "auth-qa-pass-1");
                next(1200);
            });
            plan.add(() -> {
                check("t-wallet".equals(router.route()), "returns to the route captured at expiry: " + router.route());
                // admin cria um trader com senha temporária (exige verificação real de admin, OTP de dev)
                var flow = ctx.adminAccess.startTwoFactor();
                flow.sendEmailCode();
                flow.verifyEmail(panel.QaContext.dev().lastCode());
                flow.sendSmsCode();
                flow.verifySms(panel.QaContext.dev().lastCode());
                flow.finish(false);
                panel.QaContext.dev().add("qa-trader", "trader@example.invalid", null, "temporary-pass-1", panel.security.Role.USER, true);
                // sair pelo menu: confirmação primeiro
                shell().topBar().avatar().fire();
                button("Sign out").fire(); // item do menu
                next(300);
            });
            plan.add(() -> {
                check(shell() != null && shell().overlay().openDialogs() == 1 && ctx.sessions.user().isPresent(), "sign out asks first");
                Button cancel = button("Cancel");
                check(stage.getScene().getFocusOwner() == cancel, "destructive confirm focuses Cancel");
                root().lookupAll(".byx-btn.danger").stream().map(n -> (Button) n).filter(b -> b.getScene() != null).findFirst().orElseThrow().fire();
                next(400);
            });
            plan.add(() -> {
                check(ctx.sessions.user().isEmpty() && AuthScreens.LOGIN.equals(router.route()), "signed out to login");
                signIn("qa-trader", "temporary-pass-1");
                next(1200);
            });
            plan.add(() -> {
                check(AuthScreens.CHANGE_PASSWORD.equals(router.route()), "temporary password requires a change");
                shot("auth-change-password-1440x900", 1440, 900);
                type("Temporary password", "temporary-pass-1");
                type("New password", "trader-new-pass-1");
                type("Confirm new password", "trader-new-pass-2");
                button("Change password").fire();
                check(AuthScreens.CHANGE_PASSWORD.equals(router.route()), "mismatch keeps the change screen");
                type("Temporary password", "temporary-pass-1");
                type("New password", "trader-new-pass-1");
                type("Confirm new password", "trader-new-pass-1");
                button("Change password").fire();
                next(600);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "password changed, workspace opens");
                check(!shell().switcher().button(panel.shell.ShellContext.RESEARCH).isVisible(), "trader has no Research workspace");
                router.request("overview");
                next(500);
            });
            plan.add(() -> {
                check("t-home".equals(router.route()), "Research denied for a trader (the UI grants nothing)");
                lines.add("FINAL route=" + router.route() + " user=" + ctx.sessions.user().map(u -> u.user().username()).orElse("-"));
                next(100);
            });
        }

        private int attempts;

        /** The fixture blocks globally after four failures; the next request is refused. */
        private void rateLimitLoop() throws Exception {
            if (screens().loginController().state() == LoginController.State.RATE_LIMITED) {
                check(attempts == 4, "fixture lockout follows four global failures including the initial wrong password");
                run();
                return;
            }
            if (attempts >= 6) throw new AssertionError("authority did not enforce its lockout");
            if (screens().loginController().state() != LoginController.State.LOADING) {
                screens().loginController().reset(); // Esc entre tentativas (limpa só o erro)
                signIn("nobody", "wrong-password-" + attempts);
                attempts++;
            }
            after(700, this::rateLimitLoop);
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
