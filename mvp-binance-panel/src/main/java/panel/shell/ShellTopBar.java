package panel.shell;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import panel.design.ByxBadge;
import panel.design.ByxIcon;

/**
 * Top bar V2 (56 px; medidas do handoff P2.4): seletor de workspace, breadcrumb (+ chip de contexto fora dos
 * workspaces), busca 320x36, botão de notificações 38 (contagem 18, oculta em 0), selo ADMIN SESSION e avatar
 * 36. Os controles só abrem camadas ou pedem navegação; nenhum guarda rota própria.
 */
public final class ShellTopBar extends HBox {
    private final WorkspaceSwitcher switcher;
    private final Label contextChip = new Label();
    private final Label crumb = new Label();
    private final Button search = new Button();
    private final Button notifications = new Button();
    private final Label count = new Label();
    private final Label adminBadge = ByxBadge.of("ADMIN SESSION", ByxBadge.Tone.ACCENT);
    private final Label mockBadge = ByxBadge.data(ByxBadge.Data.DEMO_DATA);
    private final Button avatar = new Button();
    private final panel.shell.avatar.MascotAvatar mascot;

    public ShellTopBar(WorkspaceSwitcher switcher, String shortcutPrefix, panel.motion.MotionService motion) {
        this.switcher = switcher;
        getStyleClass().add("byx-topbar");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(56);
        setPrefHeight(56);
        setMaxHeight(56);

        contextChip.getStyleClass().add("byx-context-chip");
        contextChip.setMinWidth(Region.USE_PREF_SIZE);
        contextChip.setVisible(false);
        contextChip.setManaged(false);
        crumb.getStyleClass().add("byx-crumb");
        crumb.setMinWidth(0);
        HBox breadcrumb = new HBox(8, contextChip, crumb);
        breadcrumb.getStyleClass().add("byx-breadcrumb");
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(breadcrumb, Priority.SOMETIMES);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(0);

        Label prompt = new Label("Search or jump to…");
        prompt.getStyleClass().add("byx-search-prompt");
        Label key = new Label(shortcutPrefix + "K");
        key.getStyleClass().add("byx-search-key");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox searchGraphic = new HBox(10, ByxIcon.path(ShellIcons.path("search"), 16, null), prompt, gap, key);
        searchGraphic.setAlignment(Pos.CENTER_LEFT);
        searchGraphic.setPrefWidth(320 - 24);
        search.setGraphic(searchGraphic);
        search.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        search.getStyleClass().add("byx-search");
        search.setMinSize(320, 36);
        search.setPrefSize(320, 36);
        search.setMaxSize(320, 36);
        search.setAccessibleText("Search or jump to, " + shortcutPrefix + "K");

        notifications.setGraphic(ByxIcon.path(ShellIcons.path("bell"), 20, null));
        notifications.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        notifications.getStyleClass().add("byx-tbtn");
        notifications.setMinSize(38, 38);
        notifications.setPrefSize(38, 38);
        notifications.setMaxSize(38, 38);
        count.getStyleClass().add("byx-count");
        count.setMouseTransparent(true);
        StackPane bell = new StackPane(notifications, count);
        StackPane.setAlignment(count, Pos.TOP_RIGHT);
        count.setTranslateY(2);
        bell.setMaxSize(38, 38);
        setUnread(0);

        adminBadge.getStyleClass().add("byx-admin-badge");
        adminBadge.setMinWidth(Region.USE_PREF_SIZE);
        setAdminSession(false);
        mockBadge.setText("MOCK DATA");
        mockBadge.setVisible(false);
        mockBadge.setManaged(false);

        avatar.getStyleClass().add("byx-avatar");
        avatar.setAccessibleText("Account menu"); // nome padrão; setUser() acrescenta o nome da conta
        avatar.setMinSize(36, 36);
        avatar.setPrefSize(36, 36);
        avatar.setMaxSize(36, 36);
        // B03: the mascot IS the avatar, drawn inside this same button (never re-parented, never recreated per page)
        mascot = new panel.shell.avatar.MascotAvatar(avatar, motion);

        getChildren().addAll(switcher, breadcrumb, spacer, mockBadge, search, bell, adminBadge, avatar);
    }

    public WorkspaceSwitcher switcher() {
        return switcher;
    }

    public Button search() {
        return search;
    }

    public Button notifications() {
        return notifications;
    }

    public Button avatar() {
        return avatar;
    }

    public panel.shell.avatar.MascotAvatar mascot() {
        return mascot;
    }

    /** Where real pending operations are reported so the avatar can show them (never simulated). */
    public panel.shell.avatar.Operations operations() {
        return mascot.operations();
    }

    public void dispose() {
        mascot.dispose();
    }

    public String crumbText() {
        return crumb.getText();
    }

    public String contextChipText() {
        return contextChip.isVisible() ? contextChip.getText() : null;
    }

    /** Breadcrumb da rota atual; chip só fora dos workspaces (ex.: ACCOUNT). */
    public void setBreadcrumb(String chip, String text) {
        boolean on = chip != null;
        contextChip.setText(on ? chip : "");
        contextChip.setVisible(on);
        contextChip.setManaged(on);
        crumb.setText(text);
    }

    /** Contagem do serviço de notificações; 0 esconde a contagem. Troca sem animação. */
    public void setUnread(int n) {
        boolean show = n > 0;
        count.setText(show ? (n > 99 ? "99+" : Integer.toString(n)) : "");
        count.setVisible(show);
        notifications.setAccessibleText(show ? "Notifications, " + n + " unread" : "Notifications");
    }

    public boolean unreadVisible() {
        return count.isVisible();
    }

    public void setAdminSession(boolean on) {
        adminBadge.setVisible(on);
        adminBadge.setManaged(on);
    }

    /** Dados de origem MOCK: selo honesto na barra (o V2 não tem posição própria para ele). */
    public void setMockData(boolean on) {
        mockBadge.setVisible(on);
        mockBadge.setManaged(on);
    }

    public void setUser(String displayName) {
        mascot.setUserName(displayName); // accessible name only: the artwork is the same mascot for every account
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("[\\s._-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && sb.length() < 2) {
                sb.append(Character.toUpperCase(p.charAt(0)));
            }
        }
        if (sb.length() == 1 && parts[0].length() > 1) {
            sb.append(Character.toUpperCase(parts[0].charAt(1)));
        }
        return sb.toString();
    }
}
