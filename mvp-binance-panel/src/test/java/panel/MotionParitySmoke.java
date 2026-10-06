package panel;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.auth.TwoFactorResult;
import panel.user.User;

/** Real native pulses, isolated auth/backend, frame traces; no arbitrary sleeps. */
public final class MotionParitySmoke {
    private static Path output;
    private static Throwable failure;
    private static final List<String> trace = new ArrayList<>();
    private static final List<java.util.concurrent.CompletableFuture<Void>> images = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("byx.legacy.qa")) { // LEGACY QA: mede o cromo anterior ao V2 e acessa o PanelApp por reflexão; não vale como evidência do V2
            System.err.println("LEGACY QA (pre-V2 chrome): not valid V2 evidence. Use ShellQaSmoke/ShellNavigationQa/AuthFlowQa and the step QAs. Pass -Dbyx.legacy.qa=true to run anyway.");
            return;
        }
        output = args.length == 0 ? Files.createTempDirectory("byx-motion-qa-") : Path.of(args[0]);
        Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-motion-home-");
        Path config = Files.createDirectory(home.resolve(".mvp-binance-panel"));
        Files.writeString(config.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(config.resolve("settings.properties"), "dataSource=REAL\nprojectPath="
                + home.resolve("empty-project") + "\ncliPath=/usr/bin/false\nmotion=FULL\n");
        System.setProperty("user.home", home.toString());
        trace.add("case,elapsed_ms,opacity,translate_y,active_channels,loops");
        Application.launch(SmokeApp.class, args);
        for (var image : images) image.join();
        Files.write(output.resolve("frames.csv"), trace);
        if (failure != null) throw new AssertionError("Native motion smoke failed", failure);
        System.out.println("MOTION_PARITY_SMOKE_OK " + output + " frames=" + (trace.size() - 1));
    }

    public static final class SmokeApp extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            return panel.QaContext.create();
        }

        private Stage stage;
        private AppContext ctx;
        private User admin;
        private int size;
        private final int[][] sizes = {{1440,900},{1600,1000},{1920,1080}};
        @Override public void start(Stage stage) {
            super.start(stage); this.stage = stage;
            try {
                Field field = PanelApp.class.getDeclaredField("ctx"); field.setAccessible(true); ctx = (AppContext)field.get(this);
                panel.QaContext.dev().add("motion-admin", "motion@example.invalid", "+5511999991234", "motion-smoke-pass-1", panel.security.Role.ADMIN, false);
                admin = ctx.auth.login("motion-admin", "motion-smoke-pass-1".toCharArray());
                nextSize();
            } catch (Throwable error) { fail(error); }
        }
        private void nextSize() throws Exception {
            if (size == sizes.length) { stage.close(); stop(); Platform.exit(); return; }
            stage.setWidth(sizes[size][0] + stage.getWidth() - stage.getScene().getWidth());
            stage.setHeight(sizes[size][1] + stage.getHeight() - stage.getScene().getHeight());
            invoke("showEntry", String.class, null);
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            Node bar = stage.getScene().getRoot().lookup(".ledger-bar");
            observe("login", bar, "entry", false, this::desk);
        }
        private void desk() throws Exception {
            invoke("afterLogin", User.class, admin); ctx.research.close();
            ctx.research.snapshot.set(new panel.adapter.FileResearchBackend(ctx.cli).load(ctx.settings));
            if (!ctx.adminAccess.hasValidAdminSession()) {
                var flow = ctx.adminAccess.startTwoFactor(); flow.sendEmailCode();
                check(flow.verifyEmail(panel.QaContext.dev().lastCode()) == TwoFactorResult.OK, "Email gate");
                flow.sendSmsCode(); check(flow.verifySms(panel.QaContext.dev().lastCode()) == TwoFactorResult.OK, "SMS gate"); flow.finish(false);
            }
            invoke("show", String.class, "t-markets");
            invoke("show", String.class, "t-desk");
            Node card = stage.getScene().getRoot().lookup("#desk-header");
            observe("desk", card, "entry", false, this::hover);
        }
        private void hover() throws Exception {
            invoke("show", String.class, "t-settings");
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            new AnimationTimer() {
                @Override public void handle(long now) {
                    stop(); Platform.runLater(() -> {
                        try { driveHover(); } catch (Throwable error) { fail(error); }
                    });
                }
            }.start();
        }
        private void driveHover() throws Exception {
            Button button = stage.getScene().getRoot().lookupAll(".btn").stream()
                    .filter(n -> n instanceof Button b && !b.isDisabled() && visible(b)
                            && !b.getStyleClass().contains("command-search") && !b.getStyleClass().contains("ws-tab"))
                    .map(n -> (Button)n).findFirst().orElseThrow();
            setHover(button, false); ctx.motion.reference.settleTree(button);
            setHover(button, true);
            observe("hover", button, "hover", true, () -> {
                setHover(button, false);
                observe("exit", button, "hover", true, this::rapidNavigation);
            });
        }
        private void setHover(Node node, boolean hovered) throws Exception {
            var method = Node.class.getDeclaredMethod("setHover", boolean.class);
            method.setAccessible(true); method.invoke(node, hovered);
        }
        private boolean visible(Node node) {
            for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false;
            var bounds = node.localToScene(node.getBoundsInLocal());
            return bounds.getWidth() > 0 && bounds.getHeight() > 0 && bounds.getMinY() >= 0
                    && bounds.getMaxY() < stage.getScene().getHeight();
        }
        private void rapidNavigation() throws Exception {
            for (int i = 0; i < 10; i++) {
                invoke("show", String.class, "overview"); invoke("show", String.class, "t-byx"); invoke("show", String.class, "t-desk");
                invoke("openPalette", null, null);
                Field field = PanelApp.class.getDeclaredField("palette"); field.setAccessible(true);
                ((panel.ui.CommandPalette)field.get(this)).close();
            }
            invoke("show", String.class, "t-treasury");
            Node card = stage.getScene().getRoot().lookupAll(".card").stream().filter(this::visible).findFirst().orElseThrow();
            observe("treasury", card, "entry", false, () -> {
                check(ctx.adminAccess.hasValidAdminSession(), "Motion changed admin session");
                ctx.motion.preference.set(panel.motion.MotionPreference.REDUCED);
                invoke("show", String.class, "overview");
                check(ctx.motion.loopCount() == 0, "Reduced loops retained");
                check(stage.getScene().getRoot().lookupAll(".card").stream().filter(this::visible)
                        .allMatch(n -> n.getOpacity() == 1 && n.getTranslateY() == 0), "Reduced entry not settled");
                ctx.motion.preference.set(panel.motion.MotionPreference.FULL);
                size++; nextSize();
            });
        }
        private interface Step { void run() throws Exception; }
        private void observe(String name, Node node, String channel, boolean awaitStart, Step next) {
            String label = name + "-" + sizes[size][0] + "x" + sizes[size][1];
            new AnimationTimer() {
                long start; boolean seen, intermediate; int frame;
                @Override public void handle(long now) {
                    try {
                        if (start == 0) start = now;
                        double elapsed = (now-start)/1_000_000.0;
                        Object animation = node.getProperties().get("reference." + channel);
                        seen |= animation != null;
                        intermediate |= name.equals("hover") || name.equals("exit")
                                ? node.getTranslateY() < 0 && node.getTranslateY() > -1
                                : node.getOpacity() > 0 && node.getOpacity() < (name.equals("login") ? .9 : 1);
                        trace.add(label + "," + elapsed + "," + node.getOpacity() + "," + node.getTranslateY() + ","
                                + node.getProperties().values().stream().filter(v -> v instanceof javafx.animation.Animation).count()
                                + "," + ctx.motion.runningLoops());
                        if (frame == 0 || intermediate && frame == 1) { shot(label + "-" + frame); frame++; }
                        if ((seen || !awaitStart) && animation == null) {
                            check(intermediate, "No native intermediate frames: " + label);
                            shot(label + "-final"); stop(); next.run();
                        } else if (elapsed > 8000) throw new AssertionError("Motion lifecycle timeout: " + label);
                    } catch (Throwable error) { stop(); fail(error); }
                }
            }.start();
        }
        private void shot(String name) {
            var image = stage.getScene().snapshot(null);
            int width = (int)image.getWidth(), height = (int)image.getHeight();
            int[] pixels = new int[width*height];
            image.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
            images.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    var bitmap = new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    bitmap.setRGB(0,0,width,height,pixels,0,width);
                    javax.imageio.ImageIO.write(bitmap,"png",output.resolve(name+".png").toFile());
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }));
        }
        private void invoke(String name, Class<?> parameter, Object arg) throws Exception {
            var method = parameter == null ? PanelApp.class.getDeclaredMethod(name) : PanelApp.class.getDeclaredMethod(name,parameter);
            method.setAccessible(true); if (parameter == null) method.invoke(this); else method.invoke(this,arg);
        }
        private void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
        private void fail(Throwable error) { failure=error; stage.close(); stop(); Platform.exit(); }
    }
}
