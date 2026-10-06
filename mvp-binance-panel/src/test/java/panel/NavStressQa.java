package panel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.shell.ShellRouter;
import panel.ui.View;
import panel.user.User;

/**
 * Stress de navegação no app REAL (manual; fora do surefire). Cenários: byx-idle (entra em BYX e fica parado com
 * updates de dados, N vezes), abc (A>B>C rápido, N vezes) e cycle (Trading>BYX>idle>Trading>BYX>idle, N ciclos).
 * Toda mudança de rota é registrada com {@link RouteTrace}. Uso: java ... panel.NavStressQa saida cenario N [segundos]
 */
public final class NavStressQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;
    static String scenario;
    static int count;
    static int seconds;
    static Path output;

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        scenario = args[1];
        count = Integer.parseInt(args[2]);
        seconds = args.length > 3 ? Integer.parseInt(args[3]) : 60;
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-stress-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        lines.add("RESULT scenario=" + scenario + " n=" + count + " failures=" + failures);
        Files.write(output.resolve("stress-" + scenario + ".txt"), lines);
        System.out.println(lines.get(lines.size() - 1));
        System.exit(failures > 0 ? 1 : 0);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            lines.add("FAIL " + what);
        }
    }

    public static final class App extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            return panel.QaContext.create();
        }

        private AppContext ctx;
        private ShellRouter router;
        private Map<String, View> views;
        private final List<String> routeLog = new ArrayList<>();
        private final List<String> trace = new ArrayList<>();
        private int iteration;

        @Override
        public void start(Stage stage) {
            super.start(stage);
            try {
                ctx = (AppContext) field("ctx");
                router = (ShellRouter) field("router");
                @SuppressWarnings("unchecked")
                Map<String, View> v = (Map<String, View>) field("views");
                views = v;
                panel.QaContext.dev().add("qa-admin", "qa@example.invalid", "+5511999991234", "shell-qa-pass-1", panel.security.Role.ADMIN, false);
                invoke("showEntry", String.class, null);
                RouteTrace.attach(router, routeLog, trace);
                after(600, () -> {
                    User admin = ctx.auth.login("qa-admin", "shell-qa-pass-1".toCharArray());
                    invoke("afterLogin", User.class, admin);
                    after(1500, this::iterate);
                });
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void iterate() throws Exception {
            if (iteration >= count) {
                Platform.exit();
                return;
            }
            int n = ++iteration;
            switch (scenario) {
                case "byx-idle" -> {
                    show("t-desk");
                    after(800, () -> {
                        show("t-byx");
                        idle("t-byx", n, this::iterateLater);
                    });
                }
                case "abc" -> {
                    show("t-desk");
                    routeLog.clear();
                    trace.clear();
                    show("overview");
                    show("t-byx");
                    after(1200, () -> {
                        check("t-byx".equals(router.route()) && !routeLog.contains("overview"), "abc#" + n + " route=" + router.route() + " log=" + routeLog + dump());
                        iterate();
                    });
                }
                case "cycle" -> {
                    show("t-desk");
                    idle("t-desk", n, () -> {
                        try {
                            show("t-byx");
                        } catch (Exception e) {
                            fail(e);
                        }
                        idle("t-byx", n, this::iterateLater);
                    });
                }
                default -> throw new IllegalArgumentException(scenario);
            }
        }

        private void iterateLater() {
            try {
                iterate();
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void idle(String ws, int n, Runnable done) {
            after(1500, () -> {
                routeLog.clear();
                trace.clear();
                int[] ticks = {0};
                Timeline data = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                    ctx.research.snapshot.set(ctx.research.snapshot.get().copy());
                    ticks[0]++;
                }));
                data.setCycleCount(seconds);
                data.setOnFinished(e -> {
                    check(ws.equals(router.route()) && routeLog.isEmpty(), scenario + "#" + n + " " + ws + " idle " + seconds + "s ticks=" + ticks[0]
                            + " route=" + router.route() + " log=" + routeLog + dump());
                    done.run();
                });
                data.play();
            });
        }

        private String dump() {
            return trace.isEmpty() ? "" : "\n  " + String.join("\n  ", trace);
        }

        private void show(String id) throws Exception {
            invoke("show", String.class, id);
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

        private void fail(Throwable t) {
            failures++;
            lines.add("FAIL exception " + t);
            Platform.exit();
        }
    }

    interface Step {
        void run() throws Exception;
    }
}
