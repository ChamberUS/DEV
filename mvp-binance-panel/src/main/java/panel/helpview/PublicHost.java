package panel.helpview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;

/**
 * Modo público (antes do login): só About, FAQ, Help, Terms e Privacy. Sem rail, seletor, busca, notificações, menu do
 * usuário nem dock; um botão Sign in volta à entrada. A lista de rotas públicas vive no roteador ({@code ShellRoutes.PUBLIC}),
 * não aqui: este host só exibe o que o roteador permitiu.
 */
public final class PublicHost extends BorderPane {
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Button> tabs = new LinkedHashMap<>();
    private final StackPane stack = new StackPane();
    private View active;

    public PublicHost(MotionService motion, Map<String, View> pages, Consumer<String> request, Runnable signIn) {
        getStyleClass().addAll("byx-app", "byx-desk");
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 20, 10, 20));
        bar.getStyleClass().add("byx-topbar");
        bar.getChildren().add(Fx.label("BYX-MVP", "byx-section-title-sm"));
        bar.getChildren().add(ByxBadge.of("PUBLIC", ByxBadge.Tone.NEUTRAL));
        for (String[] t : new String[][] {{"h-about", "About"}, {"h-faq", "FAQ"}, {"h-help", "Help"}, {"h-terms", "Terms"}, {"h-privacy", "Privacy"}}) {
            Button b = new Button(t[1]);
            b.getStyleClass().add("byx-toc-link");
            b.setOnAction(e -> request.accept(t[0]));
            tabs.put(t[0], b);
            bar.getChildren().add(b);
        }
        ByxButton in = new ByxButton("Sign in", ByxButton.Variant.PRIMARY, motion);
        in.setOnAction(e -> signIn.run());
        bar.getChildren().addAll(Fx.spacer(), in);
        pages.forEach((id, v) -> {
            views.put(id, v);
            v.node().setVisible(false);
            stack.getChildren().add(v.node());
        });
        setTop(bar);
        setCenter(stack);
    }

    public String showing() {
        return active == null ? null : views.entrySet().stream().filter(e -> e.getValue() == active).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Exibe uma página pública (o roteador já decidiu). Uma visível por vez. */
    public void show(String id) {
        View next = views.get(id);
        if (next == null) {
            return;
        }
        if (active != null && active != next) {
            active.node().setVisible(false);
            active.onHide();
        }
        active = next;
        next.node().setVisible(true);
        next.onShow();
        tabs.forEach((k, b) -> Fx.cls(b, "current", k.equals(id)));
    }

    public void dispose() {
        views.values().forEach(View::onHide);
    }

    public Node tab(String id) {
        return tabs.get(id);
    }
}
