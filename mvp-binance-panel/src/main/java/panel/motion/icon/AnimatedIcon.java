package panel.motion.icon;

import javafx.scene.Node;

/** A UI só conhece esta interface; o renderer (Lottie, SVG animado ou estático) é detalhe do repositório. */
public interface AnimatedIcon {
    Node node();

    /** Toca uma vez. */
    void play();

    /** Loop até stop(). */
    void loop();

    void stop();

    /** Frame estático (Motion OFF ou ícones desabilitados). */
    void showStatic();

    String renderer();
}
