package panel.ui;

import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

public final class LedgerMark {
    private LedgerMark() { }

    public static VBox create() {
        return create(28);
    }

    public static VBox create(double size) {
        double unit = size / 24;
        VBox mark = new VBox(2 * unit);
        mark.getStyleClass().add("ledger-mark");
        mark.setPadding(new javafx.geometry.Insets(3 * unit, 2 * unit, 2 * unit, 2 * unit));
        for (int i = 0; i < 3; i++) {
            Rectangle layer = new Rectangle((20 - i * 6) * unit, 5 * unit);
            layer.setArcWidth(5 * unit);
            layer.setArcHeight(5 * unit);
            layer.getStyleClass().add("ledger-layer");
            mark.getChildren().add(layer);
        }
        mark.setMinSize(size, size);
        mark.setPrefSize(size, size);
        mark.setMaxSize(size, size);
        return mark;
    }
}
