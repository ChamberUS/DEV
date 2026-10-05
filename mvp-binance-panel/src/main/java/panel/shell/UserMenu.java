package panel.shell;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxIcon;
import panel.design.ByxOverlayHost;

/**
 * Menu do usuário V2 (popover 300; cabeçalho com iniciais, nome, e-mail e papel vindos da sessão). Itens na
 * ordem do handoff. Destino sem tela ainda: desabilitado com COMING SOON, nunca abre tela falsa. Item da
 * rota atual: tinta + ponto. Setas (com volta), Home/End, Enter; Esc fecha e devolve o foco ao avatar;
 * Tab fecha; clique fora fecha. Abrir o painel de notificações fecha este menu (camada de popover única).
 */
public final class UserMenu {
    /** route: destino real (pede navegação); action: comando real; pending: motivo de ainda não existir. */
    public record Item(String label, String icon, String shortcut, String route, Runnable action, String pending,
            boolean danger, boolean separatorBefore) {
        public static Item route(String label, String icon, String shortcut, String route) {
            return new Item(label, icon, shortcut, route, null, null, false, false);
        }

        public static Item action(String label, String icon, String shortcut, Runnable action) {
            return new Item(label, icon, shortcut, null, action, null, false, false);
        }

        public static Item pending(String label, String icon, String shortcut, String reason) {
            return new Item(label, icon, shortcut, null, null, reason, false, false);
        }

        public Item asDanger() {
            return new Item(label, icon, shortcut, route, action, pending, true, true);
        }
    }

    /** Identidade vinda da sessão; nada é derivado na UI. email pode ser null. */
    public record Identity(String name, String email, String role) {
    }

    private final ByxOverlayHost overlay;
    private final Button avatar;
    private final ShellRouter router;
    private Identity identity = new Identity("", null, "");
    private List<Item> items = List.of();
    private VBox panel;
    private final List<Button> buttons = new ArrayList<>();

    public UserMenu(ByxOverlayHost overlay, Button avatar, ShellRouter router) {
        this.overlay = overlay;
        this.avatar = avatar;
        this.router = router;
        avatar.setOnAction(e -> toggle(true));
        avatar.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                open(e.getCode() == KeyCode.DOWN);
                e.consume();
            }
        });
        updateExpanded(false);
    }

    public void setIdentity(Identity id) {
        identity = id;
    }

    public void setItems(List<Item> items) {
        this.items = List.copyOf(items);
    }

    public boolean isOpen() {
        return panel != null && overlay.isPopoverOpen(panel);
    }

    public void toggle(boolean focusFirst) {
        if (isOpen()) {
            close();
        } else {
            open(focusFirst);
        }
    }

    public void open(boolean focusFirst) {
        if (isOpen()) {
            focus(focusFirst ? 0 : buttons.size() - 1);
            return;
        }
        panel = build();
        Bounds b = avatar.localToScene(avatar.getLayoutBounds());
        var p = overlay.sceneToLocal(b.getMaxX(), b.getMaxY() + 8);
        double width = 300;
        panel.getProperties().put("byx.popover.owner", avatar);
        VBox opened = panel;
        overlay.openPopover(panel, Math.max(8, p.getX() - width), p.getY(), () -> {
            returnFocusIfInside(opened, avatar);
            panel = null;
            buttons.clear();
            updateExpanded(false);
        });
        updateExpanded(true);
        focus(focusFirst ? 0 : buttons.size() - 1);
    }

    public void close() {
        if (panel != null) {
            overlay.closePopover(panel);
        }
    }

    public List<Button> itemButtons() {
        return List.copyOf(buttons);
    }

    /** Fechou com o foco dentro do popover (ou sem foco): devolve ao abridor (Esc, Enter, clique). */
    static void returnFocusIfInside(Node popover, Node opener) {
        if (opener.getScene() == null) {
            return;
        }
        Node f = opener.getScene().getFocusOwner();
        boolean inside = f == null;
        for (Node x = f; x != null && !inside; x = x.getParent()) {
            inside = x == popover;
        }
        if (inside) {
            opener.requestFocus();
        }
    }

    private void updateExpanded(boolean on) {
        avatar.getStyleClass().remove("expanded");
        if (on) {
            avatar.getStyleClass().add("expanded");
        }
        avatar.setAccessibleHelp(on ? "expanded" : "collapsed");
    }

    private VBox build() {
        buttons.clear();
        Label initials = new Label(ShellTopBar.initials(identity.name()));
        initials.getStyleClass().add("byx-avatar-static");
        initials.setMinSize(36, 36);
        initials.setPrefSize(36, 36);
        initials.setAlignment(Pos.CENTER);
        Label name = new Label(identity.name());
        name.getStyleClass().add("byx-menu-name");
        name.setMinWidth(0);
        Label email = new Label(identity.email() == null ? "Email not provided by the API" : identity.email());
        email.getStyleClass().add("byx-menu-email");
        email.setMinWidth(0);
        VBox who = new VBox(2, name, email);
        who.setMinWidth(0);
        HBox.setHgrow(who, Priority.ALWAYS);
        Label role = ByxBadge.of(identity.role().toUpperCase(java.util.Locale.ROOT), ByxBadge.Tone.ACCENT);
        role.setMinWidth(Region.USE_PREF_SIZE); // o papel nunca é cortado; nome e e-mail reticenciam
        HBox header = new HBox(10, initials, who, role);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("byx-menu-header");

        VBox list = new VBox(2);
        for (Item it : items) {
            if (it.separatorBefore()) {
                Region sep = new Region();
                sep.getStyleClass().add("byx-menu-sep");
                list.getChildren().add(sep);
            }
            list.getChildren().add(item(it));
        }
        VBox p = new VBox(6, header, list);
        p.getStyleClass().addAll("byx-popover", "byx-user-menu");
        p.setPrefWidth(300);
        p.setMaxWidth(300);
        p.setAccessibleRole(javafx.scene.AccessibleRole.CONTEXT_MENU);
        p.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        return p;
    }

    private Node item(Item it) {
        Label label = new Label(it.label());
        label.getStyleClass().add("byx-menu-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        spacer.setMinWidth(0);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, ByxIcon.path(ShellIcons.path(it.icon()), 18, null), label, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        boolean current = it.route() != null && it.route().equals(router.route());
        if (it.pending() != null) {
            Label soon = ByxBadge.availability(ByxBadge.Availability.COMING_SOON);
            soon.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(soon);
        } else if (current) {
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3);
            dot.getStyleClass().add("byx-menu-current-dot");
            row.getChildren().add(dot);
        } else if (it.shortcut() != null) {
            Label k = new Label(it.shortcut());
            k.getStyleClass().add("byx-menu-key");
            row.getChildren().add(k);
        }
        Button b = new Button();
        b.setGraphic(row);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("byx-menu-item");
        if (it.danger()) {
            b.getStyleClass().add("danger");
        }
        if (current) {
            b.getStyleClass().add("current");
        }
        b.setAccessibleText(it.label() + (it.pending() != null ? ", " + it.pending() : current ? ", current page" : ""));
        b.setDisable(it.pending() != null);
        b.setOnAction(e -> {
            close();
            if (it.route() != null) {
                router.request(it.route());
            } else if (it.action() != null) {
                it.action().run();
            }
        });
        if (it.pending() == null) {
            buttons.add(b);
        }
        return b;
    }

    private void focus(int i) {
        if (!buttons.isEmpty()) {
            buttons.get(Math.floorMod(i, buttons.size())).requestFocus();
        }
    }

    private int focusedIndex() {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isFocused()) {
                return i;
            }
        }
        return -1;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case DOWN -> focus(focusedIndex() + 1);
            case UP -> focus(focusedIndex() < 0 ? buttons.size() - 1 : focusedIndex() - 1);
            case HOME -> focus(0);
            case END -> focus(buttons.size() - 1);
            case ESCAPE -> close();
            case TAB -> close();
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Teclas para testes sem janela. */
    public void key(KeyCode code) {
        if (panel != null) {
            onKey(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        }
    }
}
