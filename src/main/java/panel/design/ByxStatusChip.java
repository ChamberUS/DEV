package panel.design;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Chip de estado (statusStates.chip: 28 alto, padding 10, raio 6, 12/600 caixa alta, ponto 8). Texto sempre
 * presente. Troca de estado: escala .98 → 1 só em FULL (systemChipChange); a cor muda na hora (JavaFX CSS
 * não tem transição de cor).
 */
public class ByxStatusChip extends HBox {
    private static final String[] STATE_CLASSES = {"state-operational", "state-connecting", "state-reconnecting",
            "state-degraded", "state-unavailable", "state-unavailable-expected", "state-unknown"};

    private final MotionService motion;
    private final ByxStatusDot dot;
    private final Label text = new Label();
    private StatusState state;
    private Timeline change;

    public ByxStatusChip(StatusState initial, MotionService motion) {
        this.motion = motion;
        getStyleClass().add("byx-chip");
        setAlignment(Pos.CENTER_LEFT);
        dot = new ByxStatusDot(8, motion);
        text.getStyleClass().add("byx-chip-text");
        getChildren().addAll(dot, text);
        apply(initial, false, false);
    }

    public void setState(StatusState s) {
        setState(s, false);
    }

    /** expectedUnavailable: nada deveria estar conectado (ex.: sem feed nesta build). Nunca usado após uma queda. */
    public void setState(StatusState s, boolean expectedUnavailable) {
        if (s == state && expectedUnavailable == dot.expected()) {
            return;
        }
        apply(s, expectedUnavailable, true);
    }

    public StatusState state() {
        return state;
    }

    public ByxStatusDot dot() {
        return dot;
    }

    public String text() {
        return text.getText();
    }

    private void apply(StatusState s, boolean expected, boolean animate) {
        state = s;
        getStyleClass().removeAll(STATE_CLASSES);
        getStyleClass().add(s.styleClass(expected));
        text.setText(s.name());
        dot.setState(s, expected);
        setAccessibleText("Status " + s.name());
        if (change != null) {
            change.stop();
            change = null;
        }
        setScaleX(1);
        setScaleY(1);
        if (!animate || motion == null || !motion.scaleAllowed()) {
            return;
        }
        Duration d = motion.duration("systemChipChange");
        if (d.equals(Duration.ZERO)) {
            return;
        }
        setScaleX(0.98);
        setScaleY(0.98);
        change = new Timeline(new KeyFrame(d, new KeyValue(scaleXProperty(), 1, motion.easing("systemChipChange")),
                new KeyValue(scaleYProperty(), 1, motion.easing("systemChipChange"))));
        change.setOnFinished(e -> change = null);
        change.play();
    }
}
