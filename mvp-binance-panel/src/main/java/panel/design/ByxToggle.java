package panel.design;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Interruptor V2 (components.toggle: 44x26, polegar 20). Clique ou Espaço alterna. O polegar desliza
 * com o token toggleSwitch só em FULL; em REDUCED/OFF (translate=false) vai direto à posição.
 */
public class ByxToggle extends StackPane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double TRAVEL = 18;

    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);
    private final Region thumb = new Region();
    private final MotionService motion;
    private Timeline slide;

    public ByxToggle(MotionService motion, String accessibleName) {
        this.motion = motion;
        getStyleClass().add("byx-toggle");
        Region track = new Region();
        track.getStyleClass().add("byx-toggle-track");
        track.setPrefSize(44, 26);
        track.setMinSize(44, 26);
        track.setMaxSize(44, 26);
        thumb.getStyleClass().add("byx-toggle-thumb");
        thumb.setPrefSize(20, 20);
        thumb.setMinSize(20, 20);
        thumb.setMaxSize(20, 20);
        StackPane.setAlignment(thumb, Pos.CENTER_LEFT);
        StackPane.setMargin(thumb, new javafx.geometry.Insets(0, 0, 0, 3));
        getChildren().addAll(track, thumb);
        setMinSize(44, 26);
        setMaxSize(44, 26);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);
        setAccessibleText(accessibleName);
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (!isDisabled()) {
                requestFocus();
                toggle();
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (!isDisabled() && e.getCode() == KeyCode.SPACE) {
                toggle();
                e.consume();
            }
        });
        selected.addListener((o, a, on) -> {
            pseudoClassStateChanged(SELECTED, on);
            moveThumb(on);
            notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
        });
    }

    public void toggle() {
        selected.set(!selected.get());
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean on) {
        selected.set(on);
    }

    public double thumbOffset() {
        return thumb.getTranslateX();
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.SELECTED) {
            return isSelected();
        }
        return super.queryAccessibleAttribute(attribute, parameters);
    }

    private void moveThumb(boolean on) {
        double target = on ? TRAVEL : 0;
        if (slide != null) {
            slide.stop();
            slide = null;
        }
        Duration d = motion == null || !motion.translateAllowed() ? Duration.ZERO : motion.duration("toggleSwitch");
        if (d.equals(Duration.ZERO)) {
            thumb.setTranslateX(target);
            return;
        }
        slide = new Timeline(new KeyFrame(d, new KeyValue(thumb.translateXProperty(), target, motion.easing("toggleSwitch"))));
        slide.setOnFinished(e -> slide = null);
        slide.play();
    }
}
