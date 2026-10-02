package panel.ui.toast;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.motion.icon.AnimationRepository;

/** Toasts centralizados (canto inferior direito). Não bloqueiam a UI e têm botão fechar. */
public class ToastHost extends VBox {
    private final MotionService motion;
    private final AnimationRepository icons;
    private final ToastQueue<HBox> queue = new ToastQueue<>(4);

    public ToastHost(MotionService motion, AnimationRepository icons) {
        super(8);
        this.motion = motion;
        this.icons = icons;
        setAlignment(Pos.BOTTOM_RIGHT);
        setPickOnBounds(false);
        setPadding(new Insets(16));
        setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
    }

    public void show(ToastType type, String message) {
        var icon = icons.icon(type.icon, 18, type.tone);
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(320);
        text.getStyleClass().add("toast-text");
        Button close = new Button("✕");
        close.getStyleClass().add("toast-close");
        close.setAccessibleText("Dismiss notification");
        HBox toast = new HBox(10, icon.node(), text, close);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().addAll("toast", "toast-" + type.tone);
        toast.setAccessibleText(type + ": " + message);
        close.setOnAction(e -> dismiss(toast));
        getChildren().add(toast);
        queue.add(toast, this::dismiss);
        motion.fadeSlideIn(toast, 16, 0, MotionTokens.STANDARD);
        icon.play();
        PauseTransition stay = new PauseTransition(type.stay);
        stay.setOnFinished(e -> dismiss(toast));
        stay.play();
    }

    private void dismiss(HBox toast) {
        if (!getChildren().contains(toast)) {
            return;
        }
        queue.remove(toast);
        motion.fadeOut(toast, MotionTokens.FAST, () -> getChildren().remove(toast));
    }

    public int visibleCount() {
        return queue.size();
    }
}
