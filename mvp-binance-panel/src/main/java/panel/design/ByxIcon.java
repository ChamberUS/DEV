package panel.design;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import panel.motion.icon.AnimationAsset;
import panel.motion.icon.IconPaths;

/** Ícone estático do tema V2 (viewBox 24, traço 1.7) a partir do catálogo nativo {@link IconPaths}. */
public final class ByxIcon {
    private ByxIcon() {
    }

    /** tone: pos, neg, wrn, inf, tx, t3, accent, on-accent; null usa text.secondary (ou a regra do contêiner). */
    public static Node of(String name, double size, String tone) {
        AnimationAsset a = IconPaths.CATALOG.get(name);
        if (a == null) {
            throw new IllegalArgumentException("unknown icon " + name);
        }
        SVGPath p = new SVGPath();
        p.setContent(a.svgPath());
        p.getStyleClass().add("byx-icon");
        if (tone != null) {
            p.getStyleClass().add("tone-" + tone);
        }
        p.getTransforms().add(new Scale(size / 24.0, size / 24.0, 0, 0));
        Pane box = new Pane(p);
        box.getStyleClass().add("byx-icon-box");
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
