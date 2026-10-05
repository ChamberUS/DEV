package panel.systemview;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxButton;
import panel.design.ByxIcon;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Page unavailable V2 (interna, sem aparência de 404 da web): título, uma frase, "Return to previous" só se existe rota anterior
 * e "Go to default workspace". Nunca redireciona sozinha; as ações pedem rota ao roteador.
 */
public final class PageUnavailableScreen implements View {
    private final ScrollPane scroll;
    private final Label requested = Fx.label("", "byx-desk-t3");
    private final ByxButton back;
    private final Supplier<String> previous;
    private final Consumer<String> navigate;

    public PageUnavailableScreen(MotionService motion, Supplier<String> previous, Consumer<String> navigate, String defaultRoute) {
        this.previous = previous;
        this.navigate = navigate;
        back = new ByxButton("Return to previous", ByxButton.Variant.SECONDARY, motion);
        back.setOnAction(e -> {
            String p = previous.get();
            if (p != null) {
                navigate.accept(p);
            }
        });
        ByxButton home = new ByxButton("Go to default workspace", ByxButton.Variant.PRIMARY, motion);
        home.setOnAction(e -> navigate.accept(defaultRoute));
        VBox card = new VBox(12, ByxIcon.of("warning", 28, "wrn"), Fx.label("Page unavailable", "byx-section-title"),
                Kit.muted("This page does not exist in this build, or it has been removed. Nothing was changed."), requested, new HBox(10, back, home));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(520);
        card.getStyleClass().add("byx-panel");
        card.setId("page-unavailable");
        StackPane center = new StackPane(card);
        center.setPadding(new javafx.geometry.Insets(80, 28, 28, 28));
        center.getStyleClass().addAll("byx-desk", "byx-screen");
        scroll = Kit.scroll(center);
    }

    public void setRequested(String id) {
        requested.setText(id == null ? "" : "Requested: " + id);
        refresh();
    }

    private void refresh() {
        boolean has = previous.get() != null;
        back.setDisable(!has);
        Fx.shown(back, has);
    }

    boolean backVisible() {
        return back.isVisible();
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
