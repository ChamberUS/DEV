package panel.researchview;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.model.Snapshot;
import panel.model.CaptureSnapshot;
import panel.ui.View;
import panel.user.User;

/**
 * QA visual de Research Overview e Capture no app REAL (manual; não roda no surefire). Sobe o PanelApp num home temporário com
 * backend vazio (estado real: sem feed, sem conta) e poll de 1 h para que um fixture isolado não seja sobrescrito.
 * Passos separados por vírgula: "real" (estado real, sem fixture), "fx:NOME" (fixture isolado de QA), "tab:N"
 * (aba do blotter), "ctx:N" (aba de contexto), "wait:MS", "shot:NOME@LxA". Uso:
 * java ... panel.tradeview.ResearchQa saída "real,shot:no-feed-1440@1440x900,fx:live,shot:live-1920@1920x1080"
 */
public final class ResearchQa {
    static Path output;
    static List<String> steps;
    static Throwable failure;
    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        steps = List.of(args[1].split(","));
        Path home = Files.createTempDirectory("byx-research-qa-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\npollSeconds=3600\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        Files.write(output.resolve("report.txt"), report);
        report.forEach(System.out::println);
        if (failure != null) {
            throw new AssertionError("desk QA failed", failure);
        }
        System.out.println("DESK_QA_OK " + output);
    }

    public static final class App extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            return panel.QaContext.create();
        }

        private Stage stage;
        private AppContext ctx;
        private int index;
        private int width = 1440;
        private int height = 900;

        @Override
        public void start(Stage stage) {
            this.stage = stage;
            super.start(stage);
            try {
                ctx = (AppContext) field("ctx");
                panel.QaContext.dev().add("qa-admin", "qa@example.invalid", "+5511999991234", "shell-qa-pass-1", panel.security.Role.ADMIN, false);
                invoke("showEntry", String.class, null);
                User admin = ctx.auth.login("qa-admin", "shell-qa-pass-1".toCharArray());
                invoke("afterLogin", User.class, admin);
                var flow = ctx.adminAccess.startTwoFactor();
                flow.sendEmailCode();
                flow.verifyEmail(panel.QaContext.dev().lastCode());
                flow.sendSmsCode();
                flow.verifySms(panel.QaContext.dev().lastCode());
                flow.finish(false);
                invoke("show", String.class, "t-desk");
                later(this::next);
            } catch (Throwable e) {
                fail(e);
            }
        }

        private panel.shell.ShellRouter router() throws Exception {
            return (panel.shell.ShellRouter) field("router");
        }

        private panel.ui.View view(String id) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, View> views = (Map<String, View>) field("views");
            return views.get(id);
        }

        private void refreshViews() throws Exception {
            view("overview").onSnapshot(null);
            view("capture").onSnapshot(null);
        }

        private void apply(String name) throws Exception {
            Snapshot s = switch (name) {
                case "empty" -> ResearchFixtures.empty();
                case "ready" -> ResearchFixtures.trainReady(34, false);
                case "running" -> ResearchFixtures.trainReady(34, true);
                case "failed" -> {
                    Snapshot f = ResearchFixtures.trainReady(34, true);
                    f.sessions.set(7, ResearchFixtures.session("s007", panel.model.StageState.FAILED));
                    f.warnings.add("Backend refresh unavailable (IOException)");
                    yield f;
                }
                case "labels" -> {
                    Snapshot f = ResearchFixtures.trainReady(34, false);
                    f.labelState = panel.model.StageState.PARTIAL;
                    f.labelDone = 20;
                    yield f;
                }
                default -> throw new IllegalArgumentException(name);
            };
            ctx.research.snapshot.set(s);
            refreshViews();
            report.add("FIXTURE " + name + " (QA only, in-memory, no job, no file)");
        }

        private String lastCapture;

        private void capture(String name) throws Exception {
            lastCapture = name;
            CaptureSnapshot c = switch (name) {
                case "running" -> ResearchFixtures.capture(CaptureSnapshot.State.RUNNING, java.time.Instant.now());
                case "stale" -> ResearchFixtures.capture(CaptureSnapshot.State.STALE, java.time.Instant.now());
                case "stopped" -> ResearchFixtures.capture(CaptureSnapshot.State.STOPPED, java.time.Instant.now());
                case "unknown" -> ResearchFixtures.capture(CaptureSnapshot.State.UNKNOWN, java.time.Instant.now());
                default -> throw new IllegalArgumentException(name);
            };
            ctx.captureMonitor.stop(); // o monitor real (somente leitura) não sobrescreve o fixture
            Field f = CaptureScreen.class.getDeclaredField("panel");
            f.setAccessible(true);
            CapturePanel panel = (CapturePanel) f.get(view("capture"));
            panel.show(c);
            report.add("CAPTURE FIXTURE " + name + " (QA only, in-memory)");
        }

        private void next() throws Exception {
            if (index >= steps.size()) {
                Platform.exit();
                return;
            }
            String step = steps.get(index++);
            if (step.equals("real")) {
                refreshViews(); // o estado REAL do backend vazio deste home temporário
                report.add("REAL state (empty backend)");
            } else if (step.startsWith("route:")) {
                invoke("show", String.class, step.substring(6));
            } else if (step.startsWith("fx:")) {
                apply(step.substring(3));
            } else if (step.startsWith("cap:")) {
                capture(step.substring(4));
            } else if (step.startsWith("wait:")) {
                PauseTransition p = new PauseTransition(Duration.millis(Integer.parseInt(step.substring(5))));
                p.setOnFinished(e -> {
                    try {
                        next();
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
                p.play();
                return;
            } else if (step.startsWith("shot:")) {
                String[] parts = step.substring(5).split("@");
                if (parts.length > 1) {
                    String[] wh = parts[1].split("x");
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
                }
                shot(parts[0]);
                later(this::next);
                return;
            }
            later(this::next);
        }

        /** A tela do Mac não comporta 1600/1920: a raiz real vai para uma cena fora da tela do tamanho exato. */
        private void shot(String name) throws Exception {
            var window = stage.getScene();
            var root = window.getRoot();
            window.setRoot(new javafx.scene.layout.Pane());
            javafx.scene.Scene off = new javafx.scene.Scene(root, width, height);
            off.getStylesheets().setAll(window.getStylesheets());
            root.applyCss();
            root.layout();
            refreshViews();
            if (lastCapture != null) {
                capture(lastCapture); // sair da cena limpa o card (como a tela antiga): o fixture volta antes da captura
            }
            root.applyCss();
            root.layout();
            var image = off.snapshot(null);
            off.setRoot(new javafx.scene.layout.Pane());
            window.setRoot(root);
            Path file = output.resolve(name + ".png");
            javax.imageio.ImageIO.write(panel.ControlGalleryTestAccess.toAwt(image), "png", file.toFile());
            report.add("SHOT " + name + " scene=" + (int) image.getWidth() + "x" + (int) image.getHeight() + " route=" + router().route());
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
