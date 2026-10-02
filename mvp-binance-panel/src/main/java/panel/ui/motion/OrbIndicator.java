package panel.ui.motion;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import panel.motion.MotionService;

/** Orb de pontos para processos longos: comunica atividade, não percentual. Uma Timeline, parada quando inativo. */
public class OrbIndicator extends StackPane {
    private static final int DOTS = 8;
    private final Circle[] dots = new Circle[DOTS];
    private final MotionService motion;
    private Animation anim;

    public OrbIndicator(MotionService motion, double radius, String tone) {
        this.motion = motion;
        Group g = new Group();
        for (int i = 0; i < DOTS; i++) {
            Circle c = new Circle(radius / 6);
            double a = 2 * Math.PI * i / DOTS;
            c.setTranslateX(Math.cos(a) * radius / 1.4);
            c.setTranslateY(Math.sin(a) * radius / 1.4);
            c.getStyleClass().addAll("orb-dot", "orb-" + tone);
            c.setOpacity(0.35);
            dots[i] = c;
            g.getChildren().add(c);
        }
        getChildren().add(g);
        setMinSize(radius * 2, radius * 2);
        setPrefSize(radius * 2, radius * 2);
        setMaxSize(radius * 2, radius * 2);
        setMouseTransparent(true);
    }

    public void setActive(boolean on) {
        if (!on) {
            if (anim != null) {
                anim.stop();
                anim = null;
            }
            for (Circle c : dots) {
                c.setOpacity(0.35);
            }
            return;
        }
        if (anim != null) {
            return;
        }
        if (!motion.full()) {
            for (Circle c : dots) {
                c.setOpacity(0.8);
            }
            return;
        }
        anim = motion.loop(this, () -> {
            Timeline t = new Timeline();
            for (int i = 0; i < DOTS; i++) {
                Circle c = dots[i];
                double peak = 1200.0 * i / DOTS;
                t.getKeyFrames().add(new KeyFrame(Duration.millis(peak), new KeyValue(c.opacityProperty(), 1, panel.motion.MotionTokens.EASE_OUT)));
                t.getKeyFrames().add(new KeyFrame(Duration.millis(Math.min(1200, peak + 500)), new KeyValue(c.opacityProperty(), 0.25, panel.motion.MotionTokens.EASE_IN)));
            }
            t.getKeyFrames().add(new KeyFrame(Duration.millis(1200)));
            return t;
        });
    }

    public boolean running() {
        return anim != null;
    }
}
