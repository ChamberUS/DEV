package panel.ui;

import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

public final class LedgerMark {
    private LedgerMark() { }

    public static VBox create() {
        VBox mark = new VBox(4);
        mark.getStyleClass().add("ledger-mark");
        for (int i = 0; i < 3; i++) {
            SVGPath layer = new SVGPath();
            layer.setContent("M0 0 H24 L20 4 H0 Z");
            layer.getStyleClass().add("ledger-layer");
            layer.setOpacity(1 - i * .22);
            mark.getChildren().add(layer);
        }
        mark.setMaxSize(24, 24);
        return mark;
    }
}
