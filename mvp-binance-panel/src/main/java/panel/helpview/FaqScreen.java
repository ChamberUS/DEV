package panel.helpview;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.design.ByxIcon;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * FAQ V2: busca, categorias, acordeão com teclado (Enter/Space alterna; ↑/↓, Home/End entre perguntas; ↓ da busca entra na
 * lista) e estado sem resultados. O texto vem de /content/faq.json (PLACEHOLDER_CONTENT enquanto não for conteúdo oficial).
 * Funciona também sem sessão (modo público).
 */
public final class FaqScreen implements View {
    private final HelpContent.FaqContent content;
    private final MotionService motion;
    private final Consumer<String> navigate;
    private final ScrollPane scroll;
    private final ByxField search = ByxField.text("Search the FAQ");
    private final FlowPane chips = new FlowPane(8, 8);
    private final VBox list = new VBox(0);
    private final List<Button> heads = new ArrayList<>();
    private String category;
    private String openId;

    public FaqScreen(MotionService motion, HelpContent.FaqContent content, Consumer<String> navigate) {
        this.motion = motion;
        this.content = content;
        this.navigate = navigate;
        search.input().setPromptText("Search questions and answers");
        search.setMaxWidth(640);
        search.input().textProperty().addListener((o, a, b) -> render());
        search.input().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && !heads.isEmpty()) {
                heads.get(0).requestFocus();
                e.consume();
            }
        });
        Label badge = content.status().equals("PLACEHOLDER_CONTENT") ? ByxBadge.data(ByxBadge.Data.PLACEHOLDER_CONTENT)
                : ByxBadge.of(content.status(), ByxBadge.Tone.NEUTRAL);
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("FAQ", "Answers about the workspaces, environments and security.", badge), search, chips, list);
        scroll = Kit.scroll(page);
        buildChips();
        render();
    }

    private void buildChips() {
        chips.getChildren().clear();
        chips.getChildren().add(chip("All", null));
        for (int i = 0; i < content.categoryIds().size(); i++) {
            chips.getChildren().add(chip(content.categoryTitles().get(i), content.categoryIds().get(i)));
        }
    }

    private Node chip(String text, String id) {
        Button b = new Button(text);
        b.getStyleClass().add("byx-filter-chip");
        boolean on = Objects.equals(id, category);
        if (on) {
            b.getStyleClass().add("selected");
        }
        b.setAccessibleText("Category " + text + (on ? ", selected" : ""));
        b.setOnAction(e -> {
            category = id;
            buildChips();
            render();
        });
        return b;
    }

    String query() {
        return search.input().getText();
    }

    void setQuery(String q) {
        search.input().setText(q);
    }

    List<Button> heads() {
        return heads;
    }

    String openId() {
        return openId;
    }

    /** Abre uma pergunta (deep link #q-id). */
    public void open(String id) {
        category = null;
        search.input().clear();
        openId = id;
        buildChips();
        render();
    }

    private void render() {
        list.getChildren().clear();
        heads.clear();
        List<HelpContent.Faq> items = HelpContent.filter(content, category, search.input().getText());
        if (items.isEmpty()) {
            ByxButton clear = new ByxButton("Clear search", ByxButton.Variant.SECONDARY, motion);
            clear.setOnAction(e -> {
                search.input().clear();
                category = null;
                buildChips();
                render();
            });
            ByxButton report = new ByxButton("Report a problem", ByxButton.Variant.GHOST, motion);
            report.setOnAction(e -> navigate.accept("h-help"));
            VBox empty = new VBox(10, Fx.label("No results", "byx-section-title-sm"),
                    Kit.muted("No question matches \"" + search.input().getText().trim() + "\"."), new HBox(8, clear, report));
            empty.setPadding(new javafx.geometry.Insets(24, 0, 0, 0));
            empty.setId("faq-empty");
            list.getChildren().add(empty);
            return;
        }
        for (HelpContent.Faq f : items) {
            list.getChildren().add(item(f));
        }
    }

    private Node item(HelpContent.Faq f) {
        boolean open = f.id().equals(openId);
        String q = search.input().getText();
        Button head = new Button();
        head.getStyleClass().add("byx-faq-head");
        head.setMaxWidth(Double.MAX_VALUE);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox label = new HBox(12, Highlight.flow(f.question(), q, "byx-faq-q"), ByxIcon.path(open ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6", 14, "t3"));
        label.setAlignment(Pos.CENTER_LEFT);
        head.setGraphic(label);
        head.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        head.setAccessibleText(f.question() + (open ? ", expanded" : ", collapsed"));
        head.setId("q-" + f.id());
        javafx.scene.text.TextFlow answer = Highlight.flow(f.answer(), q, "byx-faq-a");
        answer.setMaxWidth(760);
        answer.setMinHeight(Region.USE_PREF_SIZE);
        Fx.shown(answer, open);
        VBox box = new VBox(8, head, answer);
        box.getStyleClass().add("byx-faq-item");
        head.setOnAction(e -> toggle(f.id()));
        head.setOnKeyPressed(e -> {
            int i = heads.indexOf(head);
            switch (e.getCode()) {
                case DOWN -> focus(i + 1);
                case UP -> {
                    if (i == 0) {
                        search.input().requestFocus();
                    } else {
                        focus(i - 1);
                    }
                }
                case HOME -> focus(0);
                case END -> focus(heads.size() - 1);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        heads.add(head);
        return box;
    }

    private void focus(int i) {
        if (i >= 0 && i < heads.size()) {
            heads.get(i).requestFocus();
        }
    }

    /** Uma pergunta aberta por vez; o estado final é o mesmo nos três modos (o fade é só apresentação). */
    private void toggle(String id) {
        boolean opening = !id.equals(openId);
        openId = opening ? id : null;
        render();
        heads.stream().filter(h -> h.getId().equals("q-" + id)).findFirst().ifPresent(h -> {
            h.requestFocus();
            if (opening) {
                motion.fadeIn(((VBox) h.getParent()).getChildren().get(1), motion.duration("accordionExpand"));
            }
        });
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
