package panel.systemview;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxIcon;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.v2.Kit;

/**
 * Construtores dos padrões de erro que não são modais (P3.9/P3.12): erro inline de componente, barra global, permissão. Texto
 * sempre curto, ícone + palavra (nunca só cor) e sem dado sensível. Erro de campo usa {@code ByxField.setError}; erro de região
 * usa {@code ByxRegion}; aviso usa {@code ByxBanner}.
 */
public final class ErrorPatterns {
    public static final double GLOBAL_BAR_HEIGHT = 36;

    private ErrorPatterns() {
    }

    /** Mensagem compacta dentro do componente que falhou; Retry só existe se houver o que repetir. */
    public static HBox inlineComponent(MotionService motion, String text, Runnable retry) {
        Label t = Fx.label(text, "byx-desk-secondary");
        t.setWrapText(true);
        HBox h = new HBox(10, ByxIcon.of("error", 16, "neg"), t, Fx.spacer());
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("byx-inline-error");
        h.setAccessibleText("Error: " + text);
        if (retry != null) {
            ByxButton b = new ByxButton("Retry", ByxButton.Variant.SECONDARY, motion);
            b.small();
            b.setOnAction(e -> retry.run());
            h.getChildren().add(b);
        }
        return h;
    }

    /** Faixa global fina (36) sob a barra superior; não dispensável enquanto a condição dura. */
    public static HBox globalBar(String text) {
        Label t = Fx.label(text, "byx-global-bar-text");
        HBox bar = new HBox(10, ByxIcon.of("warning", 16, "wrn"), t);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("byx-global-bar");
        bar.setMinHeight(GLOBAL_BAR_HEIGHT);
        bar.setPrefHeight(GLOBAL_BAR_HEIGHT);
        bar.setMaxHeight(GLOBAL_BAR_HEIGHT);
        bar.setAccessibleText("Warning: " + text);
        return bar;
    }

    /** PERMISSION REQUIRED: papel ausente (nunca concede); a ação só existe se houver um fluxo real. */
    public static VBox permissionRequired(String title, String text, String reason) {
        VBox v = new VBox(10, ByxIcon.of("shield", 28, null), Fx.label(title, "byx-section-title"), Kit.muted(text),
                ByxBadge.availability(ByxBadge.Availability.PERMISSION_REQUIRED), Kit.dim(reason));
        v.setAlignment(Pos.CENTER);
        v.setMaxWidth(520);
        v.getStyleClass().add("byx-panel");
        v.setId("permission-required");
        v.setAccessibleText("Permission required: " + title);
        return v;
    }

    static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static Node unused() {
        return null;
    }
}
