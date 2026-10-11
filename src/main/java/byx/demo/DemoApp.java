package byx.demo;

import java.nio.file.Path;
import java.util.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import panel.accountview.*;
import panel.authview.AuthLayout;
import panel.design.*;
import panel.i18n.*;
import panel.motion.*;
import panel.nav.Navigator;
import panel.shell.*;
import panel.tradeview.*;
import panel.ui.View;

/** Presentation-only composition. Local preferences have no link to authentication or capabilities. */
public final class DemoApp extends Application {
    public static final Set<String> ROUTES = Set.of("t-home", "t-desk", "t-markets", "t-portfolio",
            "t-positions", "t-orders", "t-history", "t-performance", "t-settings", "h-faq", "h-help", "h-about",
            "h-terms", "h-privacy", "h-shortcuts", "sys-status");
    final DemoAuthProvider auth = new DemoAuthProvider();
    final MotionService motion = new MotionService();
    final StackPane root = new StackPane();
    final DemoPreferencesStore preferencesStore;
    final Preferences preferences = new Preferences();
    final Map<String, View> views = new LinkedHashMap<>();
    final ViewTransitionService transitions = new ViewTransitionService(motion);
    Scene scene;
    Stage stage;
    LocaleView localeView;
    AuthLayout login;
    ByxField identifier, password;
    ByxShell shell;
    ShellRouter router;
    View current;
    ShellPalette palette;
    UserMenu userMenu;
    DemoViewFactory factory;
    String symbol = "ETHUSDT";
    NavigationGuard.Handle leaveGuard;
    private boolean closed;
    private ChangeListener<Strings.Lang> languageListener;
    private ChangeListener<ThemeMode> themeListener;

    public DemoApp() { this(DemoPreferencesStore.defaultPath()); }
    /** Tests instantiate directly with their own presentation file; DemoMain exposes no runtime arguments. */
    DemoApp(Path preferencesPath) { preferencesStore = new DemoPreferencesStore(preferencesPath); }

    @Override public void start(Stage window) {
        stage = window;
        DemoPreferencesStore.Values saved = preferencesStore.load();
        Strings.use(saved.language());
        ByxTheme.select(saved.theme());
        preferences.value = new AccountData.Prefs(saved.motion().name(), saved.density(), saved.animatedIcons(), "TRADING", false);
        applyPreferences();
        panel.ui.Ui.init(motion);
        ByxFonts.load();
        scene = new Scene(root);
        localeView = new LocaleView(scene);
        ByxTheme.apply(scene);
        scene.getStylesheets().add(ByxTheme.stylesheetUrl("/panel/v2/demo.css"));
        stage.setTitle(Strings.get("demo.stageTitle"));
        localeView.bind(stage.titleProperty());
        stage.setScene(scene);
        WindowPolicy.apply(stage);
        languageListener = (o,a,b) -> { persistPresentation(); if (shell != null) { shell.refreshCrumb(); updateDock(); } };
        themeListener = (o,a,b) -> persistPresentation();
        Strings.languageProperty().addListener(languageListener);
        ByxTheme.modeProperty().addListener(themeListener);
        stage.setOnCloseRequest(e -> cleanup());
        showLogin(null);
        stage.show();
        motion.reference.bind(root);
    }

    void showLogin(String error) {
        cleanupViews();
        auth.logout();
        identifier = ByxField.text("Email or username");
        password = ByxField.password("Password");
        identifier.input().setId("demo-user");
        password.input().setId("demo-password");
        identifier.input().setPromptText("teste-byx");
        password.input().setPromptText("Enter your password");
        ByxButton submit = new ByxButton("Sign in", ByxButton.Variant.PRIMARY, motion).wide();
        submit.setId("demo-login");
        submit.setDefaultButton(true);
        submit.setOnAction(e -> {
            char[] secret = password.input().getText().toCharArray();
            boolean ok = auth.login(identifier.input().getText(), secret);
            password.input().clear();
            if (ok) workspace(); else showLogin(Strings.get("demo.loginError"));
        });
        login = new AuthLayout(motion, "DEMO OFFLINE", this::publicHelp);
        Label title = new Label("Sign in");
        title.getStyleClass().add("byx-auth-title");
        Label subtitle = new Label("Access the BYX-MVP terminal.");
        subtitle.getStyleClass().add("byx-auth-sub");
        Label note = new Label(Strings.get("demo.credentials"));
        note.setWrapText(true);
        note.getStyleClass().add("byx-auth-sub");
        List<Node> form = new ArrayList<>(List.of(new VBox(4, title, subtitle)));
        if (error != null) form.add(new ByxBanner(ByxBanner.Kind.ERROR, "Sign-in failed", error));
        form.addAll(List.of(identifier, password, submit, note));
        login.show("Sign in", form);
        root.getChildren().setAll(login);
        Platform.runLater(() -> { if (identifier.getScene() != null) identifier.input().requestFocus(); });
    }

    boolean permitted(String route) { return auth.session() != null && ROUTES.contains(route); }

    private ShellRouter.Decision evaluateRoute(String target, Navigator.Ticket ticket) {
        if (!permitted(target)) return ShellRouter.Decision.DENY;
        if (current != null && !target.equals(router.route()) && current.hasUnsavedChanges()) {
            View leaving = current;
            if (leaveGuard != null && leaveGuard.dialog().isOpen()) leaveGuard.dialog().close();
            leaveGuard = NavigationGuard.open(shell.overlay(), motion, leaving.unsavedChangeCount(), leaving.canSaveChanges(),
                    () -> { if (router.pending() == ticket) router.cancelPending(); },
                    () -> { if (router.pending() == ticket) { leaving.discardChanges(); router.complete(ticket, target); } },
                    leaving::saveChanges, () -> { if (router.pending() == ticket) router.complete(ticket, target); });
            return ShellRouter.Decision.PENDING;
        }
        return ShellRouter.Decision.ALLOW;
    }

    void workspace() {
        login.brand().dispose();
        login = null;
        router = new ShellRouter(new Navigator(), this::evaluateRoute, this::display);
        shell = new ByxShell(router, motion, new LegacyHost());
        shell.setAvailable(this::permitted);
        shell.switcher().setVisible(ShellContext.RESEARCH, false);
        shell.switcher().setVisible(ShellContext.BYX, false);
        shell.showV2(true);
        shell.content().setComfortable(preferences.value.density().equals("COMFORTABLE"));
        shell.topBar().setUser("DEMO_USER / USER");
        shell.topBar().setMockData(true);
        shell.topBar().setAdminSession(false);
        shell.setCrumb(r -> title(r) + (r.equals("t-desk") ? " / " + symbol : ""));
        factory = new DemoViewFactory(motion, () -> DemoFixtures.market(symbol), DemoFixtures.CLOCK, router::request);
        userMenu = new UserMenu(shell.overlay(), shell.topBar().avatar(), router);
        userMenu.setIdentity(new UserMenu.Identity("DEMO_USER", "teste-byx", "USER"));
        userMenu.setItems(List.of(UserMenu.Item.route("Home", "home", null, "t-home"),
                UserMenu.Item.route("Settings", "settings", "Ctrl+,", "t-settings"),
                UserMenu.Item.route("Help", "help", null, "h-help"),
                UserMenu.Item.action("Sign out", "logout", null, () -> showLogin(null)).asDanger()));
        shell.topBar().mascot().bindMenuOpen(userMenu.openProperty());
        shell.topBar().avatar().setId("demo-account");
        shell.topBar().notifications().setOnAction(e -> notificationDialog());
        palette = new ShellPalette(shell.overlay(), router::request, () -> ROUTES.stream().sorted()
                .map(r -> ShellPalette.Entry.nav(title(r), r, Strings.get("demo.offlineNotice"))).toList());
        shell.setOnOpenSearch(palette::open);
        shell.setGlobalBar(demoBar());
        root.getChildren().setAll(shell);
        updateDock();
        router.request("t-desk");
    }

    static String title(String route) {
        String original = ShellRoutes.get(route).map(ShellRoutes.Route::title).orElseGet(() -> switch (route) {
            case "t-history" -> "Trade history";
            case "sys-status" -> "System status";
            default -> "Page unavailable";
        });
        return Presentation.text(original);
    }

    private Node demoBar() {
        Label notice = new Label(Strings.get("demo.offlineNotice"));
        notice.getStyleClass().add("byx-secondary");
        notice.setMinWidth(0);
        HBox.setHgrow(notice, Priority.ALWAYS);
        ComboBox<String> watch = new ComboBox<>();
        watch.getStyleClass().add("byx-language");
        watch.getItems().setAll("ETHUSDT", "BTCUSDT");
        watch.setValue(symbol);
        watch.setId("demo-watchlist");
        watch.setAccessibleText(Strings.get("demo.marketPicker"));
        watch.valueProperty().addListener((o,a,b) -> {
            if (b == null || b.equals(symbol)) return;
            if (!Set.of("ETHUSDT", "BTCUSDT").contains(b)) throw new IllegalArgumentException("Unknown demo market");
            symbol = b;
            String route = router.route();
            // Only market views are replaced; Settings drafts and their save bar survive a ticker change.
            for (String r : List.copyOf(views.keySet())) if (Set.of("t-home", "t-desk", "t-markets", "t-portfolio", "t-positions", "t-history", "t-performance").contains(r)) {
                View v = views.remove(r); v.dispose(); transitions.dispose(v.node()); shell.v2Content().getChildren().remove(v.node());
                if (current == v) current = null;
            }
            if (route != null) display(route);
            shell.refreshCrumb();
        });
        ByxButton logout = new ByxButton(Strings.get("demo.logout"), ByxButton.Variant.GHOST, motion);
        logout.setId("demo-logout");
        logout.setOnAction(e -> showLogin(null));
        HBox bar = new HBox(12, notice, watch, new LanguageSelector(), logout);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("byx-demo-notice");
        return bar;
    }

    void display(String route) {
        if (!permitted(route)) throw new IllegalStateException("DEMO route denied");
        View next = views.computeIfAbsent(route, r -> {
            View view = switch (r) {
                case "t-desk" -> DemoViewFactory.fit(new TradingDesk(motion, () -> DemoFixtures.market(symbol), DemoFixtures.CLOCK), 1100, 650);
                case "t-markets" -> DemoViewFactory.fit(new MarketsPage(() -> DemoFixtures.market(symbol), DemoFixtures.CLOCK, router::request), 1020, 580);
                case "t-settings" -> new SettingsScreen(motion, preferences, router::request, () -> shell.overlay());
                default -> factory.create(r);
            };
            view.node().setVisible(false);
            shell.v2Content().getChildren().add(view.node());
            return view;
        });
        if (current != next) {
            if (current != null) current.onHide();
            current = next;
            current.onSnapshot(new panel.model.Snapshot());
            current.onShow();
        }
        transitions.show(views.values().stream().map(View::node).toList(), current.node(), false);
    }

    private void updateDock() {
        shell.dock().setModel(List.of(
                new StatusDock.Group(Strings.get("demo.dockSystem"), List.of(StatusDock.Item.status("backend",
                        Strings.get("demo.dockOffline"), StatusState.UNAVAILABLE, true, "sys-status", Strings.get("demo.offlineNotice")))),
                new StatusDock.Group(Strings.get("demo.dockMode"), List.of(StatusDock.Item.text("live",
                        Strings.get("demo.dockLive"), "primary", "sys-status", Strings.get("demo.offlineNotice")))),
                new StatusDock.Group(Strings.get("demo.dockEnvironment"), List.of(StatusDock.Item.text("environment",
                        Strings.get("demo.dockLocal"), null, "sys-status", Strings.get("demo.offlineNotice"))))));
    }

    private void notificationDialog() {
        userMenu.close();
        VBox card = new VBox(14, panel.tradeview.Fx.label(Strings.get("demo.notifications"), "byx-section-title"),
                panel.v2.Kit.muted(Strings.get("demo.notificationEmpty")));
        card.getStyleClass().add("byx-dialog");
        ByxButton close = new ByxButton("Close", ByxButton.Variant.SECONDARY, motion);
        card.getChildren().add(close);
        card.setMaxSize(480, Region.USE_PREF_SIZE);
        ByxOverlayHost.DialogHandle[] handle = new ByxOverlayHost.DialogHandle[1];
        close.setOnAction(e -> handle[0].close());
        handle[0] = shell.overlay().openDialog(card, false, close, null);
    }

    void publicHelp(String route) {
        Dialog<Void> dialog = new Dialog<>();
        View view = new DemoViewFactory(motion, () -> DemoFixtures.market(symbol), DemoFixtures.CLOCK,
                next -> { dialog.close(); if (next.startsWith("h-") || next.equals("sys-status")) publicHelp(next);
                          else identifier.input().requestFocus(); }).create(route);
        dialog.initOwner(stage);
        dialog.setTitle(title(route));
        dialog.getDialogPane().setContent(view.node());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefSize(Math.min(780, stage.getWidth() - 80), Math.min(560, stage.getHeight() - 80));
        ByxTheme.apply(dialog.getDialogPane());
        LocaleView localized = new LocaleView(dialog.getDialogPane().getScene());
        localized.bind(dialog.titleProperty());
        dialog.setOnHidden(e -> { localized.close(); view.dispose(); });
        dialog.show();
    }

    private void applyPreferences() {
        motion.preference.set(MotionPreference.valueOf(preferences.value.motion()));
        motion.animatedIcons.set(preferences.value.animatedIcons());
        root.getStyleClass().remove("comfortable");
        if (preferences.value.density().equals("COMFORTABLE")) root.getStyleClass().add("comfortable");
        if (shell != null) shell.content().setComfortable(preferences.value.density().equals("COMFORTABLE"));
    }

    private void persistPresentation() {
        try {
            preferencesStore.save(new DemoPreferencesStore.Values(Strings.language(), ByxTheme.mode(),
                    preferences.value.density(), MotionPreference.valueOf(preferences.value.motion()), preferences.value.animatedIcons()));
        } catch (java.io.IOException | RuntimeException failed) {
            if (shell != null) shell.overlay().toast(ByxOverlayHost.ToastKind.WARNING, Strings.get("demo.savedError"));
        }
    }

    void cleanupViews() {
        if (leaveGuard != null && leaveGuard.dialog().isOpen()) leaveGuard.dialog().close();
        leaveGuard = null;
        for (View view : views.values()) view.dispose();
        views.clear(); current = null; transitions.forget();
        if (palette != null) { palette.close(); palette = null; }
        if (userMenu != null) { userMenu.dispose(); userMenu = null; }
        if (shell != null) { shell.dispose(); shell = null; }
        if (login != null) { login.brand().dispose(); login = null; }
    }

    void cleanup() {
        if (closed) return;
        closed = true;
        cleanupViews(); auth.logout(); motion.setActive(false);
        if (languageListener != null) Strings.languageProperty().removeListener(languageListener);
        if (themeListener != null) ByxTheme.modeProperty().removeListener(themeListener);
        if (localeView != null) localeView.close();
    }
    @Override public void stop() { cleanup(); }

    final class Preferences implements AccountData {
        AccountData.Prefs value;
        public Prefs prefs() { return value; }
        public boolean adminSession() { return false; }
        public boolean preferencesEditable() { return true; }
        public String effectiveMotion() { return motion.preference.get().name(); }
        public void savePrefs(Prefs next) {
            MotionPreference.valueOf(next.motion());
            if (!Set.of("COMPACT", "COMFORTABLE").contains(next.density()) || !next.primaryWorkspace().equals("TRADING") || next.followSystemMotion())
                throw new IllegalArgumentException("Only supported demo presentation preferences");
            try { preferencesStore.save(new DemoPreferencesStore.Values(Strings.language(), ByxTheme.mode(), next.density(),
                    MotionPreference.valueOf(next.motion()), next.animatedIcons())); }
            catch (java.io.IOException failed) { throw new IllegalStateException("Could not save demo presentation preferences", failed); }
            value = next; applyPreferences();
        }
    }
}