package panel.design;

import javafx.animation.Animation;
import javafx.animation.RotateTransition;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import panel.motion.MotionService;
import panel.motion.MotionSpec;

/**
 * Spinner de 16 px do token "loading": gira em FULL e REDUCED (feedback essencial), glifo parado em OFF.
 * Existe só enquanto o estado de carregamento existe: {@link #dispose()} para a rotação; sair da cena também.
 */
public final class ByxSpinner extends StackPane {
    private final Arc arc = new Arc(0, 0, 7, 7, 90, 270);
    private RotateTransition spin;

    public ByxSpinner(MotionService motion) {
        getStyleClass().add("byx-spinner");
        Circle track = new Circle(7);
        track.getStyleClass().add("byx-spinner-track");
        arc.setType(ArcType.OPEN);
        arc.getStyleClass().add("byx-spinner-arc");
        getChildren().addAll(track, arc);
        setMinSize(16, 16);
        setPrefSize(16, 16);
        setMaxSize(16, 16);
        setMouseTransparent(true);
        MotionSpec.Resolved r = motion == null ? null : motion.token("loading");
        if (r != null && r.runs()) {
            spin = new RotateTransition(r.duration(), arc);
            spin.setByAngle(360);
            spin.setInterpolator(MotionSpec.get().token("loading").interpolator());
            spin.setCycleCount(Animation.INDEFINITE);
            spin.play();
        }
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                dispose();
            }
        });
    }

    public boolean spinning() {
        return spin != null && spin.getStatus() == Animation.Status.RUNNING;
    }

    public void dispose() {
        if (spin != null) {
            spin.stop();
            spin = null;
        }
    }
}
