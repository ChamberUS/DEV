package panel.shell;

import panel.i18n.Presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxIcon;
import panel.design.ByxOverlayHost;

/**
 * Busca global / paleta V2 (camada 60; 640 de largura, topo 96; grupos NAVIGATION, COMMAND, HELP).
 * Navegação só pede rota ao roteador; o gate real decide. Linhas bloqueadas ficam esmaecidas com o motivo
 * no rodapé e nunca navegam. Setas movem a seleção, Enter ativa, Esc fecha (camada do topo). Resultados
 * atualizam sem animação. Abrir de novo com a paleta aberta só devolve o foco ao campo.
 */
public final class ShellPalette {
    public enum Group { NAVIGATION, COMMAND, HELP }

    /** gate != null: linha bloqueada (motivo); target != null: pede navegação; senão roda action. hint: estado informativo. */
    public record Entry(Group group, String title, String target, Runnable action, String gate, String hint) {
        public static Entry nav(String title, String target, String hint) {
            return new Entry(Group.NAVIGATION, title, target, null, null, hint);
        }

        public static Entry command(String title, Runnable action) {
            return new Entry(Group.COMMAND, title, null, action, null, null);
        }

        public static Entry gated(Group group, String title, String reason) {
            return new Entry(group, title, null, null, reason, null);
        }

        public boolean blocked() {
            return gate != null;
        }
    }

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final ByxOverlayHost overlay;
    private final Consumer<String> request;
    private final Supplier<List<Entry>> index;
    private VBox panel;
    private TextField input;
    private VBox rows;
    private Label footer;
    private final List<Entry> shown = new ArrayList<>();
    private final List<HBox> rowNodes = new ArrayList<>();
    private int selected = -1;

    public ShellPalette(ByxOverlayHost overlay, Consumer<String> request, Supplier<List<Entry>> index) {
        this.overlay = overlay;
        this.request = request;
        this.index = index;
    }

    public boolean isOpen() {
        return panel != null && overlay.paletteOpen();
    }

    public void open() {
        if (overlay.openDialogs() > 0) return;
        if (isOpen()) {
            input.requestFocus(); // open > close > open: continua aberta com o campo focado
            return;
        }
        input = new TextField();
        input.getStyleClass().addAll("byx-input", "byx-palette-input");
        input.setPromptText("Search pages, commands and help");
        input.setAccessibleText("Search or jump to");
        rows = new VBox(2);
        rows.getStyleClass().add("byx-palette-rows");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("byx-palette-scroll");
        scroll.setPrefViewportHeight(420);
        scroll.setMaxHeight(420);
        footer = new Label();
        footer.getStyleClass().add("byx-palette-footer");
        footer.setWrapText(true);
        panel = new VBox(10, input, scroll, footer);
        panel.getStyleClass().addAll("byx-dialog", "byx-palette");
        panel.setPrefWidth(640);
        panel.setMaxWidth(640);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        panel.setAccessibleText("Search");
        input.textProperty().addListener((o, a, b) -> filter());
        panel.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        filter();
        overlay.openPalette(panel, () -> {
            panel = null;
            shown.clear();
            rowNodes.clear();
        });
        input.requestFocus();
    }

    public void close() {
        overlay.closePalette();
    }

    public String inputText() {
        return input == null ? null : input.getText();
    }

    public void setQuery(String q) {
        if (input != null) {
            input.setText(q);
        }
    }

    public List<Entry> results() {
        return List.copyOf(shown);
    }

    public Entry selection() {
        return selected < 0 || selected >= shown.size() ? null : shown.get(selected);
    }

    public String footerText() {
        return footer == null ? null : footer.getText();
    }

    private void filter() {
        String q = input.getText() == null ? "" : input.getText().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        rowNodes.clear();
        rows.getChildren().clear();
        List<Entry> all = index.get();
        for (Group g : Group.values()) {
            List<Entry> hits = all.stream().filter(e -> e.group() == g)
                    .filter(e -> q.isEmpty() || Presentation.text(e.title()).toLowerCase(Locale.ROOT).contains(q) || e.title().toLowerCase(Locale.ROOT).contains(q)).toList();
            if (hits.isEmpty()) {
                continue;
            }
            Label head = new Label(g.name());
            head.getStyleClass().add("byx-palette-group");
            rows.getChildren().add(head);
            for (Entry e : hits) {
                HBox row = row(e, shown.size());
                shown.add(e);
                rowNodes.add(row);
                rows.getChildren().add(row);
            }
        }
        if (shown.isEmpty()) {
            Label none = new Label("No results for “" + input.getText().trim() + "”.");
            none.getStyleClass().addAll("byx-body", "byx-secondary");
            rows.getChildren().add(none);
        }
        select(firstSelectable());
    }

    private int firstSelectable() {
        for (int i = 0; i < shown.size(); i++) {
            if (!shown.get(i).blocked()) {
                return i;
            }
        }
        return shown.isEmpty() ? -1 : 0;
    }

    private HBox row(Entry e, int i) {
        Label title = new Label(e.title());
        title.getStyleClass().add("byx-palette-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        String kind = e.blocked() ? "LOCKED" : e.target() != null ? "PAGE" : e.group() == Group.HELP ? "HELP" : "COMMAND";
        Label type = new Label(kind);
        type.getStyleClass().add("byx-palette-type");
        HBox row = new HBox(10);
        if (e.blocked()) {
            row.getChildren().add(ByxIcon.path(ShellIcons.path("lock"), 14, null));
        }
        row.getChildren().add(title);
        if (e.hint() != null && !e.hint().isBlank()) {
            Label hint = new Label(e.hint());
            hint.getStyleClass().add("byx-palette-hint");
            row.getChildren().add(hint);
        }
        row.getChildren().addAll(spacer, type);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("byx-palette-row");
        if (e.blocked()) {
            row.getStyleClass().add("blocked");
        }
        row.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> {
            select(i);
            activate();
        });
        row.addEventHandler(MouseEvent.MOUSE_ENTERED, ev -> select(i));
        return row;
    }

    private void select(int i) {
        if (selected >= 0 && selected < rowNodes.size()) {
            rowNodes.get(selected).pseudoClassStateChanged(SELECTED, false);
        }
        selected = i;
        Entry e = selection();
        if (e != null) {
            rowNodes.get(i).pseudoClassStateChanged(SELECTED, true);
        }
        footer.setText(e != null && e.blocked() ? e.title() + ": " + e.gate()
                : "↑↓ to move   ↵ to open   esc to close");
    }

    /** Enter/clique. Linha bloqueada nunca navega nem executa. */
    private void activate() {
        Entry e = selection();
        if (e == null || e.blocked()) {
            return;
        }
        close();
        if (e.target() != null) {
            request.accept(e.target());
        } else if (e.action() != null) {
            e.action().run();
        }
    }

    private void onKey(KeyEvent k) {
        if (k.getCode() == KeyCode.DOWN || k.getCode() == KeyCode.UP) {
            if (!shown.isEmpty()) {
                int step = k.getCode() == KeyCode.DOWN ? 1 : -1;
                select(Math.floorMod(selected + step, shown.size()));
            }
            k.consume();
        } else if (k.getCode() == KeyCode.ENTER) {
            activate();
            k.consume();
        }
    }

    /** Teclas para testes sem janela. */
    public void key(KeyCode code) {
        if (panel != null) {
            onKey(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        }
    }

    public Node panel() {
        return panel;
    }
}
