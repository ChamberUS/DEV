package panel.design;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** StatusBanner V2: ícone + título + texto, fundo 9%, cores bannerText. Não some sozinho; nunca só cor. */
public class ByxBanner extends HBox {
    public enum Kind {
        ERROR("error", "error"), WARNING("warning", "warning"), SUCCESS("success", "check"), INFO("info", "info");

        final String styleClass;
        final String icon;

        Kind(String styleClass, String icon) {
            this.styleClass = styleClass;
            this.icon = icon;
        }
    }

    private final Kind kind;
    private final Label title;
    private final Label text;

    public ByxBanner(Kind kind, String titleText, String bodyText) {
        this.kind = kind;
        getStyleClass().addAll("byx-banner", kind.styleClass);
        title = new Label(titleText);
        title.getStyleClass().add("byx-banner-title");
        text = new Label(bodyText);
        text.getStyleClass().add("byx-banner-text");
        text.setWrapText(true);
        text.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE); // texto quebrado não é truncado
        VBox copy = new VBox(2, title, text);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        getChildren().addAll(ByxIcon.of(kind.icon, 18, null), copy);
        setAccessibleText(kind.name() + ": " + titleText + ". " + bodyText);
    }

    public Kind kind() {
        return kind;
    }

    public String titleText() {
        return title.getText();
    }
}
