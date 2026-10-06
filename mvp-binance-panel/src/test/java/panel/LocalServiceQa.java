package panel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.localservice.LocalServiceStatus;
import panel.shell.ByxShell;
import panel.shell.ShellRouter;
import panel.ui.View;
import panel.user.User;

/**
 * QA dirigido no app REAL com o serviço REAL (processo separado, socket Unix privado, home temporário; não toca a captura nem os dados
 * do usuário). Manual: BYX_LOCAL_SERVICE_HOME=<dir> BYX_SVC_CP=<classpath do serviço> java ... panel.LocalServiceQa <saída>
 */
public final class LocalServiceQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;
    static Path output;
    static Path serviceHome;
    static Path serviceLog;

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        serviceHome = Path.of(System.getenv("BYX_LOCAL_SERVICE_HOME"));
        serviceLog = Files.createTempFile(Path.of("/tmp"), "svc-out", ".log");
        Path home = Files.createTempDirectory("byx-svc-qa-home-");
        Path dir = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(dir.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(dir.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\npollSeconds=3600\nmotion=FULL\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        lines.add("RESULT localservice failures=" + failures);
        Files.write(output.resolve("localservice-qa.txt"), lines);
        lines.forEach(System.out::println);
        System.exit(failures > 0 ? 1 : 0);
    }

    static void check(boolean ok, String what) {
        lines.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    static Process service;

    static void startService() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getenv("BYX_SVC_CP"), "byx.service.ServiceMain");
        pb.environment().put("BYX_LOCAL_SERVICE_HOME", serviceHome.toString());
        pb.environment().put("BYX_CANARY_SECRET", "CANARY-SECRET-7f3a91");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(serviceLog.toFile()));
        service = pb.start();
        long end = System.currentTimeMillis() + 8_000;
        while (!Files.exists(serviceHome.resolve("run").resolve("service.sock")) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
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
        private final List<Step> plan = new ArrayList<>();
        private int index;
        private String firstInstance;

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
                ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "final-qa-pass-1".toCharArray(), "+5511999991234");
                invoke("showEntry", String.class, null);
                RouteTrace.attach(router, routeLog, lines);
                startService();
                plan();
                after(600, this::run);
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
                if (service != null) {
                    service.destroyForcibly();
                }
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
            if (service != null) {
                service.destroyForcibly();
            }
            Platform.exit();
        }

        private ByxShell shell() throws Exception {
            return (ByxShell) field("shell");
        }

        private static String visibleTexts(Node n) {
            StringBuilder b = new StringBuilder();
            collect(n, b);
            return b.toString();
        }

        private static void collect(Node n, StringBuilder b) {
            if (!n.isVisible()) {
                return;
            }
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

        private void plan() {
            Object[] shellRef = new Object[1];
            plan.add(() -> {
                check(service.isAlive() && Files.exists(serviceHome.resolve("run").resolve("service.sock")), "the real service process is running");
                User u = ctx.auth.login("qa-admin", "final-qa-pass-1".toCharArray());
                invoke("afterLogin", User.class, u);
                next(2500);
            });
            plan.add(() -> {
                LocalServiceStatus s = ctx.localService.snapshot();
                check(s.state() == LocalServiceStatus.State.CONNECTED, "panel client paired with the real service: " + s.summary());
                firstInstance = s.instance();
                check(!s.feature("accountData") && !s.feature("adminOperations"), "no private capability is honored");
                check(ctx.localService.running(), "monitor runs while a session exists");
                var run = serviceHome.resolve("run");
                try {
                    check("rwx------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(serviceHome))) && "rwx------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(run)))
                            && "rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(run.resolve("pairing.token"))))
                            && "rw-------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(run.resolve("service.sock"), java.nio.file.LinkOption.NOFOLLOW_LINKS)))
                            , "real runtime files: dir 0700, run 0700, token 0600, socket 0600");
                } catch (java.io.IOException e) {
                    check(false, "permissions readable: " + e);
                }
                // nenhuma porta de rede: lsof do processo do serviço, só sockets de rede (-i)
                try {
                    Process p = new ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-p", Long.toString(service.pid()), "-i").redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes());
                    p.waitFor();
                    check(!out.contains("TCP") && !out.contains("UDP") && !out.contains("IPv"), "the service has no TCP/UDP/IPv4/IPv6 socket open: [" + out.trim() + "]");
                } catch (Exception e) {
                    check(false, "lsof available: " + e);
                }
                invoke("show", String.class, "sys-status");
                next(700);
            });
            plan.add(() -> {
                String t = visibleTexts(views.get("sys-status").node());
                check(t.contains("Local service") && t.contains("Local service answers"), "System Status shows the Local service row as answering");
                shellRef[0] = shell();
                routeLog.clear();
                service.destroyForcibly(); // o serviço cai (kill -9)
                service.waitFor();
                ctx.localService.refreshNow();
                next(2500);
            });
            plan.add(() -> {
                LocalServiceStatus s = ctx.localService.snapshot();
                check(s.state() == LocalServiceStatus.State.UNAVAILABLE && s.everConnected(), "service down after having been up: UNAVAILABLE as a drop: " + s.summary());
                check("sys-status".equals(router.route()) && routeLog.isEmpty(), "the outage navigated nowhere: " + routeLog);
                check(shell() == shellRef[0], "shell not recreated by the outage");
                check(visibleTexts(views.get("sys-status").node()).contains("stopped answering"), "System Status explains the drop");
                startService(); // reinício seguro
                ctx.localService.refreshNow();
                next(3000);
            });
            plan.add(() -> {
                LocalServiceStatus s = ctx.localService.snapshot();
                check(s.state() == LocalServiceStatus.State.CONNECTED, "after a restart the panel pairs again with the NEW secret: " + s.summary());
                check(s.instance() != null && !s.instance().equals(firstInstance), "a different instance id after restart (no stale state)");
                check(routeLog.isEmpty(), "restoring changed no route: " + routeLog);
                String log;
                try {
                    log = Files.readString(serviceLog);
                } catch (java.io.IOException e) {
                    log = "";
                }
                check(!log.contains("CANARY") && !log.contains(serviceHome.toString()), "the real service output carries no canary secret and no local path");
                check(log.contains("started"), "the service logs its lifecycle");
                invoke("logout", String.class, null);
                next(600);
            });
            plan.add(() -> {
                check(!ctx.localService.running(), "logout stops the monitor (no polling without a session)");
                check(ctx.localService.snapshot().state() == LocalServiceStatus.State.UNKNOWN, "no state survives logout");
                next(100);
            });
        }
    }
}
