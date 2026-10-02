package panel.ui.auth;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.ui.Ui;

/** Layout dividido das telas de autenticação: marca + arte à esquerda, formulário compacto à direita. */
public final class AuthShell {
    private AuthShell() {
    }

    public static Node of(Node form, panel.motion.MotionService motion) {
        VBox brand = new VBox(10, Ui.label("MVP Binance", "auth-brand"), Ui.label("Quantitative Trading System", "muted-lg"),
                Ui.label("Adaptive trading.\nResearch powered.", "auth-tagline"));
        brand.setAlignment(Pos.TOP_LEFT);
        brand.setPadding(new Insets(56, 56, 0, 56));
        StackPane left = new StackPane(new MarketArt(motion), brand);
        StackPane.setAlignment(brand, Pos.TOP_LEFT);
        left.getStyleClass().add("auth-left");
        HBox.setHgrow(left, Priority.ALWAYS);
        StackPane right = new StackPane(form);
        right.getStyleClass().add("auth-right");
        right.setPrefWidth(480);
        right.setMinWidth(440);
        right.setMaxWidth(520);
        HBox root = new HBox(left, right);
        root.getStyleClass().add("auth-root");
        return root;
    }

    public static VBox form(String title, String subtitle) {
        VBox f = new VBox(14, Ui.label(title, "h1"));
        if (subtitle != null) {
            Label s = Ui.label(subtitle, "muted");
            s.setWrapText(true);
            f.getChildren().add(s);
        }
        f.setMaxWidth(340);
        f.setAlignment(Pos.CENTER_LEFT);
        return f;
    }

    public static VBox field(String label, Control control) {
        Label l = Ui.label(label, "field-label");
        l.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        control.getStyleClass().add("auth-input");
        return new VBox(5, l, control);
    }

    public static Label error() {
        Label e = Ui.label("", "auth-error");
        e.setWrapText(true);
        e.setMinHeight(0);
        e.managedProperty().bind(e.textProperty().isNotEmpty());
        e.visibleProperty().bind(e.textProperty().isNotEmpty());
        return e;
    }

    public static Label notice(String text) {
        Label n = Ui.label(text, "auth-notice");
        n.setWrapText(true);
        return n;
    }
}
