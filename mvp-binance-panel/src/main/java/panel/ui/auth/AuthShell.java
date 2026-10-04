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
        VBox brand = new VBox(6, Ui.label(panel.app.AppBranding.NAME, "auth-brand"),
                Ui.label(panel.app.AppBranding.ATTRIBUTION, "card-title"));
        VBox layers = new VBox(14);
        layers.getStyleClass().add("ledger-art");
        layers.setAlignment(Pos.CENTER_RIGHT);
        layers.setMaxWidth(Double.MAX_VALUE);
        for (int i = 0; i < 6; i++) {
            javafx.scene.layout.Region bar = new javafx.scene.layout.Region();
            bar.getStyleClass().add("ledger-bar");
            bar.maxWidthProperty().bind(layers.widthProperty().multiply(.92 - i * .145));
            bar.setOpacity(new double[] {.9, .6, .4, .28, .18, .1}[i]);
            layers.getChildren().add(bar);
        }
        VBox tagline = new VBox(Ui.label("Capital, measured.", "auth-tagline"),
                Ui.label("Research before risk.", "auth-tagline", "muted"));
        javafx.scene.layout.Region upper = new javafx.scene.layout.Region();
        javafx.scene.layout.Region lower = new javafx.scene.layout.Region();
        VBox.setVgrow(upper, Priority.ALWAYS);
        VBox.setVgrow(lower, Priority.ALWAYS);
        VBox left = new VBox(brand, upper, layers, lower, tagline);
        layers.maxWidthProperty().bind(left.widthProperty().subtract(128).multiply(.7));
        left.setAlignment(Pos.TOP_RIGHT);
        brand.setMaxWidth(Double.MAX_VALUE);
        tagline.setMaxWidth(Double.MAX_VALUE);
        left.getStyleClass().add("auth-left");
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setMinWidth(0);
        StackPane right = new StackPane(form);
        right.getStyleClass().add("auth-right");
        right.setPrefWidth(520);
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
        f.setMaxWidth(392);
        f.getStyleClass().add("auth-form");
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
