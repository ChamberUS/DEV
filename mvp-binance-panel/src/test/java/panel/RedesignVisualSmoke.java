package panel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.auth.OtpService;
import panel.user.User;

/** Offline native smoke, with an isolated REAL-mode empty backend and development OTP. */
public final class RedesignVisualSmoke {
    static Path evidence;
    static Throwable failure;
    private RedesignVisualSmoke() { }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("byx.legacy.qa")) { // LEGACY QA: mede o cromo anterior ao V2 e acessa o PanelApp por reflexão; não vale como evidência do V2
            System.err.println("LEGACY QA (pre-V2 chrome): not valid V2 evidence. Use ShellQaSmoke/ShellNavigationQa/AuthFlowQa and the step QAs. Pass -Dbyx.legacy.qa=true to run anyway.");
            return;
        }
        evidence = args.length == 0 ? Files.createTempDirectory("byx-ui-qa-") : Path.of(args[0]);
        Files.createDirectories(evidence);
        Path home = Files.createTempDirectory("byx-ui-home-");
        Path config = Files.createDirectory(home.resolve(".mvp-binance-panel"));
        Files.writeString(config.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(config.resolve("settings.properties"), "dataSource=REAL\nprojectPath="
                + home.resolve("empty-project") + "\ncliPath=/usr/bin/false\nmotion=OFF\n");
        System.setProperty("user.home", home.toString());
        Application.launch(SmokeApp.class, args);
        if (failure != null) throw new AssertionError("Native visual smoke failed", failure);
        System.out.println("REDESIGN_VISUAL_SMOKE_OK " + evidence);
    }

    public static final class SmokeApp extends PanelApp {
        private AppContext context;
        private Stage window;
        private int screen;
        private int size;
        private final String[] screens = {"login", "t-desk", "overview", "capture", "t-byx", "t-wallet",
                "t-benefits", "t-treasury", "validation", "t-markets", "t-settings", "t-profile", "palette"};
        private final int[][] sizes = {{1440, 900}, {1600, 1000}, {1920, 1080}};
        private User admin;
        private boolean authChecked;

        @Override public void start(Stage stage) {
            super.start(stage); window = stage;
            try {
                Field field = PanelApp.class.getDeclaredField("ctx"); field.setAccessible(true);
                context = (AppContext) field.get(this);
                context.userService.createInitialAdmin("qa-admin", "qa@example.invalid",
                        "visual-smoke-pass-1".toCharArray(), "+5511999991234");
                admin = context.auth.login("qa-admin", "visual-smoke-pass-1".toCharArray());
                invoke("showEntry", String.class, null);
                later(this::next);
            } catch (Throwable error) { fail(error); }
        }

        private void authorize() {
            if (context.adminAccess.hasValidAdminSession()) return;
            var flow = context.adminAccess.startTwoFactor();
            flow.sendEmailCode(); check(flow.verifyEmail(context.devOtp.lastCode()) == OtpService.Result.OK, "Email OTP");
            flow.sendSmsCode(); check(flow.verifySms(context.devOtp.lastCode()) == OtpService.Result.OK, "SMS OTP");
            flow.finish(false);
        }

        private void next() throws Exception {
            if (screen == screens.length) {
                screen = 0; size++;
                if (size == sizes.length) { permissionChecks(); return; }
            }
            String id = screens[screen];
            if (id.equals("login")) invoke("showEntry", String.class, null);
            else {
                if (id.equals("t-desk")) {
                    invoke("afterLogin", User.class, admin);
                    context.research.close();
                    context.research.snapshot.set(new panel.adapter.FileResearchBackend(context.cli).load(context.settings));
                    if (size == 0 && !authChecked) {
                        invoke("showTwoFactor", String.class, "overview");
                        later(this::authEmail);
                        return;
                    }
                    authorize();
                }
                if (id.equals("palette")) { invoke("show", String.class, "t-desk"); invoke("openPalette", null, null); }
                else invoke("show", String.class, id);
            }
            int width = sizes[size][0], height = sizes[size][1];
            window.setWidth(width + window.getWidth() - window.getScene().getWidth());
            window.setHeight(height + window.getHeight() - window.getScene().getHeight());
            later(() -> {
                shot(id + "-" + width + "x" + height);
                screen++; next();
            });
        }

        private Parent authRoot() throws Exception {
            Field field = PanelApp.class.getDeclaredField("tfOverlay"); field.setAccessible(true);
            return (Parent) field.get(this);
        }
        private void authButton(String text) throws Exception {
            authRoot().lookupAll(".button").stream().map(n -> (Button) n).filter(b -> b.getText().equals(text))
                    .findFirst().orElseThrow().fire();
        }
        private void code(String value) throws Exception {
            ((javafx.scene.control.TextField) authRoot().lookup(".otp-input")).setText(value);
        }
        private void authEmail() throws Exception {
            shot("auth-email"); authButton("Resend code");
            later(() -> {
                check(authRoot().lookupAll(".button").stream().map(n -> (Button) n)
                        .anyMatch(b -> b.getText().startsWith("Resend in") && b.isDisabled()), "Real resend cooldown");
                shot("auth-cooldown"); code("000000".equals(context.devOtp.lastCode()) ? "111111" : "000000");
                authButton("Verify Email");
                later(() -> {
                    check(authRoot().lookup(".otp-invalid") != null, "Invalid OTP styling");
                    check(!context.adminAccess.hasValidAdminSession(), "Invalid OTP never authorizes");
                    shot("auth-invalid-code"); code(context.devOtp.lastCode()); authButton("Verify Email");
                    later(() -> {
                        shot("auth-sms"); authButton("Resend code");
                        later(() -> { code(context.devOtp.lastCode()); authButton("Verify Phone");
                            later(() -> { shot("auth-verification-success"); authButton("Continue to Research");
                                later(() -> { check(context.adminAccess.hasValidAdminSession(), "Real AdminSession from UI");
                                    authChecked = true; next(); });
                            });
                        });
                    });
                });
            });
        }

        private void permissionChecks() throws Exception {
            invoke("show", String.class, "t-desk");
            context.userService.createUser("qa-user", "user@example.invalid", "temp-pass-2".toCharArray(), null,
                    panel.security.Role.USER);
            invoke("show", String.class, "overview");
            context.sessions.grantAdmin(new panel.auth.AdminSession(java.time.Instant.now().minusSeconds(3600),
                    panel.auth.AuthMethod.TWO_FACTOR, java.time.Duration.ofMinutes(30)));
            check(!context.adminAccess.hasValidAdminSession(), "Session expiry observed before watchdog");
            invoke("watchAdminSession", null, null);
            Field viewsField = PanelApp.class.getDeclaredField("views"); viewsField.setAccessible(true);
            var views = (java.util.Map<?, ?>) viewsField.get(this);
            check(!((panel.ui.View) views.get("overview")).node().isVisible(), "Expired Research hidden");
            check(((panel.ui.View) views.get("t-desk")).node().isVisible(), "Expired Admin returns to Trading");
            shot("expired-admin-session");
            context.auth.logout();
            var user = context.auth.login("qa-user", "temp-pass-2".toCharArray());
            context.userService.changeOwnPassword(user.id(), "temp-pass-2".toCharArray(), "user-new-pass-3".toCharArray());
            user = context.auth.login("qa-user", "user-new-pass-3".toCharArray());
            invoke("afterLogin", User.class, user);
            invoke("show", String.class, "capture");
            check(!context.adminAccess.hasValidAdminSession(), "USER Research denied");
            check(window.getScene().getRoot().lookupAll(".nav-item").stream()
                    .noneMatch(n -> n.getAccessibleText().equals("Users")), "USER admin navigation hidden");
            check(panel.ui.CommandPalette.commands(false, false).stream()
                    .noneMatch(c -> c.target() != null && !c.target().startsWith("t-")), "USER palette permissions");
            invoke("openPalette", null, null);
            later(() -> { shot("user-palette"); Platform.exit(); });
        }

        private void shot(String name) throws Exception {
            Parent root = window.getScene().getRoot(); root.applyCss(); root.layout();
            var image = window.getScene().snapshot(null);
            var pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            javax.imageio.ImageIO.write(pixels, "png", evidence.resolve(name + ".png").toFile());
            if (name.startsWith("validation")) {
                check(root.lookupAll(".badge").stream().filter(n -> n instanceof Label)
                        .map(n -> ((Label) n).getText()).anyMatch(t -> t.contains("LOCKED")), "ResearchGuard UI remains locked");
            }
            if (name.startsWith("t-byx") || name.startsWith("t-wallet") || name.startsWith("t-benefits") || name.startsWith("t-treasury")) {
                check(root.lookupAll(".environment-test").stream().filter(n -> n instanceof Label)
                        .map(n -> ((Label) n).getText()).anyMatch(t -> t.contains("NO FINANCIAL VALUE")), "BYX TEST labeling");
            }
            check(root.lookupAll(".button").stream().filter(n -> n instanceof Button)
                    .noneMatch(n -> java.util.Set.of("START", "STOP", "KILL").contains(((Button) n).getText())), "No capture process controls");
        }

        private void invoke(String name, Class<?> type, Object value) throws Exception {
            Method method = type == null ? PanelApp.class.getDeclaredMethod(name) : PanelApp.class.getDeclaredMethod(name, type);
            method.setAccessible(true);
            if (type == null) method.invoke(this); else method.invoke(this, value);
        }
        private void later(Step step) {
            PauseTransition pause = new PauseTransition(Duration.millis(450));
            pause.setOnFinished(e -> { try { step.run(); } catch (Throwable error) { fail(error); } }); pause.play();
        }
        private void fail(Throwable error) { failure = error; Platform.exit(); }
        private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
        private interface Step { void run() throws Exception; }
    }
}
