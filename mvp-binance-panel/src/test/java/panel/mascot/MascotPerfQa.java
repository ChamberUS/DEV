package panel.mascot;

import java.time.Duration;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import panel.motion.MotionService;

/**
 * QA MANUAL de performance (fora do surefire): janela real, um MascotView por cenário, 8 s cada, sem rede nem serviço. Mede CPU do processo (delta de tempo de CPU / tempo de relógio), RSS, threads da JVM
 * e latência até o primeiro frame. Uso: nice -n 10 java … panel.mascot.MascotPerfQa
 */
public final class MascotPerfQa {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    public static final class App extends Application {
        private final MotionService motion = new MotionService();
        private MascotView view;
        private StackPane root;
        private final String[] scenarios = {"baseline-poster:IDLE:128:static", "IDLE-loop:IDLE:128:loop", "THINKING-loop:THINKING:128:loop", "PROCESSING-loop:PROCESSING:128:loop", "PROCESSING-loop:PROCESSING:192:loop",
                "SYNCING-loop:SYNCING:128:loop", "TRANSITION-x3:TRANSITION:96:oneshot3", "PROCESSING-second-play:PROCESSING:128:loop"};
        private int index;
        private Duration cpu0;
        private long wall0;

        @Override
        public void start(Stage stage) {
            root = new StackPane();
            stage.setScene(new Scene(root, 360, 360));
            stage.show();
            System.out.println("env: JavaFX " + System.getProperty("javafx.runtime.version") + " java " + System.getProperty("java.version") + " pid " + ProcessHandle.current().pid());
            PauseTransition warm = new PauseTransition(javafx.util.Duration.seconds(3));
            warm.setOnFinished(e -> next());
            warm.play();
        }

        private void next() {
            if (index >= scenarios.length) {
                System.out.println("DONE");
                Platform.exit();
                System.exit(0);
                return;
            }
            String[] sc = scenarios[index++].split(":");
            if (view != null) {
                view.dispose();
                root.getChildren().clear();
            }
            int px = Integer.parseInt(sc[2]);
            view = new MascotView(motion, px);
            root.getChildren().add(view);
            MascotState st = MascotState.valueOf(sc[1]);
            long t0 = System.nanoTime();
            switch (sc[3]) {
                case "static" -> view.setStaticState(st);
                case "loop" -> view.setState(st);
                default -> {
                    view.setStaticState(MascotState.IDLE);
                    view.play(st);
                }
            }
            PauseTransition begin = new PauseTransition(javafx.util.Duration.seconds(1.5)); // deixa o decode terminar; mede o regime
            begin.setOnFinished(e -> {
                cpu0 = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
                wall0 = System.nanoTime();
                if (sc[3].equals("oneshot3")) {
                    for (int i = 1; i <= 2; i++) {
                        PauseTransition again = new PauseTransition(javafx.util.Duration.seconds(2.4 * i));
                        again.setOnFinished(ev -> view.play(st));
                        again.play();
                    }
                    view.play(st);
                }
                PauseTransition measure = new PauseTransition(javafx.util.Duration.seconds(7));
                measure.setOnFinished(ev -> {
                    double cpu = (ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos() - cpu0.toNanos()) / (double) (System.nanoTime() - wall0) * 100;
                    long rss = rssKb();
                    System.out.printf(Locale.ROOT, "%-24s size=%3d  CPU=%5.1f%% of one core  RSS=%d MB  threads=%d  firstFrame=%s  loops=%d%n", sc[0], px, cpu, rss / 1024, Thread.getAllStackTraces().size(),
                            view.firstFrameMicros() < 0 ? "n/a (poster)" : view.firstFrameMicros() / 1000.0 + " ms", motion.runningLoops());
                    next();
                });
                measure.play();
            });
            begin.play();
        }

        private static long rssKb() {
            try {
                Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).start();
                return Long.parseLong(new String(p.getInputStream().readAllBytes()).trim());
            } catch (Exception e) {
                return -1;
            }
        }
    }
}
