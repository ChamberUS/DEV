package panel.mascot;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Balão contextual PEQUENO ao lado do mascote: não é diálogo nem modal, não captura foco nem teclado, some sozinho (7 s), some ao clicar nele, ao clicar de novo no mascote, ao perder o foco da janela, ao
 * esconder a janela ou ao sair da cena. Um balão por mascote. Texto vem do guia local; nada de rede.
 */
public final class MascotHintBubble {
    static final double WIDTH = 252;
    static final double AUTO_HIDE_MS = 7_000;

    private final MascotView anchor;
    private Popup popup;
    private PauseTransition auto;
    private Window owner;
    private ChangeListener<Boolean> ownerListener;
    private String text;

    public MascotHintBubble(MascotView anchor) {
        this.anchor = anchor;
    }

    public boolean showing() {
        return popup != null && popup.isShowing();
    }

    public String text() {
        return showing() ? text : null;
    }

    /** Clique no mascote: abre; clique de novo: fecha. */
    public void toggle(MascotHint hint) {
        if (showing()) {
            hide();
        } else {
            show(hint);
        }
    }

    public void show(MascotHint hint) {
        hide();
        if (anchor.getScene() == null || anchor.getScene().getWindow() == null || !anchor.getScene().getWindow().isShowing()) {
            return;
        }
        owner = anchor.getScene().getWindow();
        Label l = new Label(hint.text());
        l.setWrapText(true);
        l.setMaxWidth(WIDTH - 28);
        l.setTextFill(Color.web("#EEF1F8"));
        l.setStyle("-fx-font-size: 12.5px;");
        StackPane box = new StackPane(l);
        box.setPadding(new Insets(10, 14, 10, 14));
        box.setStyle("-fx-background-color: #1A2030; -fx-background-radius: 10; -fx-border-color: #2A3144; -fx-border-radius: 10; -fx-border-width: 1;"
                + "-fx-effect: dropshadow(gaussian, rgba(5,7,11,0.55), 14, 0.1, 0, 4);");
        box.setPrefWidth(WIDTH);
        box.setMaxWidth(WIDTH);
        box.setFocusTraversable(false);
        box.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        box.setAccessibleText("Mascot hint: " + hint.text());
        box.setOnMouseClicked(e -> hide());
        Popup p = new Popup();
        p.setAutoFix(true);
        p.setAutoHide(false);
        p.setHideOnEscape(false);
        p.getContent().add(box);
        double h = box.prefHeight(WIDTH);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        Rectangle2D vis = Screen.getScreensForRectangle(b.getMinX(), b.getMinY(), 1, 1).stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double x = Math.max(vis.getMinX() + 6, Math.min(b.getMaxX() - WIDTH, vis.getMaxX() - WIDTH - 6));
        double y = b.getMaxY() + 6;
        if (y + h > vis.getMaxY() - 6) {
            y = Math.max(vis.getMinY() + 6, b.getMinY() - h - 6);
        }
        popup = p;
        text = hint.text();
        p.show(owner, x, y);
        ownerListener = (o, was, now) -> hide();
        owner.focusedProperty().addListener(ownerListener);
        owner.showingProperty().addListener(ownerListener);
        auto = new PauseTransition(Duration.millis(AUTO_HIDE_MS));
        auto.setOnFinished(e -> hide());
        auto.play();
    }

    public void hide() {
        if (auto != null) {
            auto.stop();
            auto = null;
        }
        if (owner != null && ownerListener != null) {
            owner.focusedProperty().removeListener(ownerListener);
            owner.showingProperty().removeListener(ownerListener);
        }
        ownerListener = null;
        owner = null;
        if (popup != null) {
            popup.hide();
            popup = null;
        }
        text = null;
    }
}
