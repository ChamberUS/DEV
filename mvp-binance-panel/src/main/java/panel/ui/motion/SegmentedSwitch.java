package panel.ui.motion;

import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import panel.motion.MotionService;
import panel.motion.MotionTokens;

/** Native two-option selector; reference selection animates paint, not thumb position. */
public class SegmentedSwitch extends StackPane {
    public final Button left = new Button();
    public final Button right = new Button();
    private final Region thumb = new Region();
    private final MotionService motion;
    private boolean leftSelected = true;

    public SegmentedSwitch(MotionService motion) {
        this.motion = motion;
        getStyleClass().add("seg-switch");
        thumb.getStyleClass().add("seg-thumb");
        thumb.setMouseTransparent(true);
        left.getStyleClass().add("seg-btn");
        right.getStyleClass().add("seg-btn");
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(left, right);
        HBox.setHgrow(left, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(right, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(thumb, row);
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        thumb.setMaxWidth(USE_PREF_SIZE);
        widthProperty().addListener((o, a, w) -> {
            thumb.setPrefWidth(w.doubleValue() / 2 - 2);
            thumb.setTranslateX(leftSelected ? 0 : w.doubleValue() / 2);
        });
        thumb.setPrefHeight(28);
        thumb.setMaxHeight(USE_PREF_SIZE);
    }

    public void select(boolean leftSide) {
        if (leftSide == leftSelected) {
            return;
        }
        leftSelected = leftSide;
        thumb.setTranslateX(leftSide ? 0 : getWidth() / 2);
    }

    public boolean leftSelected() {
        return leftSelected;
    }

    public void addOption(javafx.scene.control.Button button) {
        button.getStyleClass().add("seg-btn");
        ((HBox) getChildren().get(1)).getChildren().add(button);
    }
}
