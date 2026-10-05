package panel.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import panel.design.ByxFonts;
import panel.design.ByxStatusDot;
import panel.design.StatusState;
import panel.motion.MotionService;

/**
 * Status dock V2 (38 px; grupos SYSTEM HEALTH, MODE, ENVIRONMENT; texto 13, pontos 9; nunca quebra linha).
 * Só mostra estado real recebido em {@link #setModel}. Modelo igual ao anterior não toca em nenhum nó;
 * mesma estrutura atualiza no lugar (texto, ponto); só estrutura nova reconstrói. Itens com destino pedem
 * navegação ao roteador; atualizações de estado nunca navegam.
 */
public final class StatusDock extends HBox {
    /** tone: null = text.secondary, "primary" = text.primary (ex.: Live trading OFF, nunca vermelho), "tertiary". */
    public record Item(String key, String text, StatusState dot, boolean expected, String tone, String target, String hint) {
        public static Item text(String key, String text, String tone, String target, String hint) {
            return new Item(key, text, null, false, tone, target, hint);
        }

        public static Item status(String key, String text, StatusState dot, boolean expected, String target, String hint) {
            return new Item(key, text, dot, expected, null, target, hint);
        }
    }

    public record Group(String label, List<Item> items) {
    }

    private final MotionService motion;
    private final Consumer<String> request;
    private List<Group> model = List.of();
    private final List<ItemView> views = new ArrayList<>();
    private int rebuilds;

    private final class ItemView {
        final Button node = new Button();
        final Label label = new Label();
        final ByxStatusDot dot;
        Item item;

        ItemView(Item item) {
            dot = item.dot() == null ? null : new ByxStatusDot(9, motion);
            HBox g = dot == null ? new HBox(label) : new HBox(6, dot, label);
            g.setAlignment(Pos.CENTER_LEFT);
            node.setGraphic(g);
            node.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            node.getStyleClass().add("byx-dock-item");
            label.getStyleClass().add("byx-dock-text");
            node.setOnAction(e -> {
                if (this.item.target() != null) {
                    request.accept(this.item.target());
                }
            });
            apply(item);
        }

        void apply(Item i) {
            item = i;
            label.setText(i.text());
            label.getStyleClass().removeAll("tone-primary", "tone-tertiary");
            if (i.tone() != null) {
                label.getStyleClass().add("tone-" + i.tone());
            }
            if (dot != null) {
                dot.setState(i.dot(), i.expected());
            }
            boolean link = i.target() != null;
            node.setFocusTraversable(link);
            node.setMouseTransparent(!link && i.hint() == null);
            node.getStyleClass().remove("link");
            if (link) {
                node.getStyleClass().add("link");
            }
            String a11y = i.text() + (i.dot() == null ? "" : ", " + i.dot().name().toLowerCase(java.util.Locale.ROOT));
            node.setAccessibleText(a11y);
            if (i.hint() != null) {
                Tooltip t = node.getTooltip();
                if (t == null) {
                    t = new Tooltip();
                    t.getStyleClass().add("byx-tooltip");
                    t.setShowDelay(Duration.millis(300));
                    node.setTooltip(t);
                }
                t.setText(i.hint());
            } else {
                node.setTooltip(null);
            }
        }
    }

    public StatusDock(MotionService motion, Consumer<String> request) {
        this.motion = motion;
        this.request = request;
        getStyleClass().add("byx-dock");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(38);
        setPrefHeight(38);
        setMaxHeight(38);
    }

    /** Estado real atual. Igual ao anterior: nada muda (nem nós, nem animação). */
    public void setModel(List<Group> next) {
        if (next.equals(model)) {
            return;
        }
        if (sameStructure(model, next)) {
            int k = 0;
            for (Group g : next) {
                for (Item i : g.items()) {
                    views.get(k++).apply(i);
                }
            }
            model = List.copyOf(next);
            return;
        }
        model = List.copyOf(next);
        rebuild();
    }

    private static boolean sameStructure(List<Group> a, List<Group> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int g = 0; g < a.size(); g++) {
            List<Item> x = a.get(g).items();
            List<Item> y = b.get(g).items();
            if (!a.get(g).label().equals(b.get(g).label()) || x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!x.get(i).key().equals(y.get(i).key()) || (x.get(i).dot() == null) != (y.get(i).dot() == null)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void rebuild() {
        rebuilds++;
        views.clear();
        List<Node> children = new ArrayList<>();
        for (int g = 0; g < model.size(); g++) {
            if (g > 0) {
                Region vl = new Region();
                vl.getStyleClass().add("byx-dock-sep");
                vl.setMinSize(1, 18);
                vl.setPrefSize(1, 18);
                vl.setMaxSize(1, 18);
                HBox.setMargin(vl, new javafx.geometry.Insets(0, 22, 0, 22)); // .dock .vl margin 0 22
                children.add(vl);
            }
            Group group = model.get(g);
            Label name = ByxFonts.upper(new Label(group.label()));
            name.getStyleClass().add("byx-dock-group");
            name.setMinWidth(Region.USE_PREF_SIZE);
            HBox box = new HBox(16, name);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("byx-dock-grp");
            box.setMinWidth(Region.USE_PREF_SIZE);
            for (Item i : group.items()) {
                ItemView v = new ItemView(i);
                views.add(v);
                box.getChildren().add(v.node);
            }
            children.add(box);
        }
        getChildren().setAll(children);
    }

    public List<Group> model() {
        return model;
    }

    /** Quantas vezes os nós foram recriados (teste: estado igual não reconstrói). */
    public int rebuilds() {
        return rebuilds;
    }

    public Button itemNode(String key) {
        for (ItemView v : views) {
            if (v.item.key().equals(key)) {
                return v.node;
            }
        }
        return null;
    }
}
