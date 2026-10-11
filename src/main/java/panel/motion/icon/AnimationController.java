package panel.motion.icon;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import panel.motion.MotionService;

/** Liga um AnimatedIcon a um gatilho. Cada gatilho só reage; nunca cria timelines próprias. */
public final class AnimationController {
    private AnimationController() {
    }

    public static void attach(AnimatedIcon icon, Node host, AnimationTrigger trigger, MotionService motion) {
        switch (trigger) {
            case HOVER -> host.setOnMouseEntered(e -> icon.play());
            case CLICK -> host.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> icon.play());
            case PLAY_ONCE, IN_VIEW -> host.sceneProperty().addListener((o, a, s) -> {
                if (s != null) {
                    javafx.application.Platform.runLater(icon::play);
                }
            });
            case STATE_CHANGE, LOOP_WHILE_ACTIVE -> { }
        }
    }

    public static void loopWhile(AnimatedIcon icon, BooleanProperty active) {
        active.addListener((o, a, on) -> {
            if (on) {
                icon.loop();
            } else {
                icon.stop();
            }
        });
        if (active.get()) {
            icon.loop();
        }
    }
}
