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
import panel.shell.ByxShell;
import panel.shell.ShellRouter;
import panel.ui.View;
import panel.user.User;

/**
 * Regressão de navegação no shell V2 REAL (manual; não roda no surefire). Executa os critérios da fundação
 * contra o PanelApp: idle de 60 s por workspace com dados chegando, A→B→C e A→B→A rápidos (inclusive com um
 * pedido Research pendente cujo callback chega depois), 20 idas e voltas, resize, refresh de status e
 * camadas. Uso: java ... panel.ShellNavigationQa <saída> [idleSegundos]; modo de motion por -Dbyx.qa.motion.
 */
public final class ShellNavigationQa {
    static final List<String> lines = new ArrayList<>();
    static int failures;
    static Path output;
    static int idleSeconds;

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        idleSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-nav-qa-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=" + System.getProperty("byx.qa.motion", "FULL") + "\ndensity=COMPACT\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
        String mode = System.getProperty("byx.qa.motion", "FULL");
        lines.add("RESULT mode=" + mode + " failures=" + failures);
        Files.write(output.resolve("navigation-" + mode.toLowerCase() + ".txt"), lines);
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
        private AppContext ctx;
        private ShellRouter router;
        private Map<String, View> views;
        private final List<String> routeLog = new ArrayList<>();
        private final List<Step> plan = new ArrayList<>();

        @Override
        public void start(Stage stage) {
            super.start(stage);
            try {
                ctx = (AppContext) field("ctx");
                router = (ShellRouter) field("router");
                @SuppressWarnings("unchecked")
                Map<String, View> v = (Map<String, View>) field("views");
                views = v;
                ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "shell-qa-pass-1".toCharArray(), "+5511999991234");
                invoke("showEntry", String.class, null);
                RouteTrace.attach(router, routeLog, lines);
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

        private void show(String id) throws Exception {
            invoke("show", String.class, id);
        }

        private ByxShell shell() throws Exception {
            return (ByxShell) field("shell");
        }

        private long visibleViews() {
            return views.values().stream().filter(v -> v.node().isVisible()).count();
        }

        private boolean only(String id) {
            return visibleViews() == 1 && views.get(id).node().isVisible();
        }

        interface Step {
            void run() throws Exception;
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

        private int index;

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

        private void plan() {
            // 1. sessão sem verificação de admin: Research exige verificação (pedido PENDENTE)
            plan.add(() -> {
                User admin = ctx.auth.login("qa-admin", "shell-qa-pass-1".toCharArray());
                invoke("afterLogin", User.class, admin);
                next(1500);
            });
            plan.add(() -> {
                routeLog.clear();
                show("t-desk");
                show("overview"); // B pendente: verificação de dispositivo em outra thread
                show("t-byx");    // C chega antes da verificação terminar
                next(1500);
            });
            plan.add(() -> {
                check("t-byx".equals(router.route()), "A>B(pending)>C ends in C: route=" + router.route());
                check(field("tfOverlay") == null, "late verification callback for cancelled B shows nothing");
                check(!routeLog.contains("overview"), "B never displayed: " + routeLog);
                check(only("t-byx"), "one visible view (t-byx)");
                routeLog.clear();
                show("t-desk");
                show("overview");
                show("t-desk"); // A>B>A com B pendente
                next(1500);
            });
            plan.add(() -> {
                check("t-desk".equals(router.route()), "A>B(pending)>A ends in A: route=" + router.route());
                check(field("tfOverlay") == null, "no verification overlay after returning to A");
                check(!routeLog.contains("overview"), "B never displayed: " + routeLog);
                // agora verifica o admin (OTP de desenvolvimento) para os demais cenários
                var flow = ctx.adminAccess.startTwoFactor();
                flow.sendEmailCode();
                flow.verifyEmail(ctx.devOtp.lastCode());
                flow.sendSmsCode();
                flow.verifySms(ctx.devOtp.lastCode());
                flow.finish(false);
                next(300);
            });
            // 2. A>B>C e A>B>A síncronos (admin verificado)
            plan.add(() -> {
                routeLog.clear();
                show("t-desk");
                show("overview");
                show("t-byx");
                check("t-byx".equals(router.route()) && only("t-byx"), "A>B>C ends in C with one visible view");
                check(routeLog.equals(List.of("t-desk", "overview", "t-byx")) || routeLog.equals(List.of("overview", "t-byx")),
                        "A>B>C never passes through B again: " + routeLog);
                check(shell().rail().context() == panel.shell.ShellContext.BYX && shell().switcher().selected() == panel.shell.ShellContext.BYX,
                        "rail and switcher follow C");
                routeLog.clear();
                show("t-desk");
                show("overview");
                show("t-desk");
                check("t-desk".equals(router.route()) && only("t-desk"), "A>B>A ends in A with one visible view");
                next(800);
            });
            // 3. idle por workspace com dados chegando
            for (String ws : List.of("t-desk", "overview", "t-byx")) {
                plan.add(() -> idle(ws));
            }
            // 4. 20 idas e voltas: sem loops/entradas duplicados
            plan.add(() -> {
                show("t-desk");
                next(1200);
            });
            plan.add(() -> {
                show("t-byx");
                show("t-desk");
                next(1200);
            });
            plan.add(() -> {
                int loops = ctx.motion.runningLoops();
                int entries = ctx.transitions.entries();
                for (int i = 0; i < 20; i++) {
                    show("t-byx");
                    show("t-desk");
                }
                after(1500, () -> {
                    check(ctx.motion.runningLoops() == loops, "20 round trips keep running loops at " + loops + " (now " + ctx.motion.runningLoops() + ")");
                    check(ctx.transitions.entries() == entries, "20 round trips replay no card entry (" + entries + " -> "
                            + ctx.transitions.entries() + "), P3.3");
                    check(only("t-desk"), "one visible view after 20 round trips");
                    run();
                });
            });
            // 5. resize: nenhuma entrada de página, rota igual
            plan.add(() -> {
                int entries = ctx.transitions.entries();
                String route = router.route();
                Stage st = (Stage) shell().getScene().getWindow();
                double w = st.getWidth();
                double h = st.getHeight();
                st.setWidth(1300);
                st.setHeight(780);
                after(400, () -> {
                    st.setWidth(w);
                    st.setHeight(h);
                    after(400, () -> {
                        check(route.equals(router.route()), "resize keeps the route");
                        check(ctx.transitions.entries() == entries, "resize replays no page enter");
                        st.setIconified(true); // fundo
                        after(500, () -> {
                            st.setIconified(false); // frente
                            after(700, () -> {
                                check(route.equals(router.route()), "background to foreground keeps the route");
                                check(ctx.transitions.entries() == entries, "background to foreground replays no page enter");
                                check(only(route), "one visible view after foreground");
                                run();
                            });
                        });
                    });
                });
            });
            // 6. refresh de status: dock não reconstrói por estado igual, nada navega
            plan.add(() -> {
                int entries = ctx.transitions.entries();
                String route = router.route();
                Method chrome = PanelApp.class.getDeclaredMethod("chrome", panel.model.Snapshot.class);
                chrome.setAccessible(true);
                chrome.invoke(this, ctx.research.snapshot.get());
                int rebuilds = shell().dock().rebuilds();
                for (int i = 0; i < 50; i++) {
                    ctx.research.snapshot.set(ctx.research.snapshot.get().copy());
                    chrome.invoke(this, ctx.research.snapshot.get());
                }
                check(route.equals(router.route()), "50 status refreshes keep the route");
                check(shell().dock().rebuilds() == rebuilds, "equal status never rebuilds the dock (" + rebuilds + " -> " + shell().dock().rebuilds() + ")");
                check(ctx.transitions.entries() == entries, "status refresh replays no page enter");
                next(300);
            });
            // 7. camadas: abrir/fechar nunca muda a rota; Esc fecha só o topo
            plan.add(() -> {
                String route = router.route();
                int entries = ctx.transitions.entries();
                ByxShell sh = shell();
                sh.topBar().search().fire();
                sh.topBar().avatar().fire();
                boolean menuWhilePalette = sh.overlay().topLayer() == panel.design.OverlayLayer.PALETTE;
                Method open = PanelApp.class.getDeclaredMethod("openPalette");
                open.setAccessible(true);
                open.invoke(this); // Cmd/Ctrl+K com a paleta aberta: continua aberta
                next(0);
                check(menuWhilePalette, "palette (60) stays above popovers (40)");
                check(route.equals(router.route()), "opening overlays keeps the route");
                check(ctx.transitions.entries() == entries, "overlays replay no page enter");
            });
            plan.add(() -> {
                ByxShell sh = shell();
                var host = sh.overlay();
                fireEsc(sh);
                check(!host.paletteOpen(), "Esc closes the palette");
                sh.topBar().notifications().fire();
                fireEsc(sh);
                check(host.openPopovers() == 0, "Esc closes the notification panel");
                check(router.route() != null && only(router.route()), "one visible view after overlays");
                lines.add("FINAL route=" + router.route() + " visible=" + visibleViews() + " rail=" + sh.rail().context() + "#"
                        + sh.rail().selectedIndex() + " switch=" + sh.switcher().selected() + " top=" + host.topLayer()
                        + " popovers=" + host.openPopovers());
                next(200);
            });
        }

        private void fireEsc(ByxShell sh) {
            javafx.scene.Node target = sh.getScene().getFocusOwner() != null ? sh.getScene().getFocusOwner() : sh.overlay();
            javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
        }

        /** Workspace parado por idleSeconds com um snapshot novo por segundo: rota e View não mudam. */
        private void idle(String ws) throws Exception {
            show(ws);
            after(1500, () -> {
                routeLog.clear();
                int entries = ctx.transitions.entries();
                int[] ticks = {0};
                Timeline data = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                    ctx.research.snapshot.set(ctx.research.snapshot.get().copy()); // dados chegando
                    ticks[0]++;
                }));
                data.setCycleCount(idleSeconds);
                data.setOnFinished(e -> {
                    try {
                        check(ws.equals(router.route()) && routeLog.isEmpty(), ws + " idle " + idleSeconds + " s with " + ticks[0]
                                + " data updates never navigates: " + routeLog);
                        check(only(ws), ws + " idle keeps one visible view");
                        check(ctx.transitions.entries() == entries, ws + " idle replays no page enter");
                        run();
                    } catch (Throwable t) {
                        fail(t);
                    }
                });
                data.play();
            });
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
