package panel.shell;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.value.ChangeListener;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxOverlayHost;
import panel.motion.MotionService;

/**
 * Shell V2 real: rail (68) | top bar (56) / conteúdo / dock (38), dentro do host de camadas 30–80.
 * O conteúdo é um {@link LegacyHost} enquanto as Views não forem portadas. Tudo que é visual de rota
 * (rail, seletor, breadcrumb, acento, contexto legado) é derivado do {@link ShellRouter#routeProperty()};
 * nenhum componente do shell guarda rota própria.
 */
public final class ByxShell extends StackPane {
    private final ShellRouter router;
    private final ShellRail rail;
    private final WorkspaceSwitcher switcher;
    private final ShellTopBar topBar;
    private final StatusDock dock;
    private final LegacyHost content;
    private final ByxOverlayHost overlay;
    private final Map<ShellContext, String> lastRoute = new EnumMap<>(ShellContext.class);
    private final ChangeListener<String> routeListener = (o, a, b) -> applyRoute(b);
    private final String shortcutPrefix;
    private final MotionService motion;
    private Predicate<String> available = id -> true;
    private Function<String, String> crumb = id -> ShellRoutes.get(id).map(ShellRoutes.Route::title).orElse(id);
    private Runnable openSearch = () -> { };
    private ShellContext accent;
    private final StackPane main;
    /** Conteúdo V2 (Views já portadas): fora do LegacyHost, sem folhas legadas. */
    private final StackPane v2Content = new StackPane();
    private javafx.scene.Node mainOverlay;
    private panel.mascot.MascotTransitionOverlay workspaceMascot;
    private final VBox center;
    private javafx.scene.Node globalBar;

    public ByxShell(ShellRouter router, MotionService motion, LegacyHost content) {
        this.router = router;
        this.motion = motion;
        this.content = content;
        this.shortcutPrefix = isMac() ? "⌘" : "Ctrl+";
        getStyleClass().add("byx-shell");
        rail = new ShellRail(router, motion, shortcutPrefix);
        switcher = new WorkspaceSwitcher(motion, this::pickWorkspace);
        topBar = new ShellTopBar(switcher, shortcutPrefix);
        dock = new StatusDock(motion, router::request);
        v2Content.getStyleClass().add("byx-v2-content");
        v2Content.setMinSize(0, 0);
        v2Content.setVisible(false);
        v2Content.setManaged(false);
        main = new StackPane(content, v2Content);
        main.getStyleClass().add("byx-main");
        main.setMinSize(0, 0);
        center = new VBox(topBar, main, dock);
        VBox.setVgrow(main, Priority.ALWAYS);
        center.setMinWidth(0);
        BorderPane frame = new BorderPane(center);
        frame.setLeft(rail);
        frame.getStyleClass().add("byx-frame");
        overlay = new ByxOverlayHost(frame, motion);
        overlay.setToastMargin(new javafx.geometry.Insets(0, 0, 38 + 16, 68 + 16)); // acima do dock, ao lado do rail
        getChildren().add(overlay);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onShortcut);
        addEventFilter(KeyEvent.KEY_TYPED, this::onHelpKey);
        router.routeProperty().addListener(routeListener);
        topBar.search().setOnAction(e -> openSearch.run());
        if (router.route() != null) {
            applyRoute(router.route());
        }
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    public String shortcutPrefix() {
        return shortcutPrefix;
    }

    public ShellRail rail() {
        return rail;
    }

    public WorkspaceSwitcher switcher() {
        return switcher;
    }

    public ShellTopBar topBar() {
        return topBar;
    }

    public StatusDock dock() {
        return dock;
    }

    public ByxOverlayHost overlay() {
        return overlay;
    }

    public LegacyHost content() {
        return content;
    }

    /** Onde ficam as Views V2 (uma visível por vez, escolhida pelo roteador). */
    public StackPane v2Content() {
        return v2Content;
    }

    /** A View ativa é V2 (true) ou legada (false): só um dos dois hosts fica visível. */
    public void showV2(boolean v2) {
        if (v2Content.isVisible() != v2) {
            v2Content.setVisible(v2);
            v2Content.setManaged(v2);
        }
        if (content.isVisible() == v2) {
            content.setVisible(!v2);
            content.setManaged(!v2);
        }
    }

    /**
     * Conteúdo V2 por cima da área principal (ex.: verificação de admin enquanto a rota Research está pendente).
     * Fica fora do LegacyHost; null remove. Não muda a rota.
     */
    public void setMainOverlay(javafx.scene.Node node) {
        if (mainOverlay != null) {
            main.getChildren().remove(mainOverlay);
        }
        mainOverlay = node;
        if (node != null) {
            main.getChildren().add(node);
        }
    }

    /** Faixa global fina sob a barra superior (perda do backend); null remove. Não muda a rota nem recria o conteúdo. */
    public void setGlobalBar(javafx.scene.Node bar) {
        if (globalBar == bar) {
            return;
        }
        if (globalBar != null) {
            center.getChildren().remove(globalBar);
        }
        globalBar = bar;
        if (bar != null) {
            center.getChildren().add(1, bar);
            motion.fadeIn(bar, motion.duration("globalBarShow")); // só apresentação; a faixa existe com a condição
        }
    }

    public javafx.scene.Node globalBar() {
        return globalBar;
    }

    public javafx.scene.Node mainOverlay() {
        return mainOverlay;
    }

    /** Rotas que existem nesta sessão (papéis vêm da autenticação; a UI não concede nada). */
    public void setAvailable(Predicate<String> available) {
        this.available = available;
        rail.setAvailable(available);
        switcher.setVisible(ShellContext.RESEARCH, available.test(ShellRoutes.home(ShellContext.RESEARCH)));
    }

    /** Texto do breadcrumb por rota (ex.: Desk / símbolo atual). */
    public void setCrumb(Function<String, String> crumb) {
        this.crumb = crumb;
        refreshCrumb();
    }

    public void refreshCrumb() {
        String r = router.route();
        if (r == null) {
            return;
        }
        ShellContext c = ShellRoutes.contextOf(r);
        topBar.setBreadcrumb(c.workspace ? null : c.label.toUpperCase(java.util.Locale.ROOT), crumb.apply(r));
    }

    public void setOnOpenSearch(Runnable r) {
        openSearch = r;
    }

    /** Workspace escolhido no seletor: pede a última rota válida dele (ou a inicial). */
    private void pickWorkspace(ShellContext c) {
        String last = lastRoute.get(c);
        router.request(last != null && available.test(last) ? last : ShellRoutes.home(c));
    }

    private void applyRoute(String route) {
        if (route == null) {
            return;
        }
        ShellContext c = ShellRoutes.contextOf(route);
        lastRoute.put(c, route);
        if (c != accent) {
            if (accent != null) {
                getStyleClass().remove(accent.accentClass());
            }
            getStyleClass().add(c.accentClass());
            accent = c;
        }
        content.setContext(c.legacyContext);
        rail.select(route);
        switcher.select(c);
        refreshCrumb();
    }

    /** "?" abre o diálogo de atalhos, nunca enquanto se digita num campo, nem com outra camada aberta. */
    private void onHelpKey(javafx.scene.input.KeyEvent e) {
        if (!"?".equals(e.getCharacter()) || e.isShortcutDown() || e.isAltDown() || overlay.openDialogs() > 0 || overlay.paletteOpen()) {
            return;
        }
        javafx.scene.Node focus = getScene() == null ? null : getScene().getFocusOwner();
        if (focus instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        openShortcuts();
        e.consume();
    }

    /** Diálogo de atalhos (camada 70): gerado do registro único; Esc fecha e o foco volta ao abridor. */
    public void openShortcuts() {
        if (overlay.openDialogs() > 0) {
            return;
        }
        javafx.scene.control.Label title = new javafx.scene.control.Label("Keyboard shortcuts");
        title.getStyleClass().add("byx-section-title");
        javafx.scene.control.ScrollPane body = new javafx.scene.control.ScrollPane(panel.helpview.ShortcutsScreen.content(shortcutPrefix.replace("Ctrl+", "Ctrl")));
        body.setFitToWidth(true);
        body.setPrefViewportHeight(420);
        body.getStyleClass().add("byx-desk-scroll");
        panel.design.ByxButton close = new panel.design.ByxButton("Close", panel.design.ByxButton.Variant.SECONDARY, motion);
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(12, title, body, close);
        card.getStyleClass().add("byx-dialog");
        card.setPrefWidth(560);
        card.setMaxSize(560, javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        card.setAccessibleText("Keyboard shortcuts");
        panel.design.ByxOverlayHost.DialogHandle[] h = new panel.design.ByxOverlayHost.DialogHandle[1];
        close.setOnAction(x -> h[0].close());
        h[0] = overlay.openDialog(card, false, close, null);
    }

    /** Cmd/Ctrl+1..5 = itens do rail do contexto atual; Cmd/Ctrl+, = Settings; Cmd/Ctrl+K = busca. */
    private void onShortcut(KeyEvent e) {
        // diálogo aberto (camada 70): atalhos não navegam por trás dele
        if (!e.isShortcutDown() || overlay.openDialogs() > 0) {
            return;
        }
        if (new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN).match(e)) {
            openSearch.run();
            e.consume();
            return;
        }
        if (new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN).match(e)) {
            router.request(ShellRoutes.SETTINGS);
            e.consume();
            return;
        }
        int n = switch (e.getCode()) {
            case DIGIT1 -> 1;
            case DIGIT2 -> 2;
            case DIGIT3 -> 3;
            case DIGIT4 -> 4;
            case DIGIT5 -> 5;
            case DIGIT6 -> 6;
            default -> 0;
        };
        if (n > 0 && rail.context() != null) {
            List<ShellRoutes.Route> items = ShellRoutes.rail(rail.context()).stream().filter(r -> available.test(r.id())).toList();
            if (n <= items.size()) {
                router.request(items.get(n - 1).id());
                e.consume();
            }
        }
    }

    /** Fim de sessão: solta o roteador (que vive mais que o shell) e para animações próprias. */
    /** Mudança de workspace: transição curta do mascote (só FULL; não bloqueia, não captura mouse, criada sob demanda). */
    public void workspaceTransition() {
        if (!motion.full()) {
            return;
        }
        if (workspaceMascot == null) {
            workspaceMascot = new panel.mascot.MascotTransitionOverlay(motion);
            main.getChildren().add(workspaceMascot);
        }
        workspaceMascot.trigger();
    }

    public void dispose() {
        if (workspaceMascot != null) {
            workspaceMascot.dispose();
        }
        router.routeProperty().removeListener(routeListener);
        rail.dispose();
        switcher.dispose();
        overlay.dispose();
    }
}
