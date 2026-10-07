package panel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.PanelApp;
import panel.design.ByxField;

/**
 * QA MANUAL (fora do surefire): o painel com o AppContext de PRODUÇÃO (AuthorityClient real) contra um serviço de verdade em processo próprio (LoginHost) com autoridade SINTÉTICA. Dirige o login
 * pela UI (campos e botão reais) com credenciais do arquivo 0600 do home temporário e mede a responsividade da thread FX (batimento). Nunca toca o home/autoridade reais.
 * Uso: BYX_LOCAL_SERVICE_HOME=<home> java … panel.RealLoginQa <creds-file> <saída>
 */
public final class RealLoginQa {
    static final List<String> lines = new ArrayList<>();
    static Path credsFile;
    static final AtomicLong lastBeat = new AtomicLong(System.nanoTime());
    static volatile long maxGapMs;

    public static void main(String[] args) throws Exception {
        credsFile = Path.of(args[0]);
        Path home = Files.createTempDirectory("byx-real-login-qa-");
        Path dir = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(dir.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project") + "\ncliPath=/usr/bin/false\npollSeconds=3600\nmotion=FULL\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        Application.launch(App.class, args);
    }

    static void say(String s) {
        String l = String.format("%tT.%<tL %s", System.currentTimeMillis(), s);
        lines.add(l);
        System.out.println(l);
    }

    public static final class App extends PanelApp {
        private Stage stage;

        @Override
        public void start(Stage stage) {
            this.stage = stage;
            super.start(stage);
            // batimento da thread FX: o maior intervalo entre dois batimentos mede congelamentos
            Timeline beat = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                long now = System.nanoTime();
                long gap = (now - lastBeat.getAndSet(now)) / 1_000_000;
                maxGapMs = Math.max(maxGapMs, gap);
                if (gap > 700) {
                    say("FX_GAP " + gap + "ms");
                }
            }));
            beat.setCycleCount(Timeline.INDEFINITE);
            beat.play();
            Thread driver = new Thread(this::drive, "qa-driver");
            driver.setDaemon(true);
            driver.start();
        }

        private void fx(Runnable r) {
            Platform.runLater(r);
        }

        private String describe() {
            StringBuilder b = new StringBuilder();
            List<Node> all = new ArrayList<>();
            collect(stage.getScene().getRoot(), all);
            for (Node n : all) {
                if (n instanceof ByxField f && n.isVisible() && n.getScene() != null) {
                    b.append("[field ").append(f.labelText()).append(" dis=").append(f.input().isDisabled()).append("] ");
                }
                if (n instanceof Labeled lb && !(n instanceof Button) && n.isVisible() && n.getScene() != null && lb.getText() != null && lb.getText().length() > 12 && lb.getText().length() < 160) {
                    b.append("{").append(lb.getText()).append("} ");
                }
                if (n instanceof Button bt && n.isVisible() && n.getScene() != null && bt.getText() != null && !bt.getText().isBlank()) {
                    b.append("[btn ").append(bt.getText()).append(" dis=").append(bt.isDisabled()).append("] ");
                }
            }
            return b.toString();
        }

        private static void collect(Node n, List<Node> out) {
            out.add(n);
            if (n instanceof Parent p) {
                p.getChildrenUnmodifiable().forEach(c -> collect(c, out));
            }
        }

        private void drive() {
            try {
                Thread.sleep(2500);
                String[] creds = Files.readString(credsFile).split("\n");
                String[] shown = new String[1];
                fx(() -> shown[0] = describe());
                Thread.sleep(300);
                say("LOGIN_SCREEN " + shown[0]);
                fx(() -> {
                    List<Node> all = new ArrayList<>();
                    collect(stage.getScene().getRoot(), all);
                    int i = 0;
                    for (Node n : all) {
                        if (n instanceof ByxField f && n.isVisible() && n.getScene() != null) {
                            String l = f.labelText().toLowerCase();
                            if (l.contains("user") || l.contains("email") || l.contains("login")) {
                                f.input().setText(creds[0]);
                            } else if (l.contains("password") || l.contains("senha")) {
                                f.input().setText(creds[1]);
                            }
                        }
                    }
                    long t0 = System.currentTimeMillis();
                    for (Node n : all) {
                        if (n instanceof Button bt && n.isVisible() && n.getScene() != null && bt.getText() != null && bt.getText().toLowerCase().matches("(sign in|log in|login|entrar|continue)")) {
                            say("CLICK " + bt.getText());
                            bt.fire();
                            break;
                        }
                    }
                });
                long t0 = System.currentTimeMillis();
                String last = "";
                for (int s = 0; s < 40; s++) {
                    Thread.sleep(500);
                    String[] d = new String[1];
                    fx(() -> d[0] = describe());
                    Thread.sleep(150);
                    String cur = d[0] == null ? "<FX BLOCKED: no reply>" : d[0];
                    if (!cur.equals(last)) {
                        say("T+" + (System.currentTimeMillis() - t0) + "ms UI " + cur);
                        last = cur;
                    }
                }
                say("MAX_FX_GAP_MS=" + maxGapMs);
            } catch (Throwable t) {
                say("DRIVER_ERROR " + t);
            }
            System.exit(0);
        }
    }
}
