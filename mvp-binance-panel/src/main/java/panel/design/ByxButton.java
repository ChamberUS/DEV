package panel.design;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Botão V2: primary, secondary, danger, danger-outline, ghost. Hover sobe 1 px e press escala .98 só em
 * FULL (tokens hover/press). Loading mostra spinner, fica desabilitado e engole ações: duplo clique
 * durante o carregamento não gera segundo pedido.
 */
public class ByxButton extends Button {
    public enum Variant { PRIMARY, SECONDARY, DANGER, DANGER_OUTLINE, GHOST }

    private static final PseudoClass LOADING = PseudoClass.getPseudoClass("loading");

    private final MotionService motion;
    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading", false);
    private Node idleGraphic;
    private ByxSpinner spinner;
    private boolean disabledBeforeLoading;

    public ByxButton(String text, Variant variant, MotionService motion) {
        super(text);
        this.motion = motion;
        getStyleClass().add("byx-btn");
        switch (variant) {
            case SECONDARY -> getStyleClass().add("secondary");
            case DANGER -> getStyleClass().add("danger");
            case DANGER_OUTLINE -> getStyleClass().add("danger-outline");
            case GHOST -> getStyleClass().add("ghost");
            default -> { }
        }
        addEventFilter(ActionEvent.ACTION, e -> {
            if (isLoading()) {
                e.consume();
            }
        });
        addEventHandler(MouseEvent.MOUSE_ENTERED, e -> lift(-1, "hover"));
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            lift(0, "hover");
            press(1);
        });
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> press(0.98));
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> press(1));
        loading.addListener((o, a, on) -> applyLoading(on));
        disabledProperty().addListener((o, a, d) -> {
            if (d) {
                setTranslateY(0);
                setScaleX(1);
                setScaleY(1);
            }
        });
    }

    public ByxButton wide() {
        getStyleClass().add("wide");
        return this;
    }

    public ByxButton small() {
        getStyleClass().add("small");
        return this;
    }

    public BooleanProperty loadingProperty() {
        return loading;
    }

    public boolean isLoading() {
        return loading.get();
    }

    public void setLoading(boolean on) {
        loading.set(on);
    }

    /** Spinner atual (null fora do carregamento). */
    public ByxSpinner spinner() {
        return spinner;
    }

    private void applyLoading(boolean on) {
        pseudoClassStateChanged(LOADING, on);
        if (on) {
            idleGraphic = getGraphic();
            spinner = new ByxSpinner(motion);
            setGraphic(spinner);
            if (!disableProperty().isBound()) {
                disabledBeforeLoading = isDisable();
                setDisable(true);
            }
        } else {
            if (spinner != null) {
                spinner.dispose();
                spinner = null;
            }
            setGraphic(idleGraphic);
            idleGraphic = null;
            if (!disableProperty().isBound()) {
                setDisable(disabledBeforeLoading);
            }
        }
    }

    private void lift(double y, String token) {
        if (motion == null || isDisabled() || !motion.translateAllowed()) {
            setTranslateY(0);
            return;
        }
        animate(token, new KeyValue(translateYProperty(), y, motion.easing(token)));
    }

    private void press(double s) {
        if (motion == null || isDisabled() || !motion.scaleAllowed()) {
            setScaleX(1);
            setScaleY(1);
            return;
        }
        animate("press", new KeyValue(scaleXProperty(), s, motion.easing("press")),
                new KeyValue(scaleYProperty(), s, motion.easing("press")));
    }

    private void animate(String token, KeyValue... values) {
        Duration d = motion.duration(token);
        if (d.equals(Duration.ZERO)) {
            for (KeyValue v : values) {
                @SuppressWarnings("unchecked")
                javafx.beans.value.WritableValue<Object> t = (javafx.beans.value.WritableValue<Object>) v.getTarget();
                t.setValue(v.getEndValue());
            }
            return;
        }
        // chave própria por token: hover e press não se cancelam entre si
        Object prev = getProperties().get("byx.motion." + token);
        if (prev instanceof Timeline p) {
            p.stop();
        }
        Timeline t = new Timeline(new KeyFrame(d, values));
        getProperties().put("byx.motion." + token, t);
        t.setOnFinished(e -> getProperties().remove("byx.motion." + token, t));
        t.play();
    }
}
