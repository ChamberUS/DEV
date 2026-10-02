package panel.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.motion.icon.AnimatedIcon;
import panel.motion.icon.AnimationRepository;

/** Estado vazio informativo: ícone que toca uma vez, título, explicação e chips de estado. */
public final class EmptyState {
    private static AnimationRepository icons;

    private EmptyState() {
    }

    public static void init(AnimationRepository repo) {
        icons = repo;
    }

    public static VBox compact(String icon, String title, String detail) {
        return build(icon, 24, title, detail, true);
    }

    public static VBox of(String icon, String title, String detail, Node... chips) {
        return build(icon, 34, title, detail, false, chips);
    }

    private static VBox build(String icon, double size, String title, String detail, boolean compact, Node... chips) {
        VBox box = new VBox(compact ? 4 : 8);
        if (compact) {
            box.setStyle("-fx-padding: 8;");
        }
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("empty-state");
        box.setAccessibleText(title + ". " + detail);
        if (icons != null) {
            AnimatedIcon i = icons.icon(icon, size, "muted");
            box.getChildren().add(i.node());
            box.sceneProperty().addListener((o, a, s) -> {
                if (s != null) {
                    javafx.application.Platform.runLater(i::play);
                }
            });
        }
        Label t = Ui.label(title, "empty-title");
        Label d = Ui.label(detail, "muted");
        d.setWrapText(true);
        d.setMaxWidth(380);
        d.setAlignment(Pos.CENTER);
        d.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        box.getChildren().addAll(t, d);
        if (chips.length > 0) {
            HBox row = new HBox(8, chips);
            row.setAlignment(Pos.CENTER);
            box.getChildren().add(row);
        }
        return box;
    }
}
