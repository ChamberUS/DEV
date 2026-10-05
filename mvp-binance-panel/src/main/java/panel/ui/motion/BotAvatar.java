package panel.ui.motion;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.motion.icon.IconPaths;

/** Representação abstrata do Adaptive Trader: anel + hexágono. O estado define cor e atividade. */
public class BotAvatar extends StackPane {
    public enum State {
        OFFLINE("muted"), IDLE("info"), MONITORING("warn"), ANALYZING("purple"), PAUSED("muted"), ERROR("bad");

        public final String tone;

        State(String tone) {
            this.tone = tone;
        }

        public static State parse(String text) {
            if (text == null) {
                return OFFLINE;
            }
            String u = text.toUpperCase();
            for (State s : values()) {
                if (u.contains(s.name())) {
                    return s;
                }
            }
            return OFFLINE;
        }
    }

    private final Circle ring = new Circle();
    private final SVGPath glyph = new SVGPath();
    private final MotionService motion;
    private Animation pulse;
    private State state;

    public BotAvatar(MotionService motion, double size, State state) {
        this.motion = motion;
        ring.setRadius(size / 2 - 2);
        ring.getStyleClass().add("bot-ring");
        glyph.setContent(IconPaths.CATALOG.get("bot").svgPath());
        glyph.getTransforms().add(new Scale(size * 0.5 / 24, size * 0.5 / 24, 0, 0));
        glyph.getStyleClass().add("bot-glyph");
        getChildren().addAll(ring, new javafx.scene.Group(glyph));
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setState(state);
        javafx.beans.value.ChangeListener<panel.motion.MotionPreference> listener = (o, was, now) -> {
            State previous = this.state;
            this.state = null;
            setState(previous);
        };
        getProperties().put("bot.motion.preference", listener);
        motion.preference.addListener(new javafx.beans.value.WeakChangeListener<>(listener));
        // loops são removidos ao sair da cena; ao voltar, o pulso é recriado
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null && pulse != null && !motion.isLooping(pulse)) {
                State previous = this.state;
                this.state = null;
                setState(previous);
            }
        });
    }

    public void setState(State s) {
        if (s == state) {
            return;
        }
        state = s;
        ring.getStyleClass().removeIf(c -> c.startsWith("bot-tone-"));
        ring.getStyleClass().add("bot-tone-" + s.tone);
        glyph.getStyleClass().removeIf(c -> c.startsWith("bot-tone-"));
        glyph.getStyleClass().add("bot-tone-" + s.tone);
        if (pulse != null) {
            motion.removeLoop(pulse);
            pulse = null;
        }
        ring.setOpacity(1);
        ring.setScaleX(1);
        ring.setScaleY(1);
        if (s == State.MONITORING || s == State.ANALYZING) {
            Duration period = MotionTokens.LIVE;
            pulse = motion.loop(this, () -> new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(ring.opacityProperty(), 1)),
                    new KeyFrame(period.divide(2), new KeyValue(ring.opacityProperty(), .45, MotionTokens.CSS_EASE_IN_OUT)),
                    new KeyFrame(period, new KeyValue(ring.opacityProperty(), 1, MotionTokens.CSS_EASE_IN_OUT))));
        }
    }
}
