package panel.motion.icon;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/** Ícone vetorial nativo estático (viewBox 24x24, traço). Base do fallback animado. */
public class SvgIcon implements AnimatedIcon {
    public enum Kind { NONE, SPIN, POP, SHAKE, RING, DRAW }

    protected final Pane box = new Pane();
    protected final SVGPath path = new SVGPath();

    public SvgIcon(String svg, double size, String tone) {
        path.setContent(svg);
        path.getStyleClass().addAll("svg-icon", "icon-" + tone);
        path.getTransforms().add(new Scale(size / 24.0, size / 24.0, 0, 0));
        box.getChildren().add(path);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
    }

    @Override
    public Node node() {
        return box;
    }

    @Override
    public void play() {
    }

    @Override
    public void loop() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void showStatic() {
    }

    @Override
    public String renderer() {
        return "svg-static";
    }
}
