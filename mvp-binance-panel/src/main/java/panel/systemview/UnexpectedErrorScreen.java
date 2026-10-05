package panel.systemview;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxButton;
import panel.design.ByxIcon;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.v2.Kit;

/**
 * Fallback de erro inesperado (P3.12), por cima da área principal: "Something went wrong", código de referência, Retry (só
 * quando é seguro; senão desabilitado com o motivo), Open diagnostics e Return. Nunca mostra stack trace, exceção bruta, token
 * ou caminho: o detalhe técnico vai só para o log.
 */
public final class UnexpectedErrorScreen extends StackPane {
    private final ByxButton retry;

    public UnexpectedErrorScreen(MotionService motion, String code, String retryDisabledReason, Runnable onRetry, Runnable onDiagnostics, Runnable onReturn) {
        retry = new ByxButton("Retry", ByxButton.Variant.PRIMARY, motion);
        retry.setOnAction(e -> onRetry.run());
        if (retryDisabledReason != null) {
            retry.setDisable(true);
        }
        ByxButton diag = new ByxButton("Open diagnostics", ByxButton.Variant.SECONDARY, motion);
        diag.setOnAction(e -> onDiagnostics.run());
        ByxButton back = new ByxButton("Return", ByxButton.Variant.SECONDARY, motion);
        back.setOnAction(e -> onReturn.run());
        VBox card = new VBox(12, ByxIcon.of("error", 30, "neg"), Fx.label("Something went wrong", "byx-section-title"),
                Kit.muted("The app hit an unexpected problem. Your data was not changed. Share the reference code if you report it."),
                Fx.label("Reference " + code, "byx-mono"));
        if (retryDisabledReason != null) {
            card.getChildren().add(Kit.dim("Retry is off: " + retryDisabledReason));
        }
        card.getChildren().add(new HBox(10, retry, diag, back));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(520);
        card.getStyleClass().add("byx-panel");
        card.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        card.setAccessibleText("Something went wrong. Reference " + code);
        getChildren().add(card);
        setAlignment(Pos.CENTER);
        getStyleClass().addAll("byx-desk", "byx-screen", "byx-unexpected");
        setId("unexpected-error");
    }

    ByxButton retryButton() {
        return retry;
    }

    public Node focusTarget() {
        return retry.isDisabled() ? null : retry;
    }
}
