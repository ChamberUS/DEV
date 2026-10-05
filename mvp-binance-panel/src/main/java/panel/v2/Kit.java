package panel.v2;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;

/**
 * Blocos compartilhados das telas V2 dos Passos 9–12 (BYX, Account, Help, System). Só classes {@code .byx-*};
 * nenhum valor visual novo: reaproveita o painel, o título e as linhas da referência do Desk.
 */
public final class Kit {
    private Kit() {
    }

    /** Rolagem V2: ocupa a largura, só rola quando a janela é menor que o conteúdo. */
    public static ScrollPane scroll(Node content) {
        ScrollPane s = new ScrollPane(content);
        s.getStyleClass().add("byx-desk-scroll");
        s.setFitToWidth(true);
        s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        s.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        s.setMinSize(0, 0);
        s.setPannable(false);
        return s;
    }

    /** Coluna de página (margens P2.6: 22/28 no COMPACT). */
    public static VBox page(double spacing) {
        VBox v = new VBox(spacing);
        v.getStyleClass().addAll("byx-desk", "byx-screen");
        v.setPadding(new Insets(22, 28, 24, 28));
        v.setMinSize(0, 0);
        return v;
    }

    /** Coluna de leitura centrada com largura máxima (Settings, About, Terms: 1240). */
    public static VBox reading(double spacing, double maxWidth) {
        VBox v = page(spacing);
        v.setMaxWidth(maxWidth);
        return v;
    }

    public static HBox header(String title, String subtitle, Node... trailing) {
        VBox titles = new VBox(2, Fx.label(title, "byx-page-title"));
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = Fx.label(subtitle, "byx-desk-secondary");
            sub.setWrapText(true);
            titles.getChildren().add(sub);
        }
        HBox h = new HBox(16, titles, Fx.spacer());
        h.getChildren().addAll(trailing);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setMinHeight(Region.USE_PREF_SIZE);
        return h;
    }

    public static VBox panel(String title, Node... body) {
        VBox p = new VBox(10);
        p.getStyleClass().add("byx-panel");
        if (title != null) {
            p.getChildren().add(Fx.label(title, "byx-section-title-sm"));
        }
        p.getChildren().addAll(body);
        return p;
    }

    /** Título de painel com selo à direita. */
    public static HBox titled(String title, Node trailing) {
        HBox h = new HBox(8, Fx.label(title, "byx-section-title-sm"), Fx.spacer(), trailing);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static Label muted(String text) {
        Label l = Fx.label(text, "byx-desk-secondary", "byx-desk-body");
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    public static Label dim(String text) {
        Label l = Fx.label(text, "byx-desk-t3", "byx-desk-body");
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    public static Label label(String text) {
        return ByxFonts.upper(Fx.label(text, "byx-label"));
    }

    public static KvRow row(String key, String value, boolean mono) {
        KvRow r = new KvRow(key);
        r.set(value, mono, null);
        return r;
    }

    public static Label badge(String text, ByxBadge.Tone tone) {
        return ByxBadge.of(text, tone);
    }

    /** Faixa de ambiente (referência: painel hachurado com selo, título e texto). */
    public static HBox environment(String badge, String title, String text) {
        Label t = Fx.label(title, "byx-section-title-sm");
        Label x = Fx.label(text, "byx-desk-secondary");
        x.setWrapText(true);
        HBox h = new HBox(14, ByxBadge.of(badge, ByxBadge.Tone.WARNING), t, x);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().addAll("byx-panel", "byx-env-banner");
        return h;
    }

    public static void grow(Node n) {
        HBox.setHgrow(n, Priority.ALWAYS);
        VBox.setVgrow(n, Priority.ALWAYS);
    }

    /** Controle segmentado (referência .byx-desk-seg): uma opção selecionada; notifica só quando muda. */
    public static final class Segmented extends HBox {
        private final java.util.List<javafx.scene.control.ToggleButton> buttons = new java.util.ArrayList<>();
        private final javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        private final java.util.function.Consumer<String> onChange;

        public Segmented(java.util.List<String> options, String selected, java.util.function.Consumer<String> onChange) {
            super(0);
            this.onChange = onChange;
            getStyleClass().add("byx-desk-seg");
            for (String o : options) {
                javafx.scene.control.ToggleButton b = new javafx.scene.control.ToggleButton(o);
                b.getStyleClass().add("byx-desk-seg-btn");
                b.setToggleGroup(group);
                b.setSelected(o.equals(selected));
                b.setUserData(o);
                buttons.add(b);
                getChildren().add(b);
            }
            setOnKeyPressed(e -> {
                int dir = e.getCode() == javafx.scene.input.KeyCode.RIGHT ? 1 : e.getCode() == javafx.scene.input.KeyCode.LEFT ? -1 : 0;
                if (dir == 0) {
                    return;
                }
                int i = 0;
                for (int k = 0; k < buttons.size(); k++) {
                    if (buttons.get(k).isFocused() || buttons.get(k).isSelected() && !anyFocused()) {
                        i = k;
                    }
                }
                for (int step = 1; step <= buttons.size(); step++) {
                    javafx.scene.control.ToggleButton b = buttons.get(Math.floorMod(i + dir * step, buttons.size()));
                    if (!b.isDisabled()) {
                        b.setSelected(true);
                        b.requestFocus();
                        break;
                    }
                }
                e.consume();
            });
            group.selectedToggleProperty().addListener((obs, a, b) -> {
                if (b == null) {
                    a.setSelected(true); // sempre uma opção
                } else if (!suppress) {
                    onChange.accept((String) b.getUserData());
                }
            });
        }

        private boolean suppress;

        private boolean anyFocused() {
            return buttons.stream().anyMatch(javafx.scene.control.ToggleButton::isFocused);
        }

        public String selected() {
            return group.getSelectedToggle() == null ? null : (String) group.getSelectedToggle().getUserData();
        }

        /** Define sem notificar (descartar edição). */
        public void select(String value) {
            suppress = true;
            for (javafx.scene.control.ToggleButton b : buttons) {
                b.setSelected(value.equals(b.getUserData()));
            }
            suppress = false;
        }

        public void disable(String option, boolean off) {
            buttons.stream().filter(b -> option.equals(b.getUserData())).forEach(b -> b.setDisable(off));
        }

        public java.util.List<javafx.scene.control.ToggleButton> buttons() {
            return buttons;
        }
    }

    /** Linha de configuração: título + descrição à esquerda, controle à direita. */
    public static HBox setting(String title, String description, Node control) {
        VBox copy = new VBox(2, Fx.label(title, "byx-section-title-sm"), muted(description));
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(16, copy, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("byx-desk-row");
        return row;
    }
}
