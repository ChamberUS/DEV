package panel.mascot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** QA MANUAL: renderiza todos os estados sobre o fundo escuro real do tema (bg0 #0B0E16 / painel bg1 #121723) e grava PNGs. Uso: java … panel.mascot.MascotSnapshotQa <saída.png> [tempo_ms] */
public final class MascotSnapshotQa {
    public static void main(String[] a) {
        Application.launch(App.class, a);
    }

    public static final class App extends Application {
        @Override
        public void start(Stage stage) {
            List<String> args = getParameters().getRaw();
            File out = new File(args.get(0));
            int when = args.size() > 1 ? Integer.parseInt(args.get(1)) : 1500;
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.FULL);
            List<MascotView> views = new ArrayList<>();
            HBox row = new HBox(16);
            row.setAlignment(Pos.TOP_CENTER);
            for (MascotState s : MascotState.values()) {
                MascotView v = new MascotView(motion, 128);
                views.add(v);
                if (s.loops()) {
                    v.setState(s);
                } else {
                    v.setStaticState(MascotState.IDLE);
                    v.play(s);
                }
                Label l = new Label(s.name());
                l.setTextFill(Color.web("#AAB3C7"));
                VBox box = new VBox(6, v, l);
                box.setAlignment(Pos.CENTER);
                row.getChildren().add(box);
            }
            FlowPane sizes = new FlowPane(16, 8);
            sizes.setAlignment(Pos.CENTER);
            for (int px : new int[] {64, 96, 128, 160, 192}) {
                MascotView v = new MascotView(motion, px);
                v.setStaticState(MascotState.PROCESSING);
                views.add(v);
                sizes.getChildren().add(v);
            }
            VBox root = new VBox(18, row, sizes);
            root.setPadding(new Insets(20));
            root.setStyle("-fx-background-color: #121723;");
            stage.setScene(new Scene(new StackPane(root), 1060, 520, Color.web("#0B0E16")));
            stage.show();
            PauseTransition p = new PauseTransition(Duration.millis(when));
            p.setOnFinished(e -> {
                try {
                    javax.imageio.ImageIO.write(panel.ControlGalleryTestAccess.toAwt(stage.getScene().snapshot(null)), "png", out);
                    System.out.println("SNAPSHOT " + out + " views=" + views.size());
                } catch (Exception ex) {
                    System.out.println("SNAPSHOT FAILED " + ex);
                }
                views.forEach(MascotView::dispose);
                Platform.exit();
                System.exit(0);
            });
            p.play();
        }
    }
}
