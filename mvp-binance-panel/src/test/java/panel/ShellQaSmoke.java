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
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.user.User;

/**
 * QA manual do shell real (não roda no surefire). Sobe o PanelApp num home temporário (dev mode, admin local,
 * OTP de desenvolvimento), sem dados de fixture: o shell mostra os estados reais vazios. Executa os passos
 * "rota@LARGURAxALTURA" de args[1] (separados por vírgula; "login" = tela de entrada) e grava PNGs em args[0].
 * Uso: java -cp target/classes:target/test-classes:<deps> panel.ShellQaSmoke out "login,t-desk@1440x900"
 */
public final class ShellQaSmoke {
    static Path output;
    static List<String> steps;
    static Throwable failure;
    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        steps = List.of(args[1].split(","));
        Path home = Files.createTempDirectory("byx-shell-qa-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        Files.write(output.resolve("report.txt"), report);
        report.forEach(System.out::println);
        if (failure != null) {
            throw new AssertionError("shell QA failed", failure);
        }
        System.out.println("SHELL_QA_OK " + output);
    }

    public static final class App extends PanelApp {
        private Stage stage;
        private AppContext ctx;
        private int index;
        private boolean entered;

        @Override
        public void start(Stage stage) {
            this.stage = stage;
            super.start(stage);
            try {
                Field f = PanelApp.class.getDeclaredField("ctx");
                f.setAccessible(true);
                ctx = (AppContext) f.get(this);
                ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "shell-qa-pass-1".toCharArray(), "+5511999991234");
                invoke("showEntry", String.class, null); // sai do first-run: a entrada passa a ser o Login
                later(this::next);
            } catch (Throwable e) {
                fail(e);
            }
        }

        private void enter() throws Exception {
            User admin = ctx.auth.login("qa-admin", "shell-qa-pass-1".toCharArray());
            invoke("afterLogin", User.class, admin);
            var flow = ctx.adminAccess.startTwoFactor();
            flow.sendEmailCode();
            flow.verifyEmail(ctx.devOtp.lastCode());
            flow.sendSmsCode();
            flow.verifySms(ctx.devOtp.lastCode());
            flow.finish(false);
            entered = true;
        }

        private void next() throws Exception {
            if (index >= steps.size()) {
                Platform.exit();
                return;
            }
            String step = steps.get(index++);
            String[] parts = step.split("@");
            String route = parts[0];
            if (parts.length > 1) {
                String[] wh = parts[1].split("x");
                resize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            }
            if (!route.equals("login")) {
                if (!entered) {
                    enter();
                }
                invoke("show", String.class, route);
            }
            later(() -> {
                shot(step.replace('@', '-'));
                next();
            });
        }

        private void resize(int w, int h) {
            double dw = stage.getWidth() - stage.getScene().getWidth();
            double dh = stage.getHeight() - stage.getScene().getHeight();
            stage.setWidth(w + (Double.isNaN(dw) ? 0 : dw));
            stage.setHeight(h + (Double.isNaN(dh) ? 0 : dh));
        }

        private void shot(String name) throws Exception {
            var image = stage.getScene().snapshot(null);
            Path file = output.resolve(name + ".png");
            javax.imageio.ImageIO.write(ControlGalleryTest.toAwt(image), "png", file.toFile());
            report.add("SHOT " + name + " scene=" + (int) stage.getScene().getWidth() + "x" + (int) stage.getScene().getHeight());
        }

        private void invoke(String name, Class<?> type, Object arg) throws Exception {
            Method m = PanelApp.class.getDeclaredMethod(name, type);
            m.setAccessible(true);
            m.invoke(this, arg);
        }

        private void later(Step s) {
            PauseTransition p = new PauseTransition(Duration.millis(900));
            p.setOnFinished(e -> {
                try {
                    s.run();
                } catch (Throwable t) {
                    fail(t);
                }
            });
            p.play();
        }

        private void fail(Throwable t) {
            failure = t;
            Platform.exit();
        }
    }

    interface Step {
        void run() throws Exception;
    }
}
