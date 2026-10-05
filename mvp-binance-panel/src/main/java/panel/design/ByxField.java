package panel.design;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Campo V2: rótulo 12 caixa alta, input 46 px, erro com ícone + texto (nunca só cor), desabilitado 55%.
 * Senha: Show/Hide troca o mascaramento; o texto visível usa a mesma propriedade e nunca é registrado.
 */
public class ByxField extends VBox {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final Label label;
    private final TextField input;
    private final TextField plain;
    private final Button reveal;
    private final HBox errorRow = new HBox();
    private final Label errorText = new Label();

    private ByxField(String labelText, boolean secret) {
        getStyleClass().add("byx-field");
        label = ByxFonts.upper(new Label(labelText));
        label.getStyleClass().add("byx-field-label");
        input = secret ? new PasswordField() : new TextField();
        input.getStyleClass().add("byx-input");
        label.setLabelFor(input);
        input.setAccessibleText(labelText);
        errorRow.getStyleClass().add("byx-field-error");
        errorRow.setAlignment(Pos.CENTER_LEFT);
        errorRow.getChildren().addAll(ByxIcon.of("error", 14, null), errorText);
        errorRow.setVisible(false);
        errorRow.setManaged(false);
        if (secret) {
            plain = new TextField();
            plain.getStyleClass().addAll("byx-input", "with-reveal");
            plain.textProperty().bindBidirectional(input.textProperty());
            plain.setAccessibleText(labelText);
            plain.setVisible(false);
            plain.setManaged(false);
            input.getStyleClass().add("with-reveal");
            reveal = new Button("Show");
            reveal.getStyleClass().add("byx-reveal");
            reveal.setOnAction(e -> setRevealed(!isRevealed()));
            StackPane stack = new StackPane(input, plain, reveal);
            StackPane.setAlignment(reveal, Pos.CENTER_RIGHT);
            StackPane.setMargin(reveal, new javafx.geometry.Insets(0, 8, 0, 0));
            getChildren().addAll(label, stack, errorRow);
        } else {
            plain = null;
            reveal = null;
            getChildren().addAll(label, input, errorRow);
        }
    }

    public static ByxField text(String label) {
        return new ByxField(label, false);
    }

    public static ByxField password(String label) {
        return new ByxField(label, true);
    }

    public TextField input() {
        return input;
    }

    public String labelText() {
        return label.getText();
    }

    public boolean isRevealed() {
        return plain != null && plain.isVisible();
    }

    public void setRevealed(boolean on) {
        if (plain == null) {
            return;
        }
        boolean hadFocus = input.isFocused() || plain.isFocused();
        plain.setVisible(on);
        plain.setManaged(on);
        input.setVisible(!on);
        input.setManaged(!on);
        reveal.setText(on ? "Hide" : "Show");
        if (hadFocus) {
            (on ? plain : input).requestFocus();
        }
    }

    /** Mensagem vem do backend ou da política; null limpa. */
    public void setError(String message) {
        boolean on = message != null && !message.isBlank();
        errorText.setText(on ? message : "");
        errorRow.setVisible(on);
        errorRow.setManaged(on);
        input.pseudoClassStateChanged(ERROR, on);
        if (plain != null) {
            plain.pseudoClassStateChanged(ERROR, on);
        }
        input.setAccessibleHelp(on ? message : null);
        input.notifyAccessibleAttributeChanged(AccessibleAttribute.HELP);
    }

    public boolean hasError() {
        return errorRow.isVisible();
    }

    public String errorMessage() {
        return errorText.getText();
    }
}
