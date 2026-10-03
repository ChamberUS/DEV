package panel.app;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.auth.AuthMethod;
import panel.model.DataSource;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.security.AccessDeniedException;
import panel.ui.CaptureView;
import panel.ui.DatasetView;
import panel.ui.ExecutionView;
import panel.ui.FeaturesView;
import panel.ui.HypothesesView;
import panel.ui.JobsView;
import panel.ui.LabelsView;
import panel.ui.LockedView;
import panel.ui.LogsView;
import panel.ui.OverviewView;
import panel.ui.SessionsView;
import panel.ui.SettingsView;
import panel.motion.MotionTokens;
import panel.motion.icon.AnimatedIcon;
import panel.ui.Credits;
import panel.ui.EmptyState;
import panel.ui.Ui;
import panel.ui.motion.SegmentedSwitch;
import panel.ui.motion.UserMenu;
import panel.ui.toast.ToastType;
import panel.ui.View;
import panel.ui.auth.ChangePasswordView;
import panel.ui.auth.InitialAdminSetupView;
import panel.ui.auth.LoginView;
import panel.ui.auth.ProfileView;
import panel.ui.auth.TwoFactorView;
import panel.ui.auth.UsersView;
import panel.ui.trader.TraderScreens;
import panel.ui.trader.TradingDeskView;
import panel.user.User;
import panel.util.Fmt;

public class PanelApp extends Application {
    private static final Map<String, String> NAV_ICONS = Map.ofEntries(
            Map.entry("t-desk", "dashboard"), Map.entry("t-markets", "chart"), Map.entry("t-bot", "bot"), Map.entry("t-strategies", "settings"),
            Map.entry("t-signals", "signal"), Map.entry("t-portfolio", "dashboard"), Map.entry("t-positions", "positions"), Map.entry("t-orders", "orders"),
            Map.entry("t-performance", "chart"), Map.entry("t-activity", "clock"), Map.entry("t-profile", "user"), Map.entry("t-settings", "settings"),
            Map.entry("overview", "dashboard"), Map.entry("capture", "feed"), Map.entry("sessions", "clock"), Map.entry("dataset", "positions"),
            Map.entry("labels", "orders"), Map.entry("features", "chart"), Map.entry("hypotheses", "search"), Map.entry("validation", "lock"),
            Map.entry("execution", "chart"), Map.entry("paper", "lock"), Map.entry("live", "lock"), Map.entry("jobs", "refresh"),
            Map.entry("logs", "orders"), Map.entry("users", "user"), Map.entry("settings", "settings"));

    private final AppContext ctx = new AppContext();
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final StackPane content = new StackPane();
    private final VBox footer = new VBox(6);
    private final HBox topBar = new HBox(10);
    private final VBox researchNav = new VBox(2);
    private final VBox traderNav = new VBox(2);
    private final VBox brand = new VBox();
    private final ScrollPane navHolder = new ScrollPane();
    private final Map<Boolean, String> lastView = new LinkedHashMap<>();
    private final SegmentedSwitch workspaceSwitch = new SegmentedSwitch(ctx.motion);
    private final StackPane holder = new StackPane();
    private final StackPane rootStack = new StackPane();
    private final Map<String, StackPane> navWrappers = new LinkedHashMap<>();
    private final Map<VBox, Region> indicators = new LinkedHashMap<>();
    private final Map<VBox, StackPane> navStacks = new LinkedHashMap<>();
    private final Button refreshBtn = Ui.button("", "ghost");
    private final AnimatedIcon refreshIcon = ctx.icons.icon("refresh", 16, "text");
    private final UserMenu userMenu = new UserMenu(ctx.motion, ctx.icons);
    private final StackPane lockHolder = new StackPane();
    private boolean lockShown;
    private boolean indicatorPlaced;
    private boolean trader = true;
    private boolean mainActive;
    private boolean researchStarted;
    private boolean listening;
    private Timeline expiryWatch;
    private Stage stage;
    private StackPane tfOverlay;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        for (String f : new String[] {"Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold", "JetBrainsMono-Regular", "JetBrainsMono-Medium", "JetBrainsMono-Bold"}) {
            Font.loadFont(getClass().getResourceAsStream("/fonts/" + f + ".ttf"), 13);
        }
        EmptyState.init(ctx.icons);
        Ui.init(ctx.motion);
        panel.ui.Dialogs.init(ctx.motion);
        ctx.refreshDensity = this::applyDensity;
        rootStack.getChildren().addAll(holder, ctx.toasts);
        StackPane.setAlignment(ctx.toasts, Pos.BOTTOM_RIGHT);
        Scene scene = new Scene(rootStack, 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/panel/panel.css").toExternalForm());
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> ctx.adminAccess.touch());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> ctx.adminAccess.touch());
        stage.iconifiedProperty().addListener((o, a, iconified) -> ctx.motion.setActive(!iconified));
        stage.showingProperty().addListener((o, a, showing) -> ctx.motion.setActive(showing));
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        applyDensity();
        showEntry(null);
        stage.show();
    }

    @Override
    public void stop() { ctx.research.close(); ctx.captureMonitor.close(); }

    private void applyDensity() {
        var cls = rootStack.getStyleClass();
        cls.remove("comfortable");
        if ("COMFORTABLE".equals(ctx.settings.density)) {
            cls.add("comfortable");
        }
    }

    // ---- fluxo de autenticação -------------------------------------------------

    private void showEntry(String message) {
        mainActive = false;
        if (expiryWatch != null) {
            expiryWatch.stop();
        }
        rootStack.getStyleClass().remove("trader");
        rootStack.getStyleClass().add("trader");
        stage.setTitle("MVP Binance");
        if (ctx.auth.firstRun()) {
            holder.getChildren().setAll(new InitialAdminSetupView(ctx, this::showEntry).node());
        } else {
            holder.getChildren().setAll(new LoginView(ctx, message, this::afterLogin).node());
        }
    }

    private void afterLogin(User user) {
        if (user.mustChangePassword()) {
            holder.getChildren().setAll(new ChangePasswordView(ctx, user, () -> {
                User fresh = ctx.users.findById(user.id()).orElse(user);
                ctx.sessions.updateUser(fresh);
                enterApp(fresh);
            }, () -> logout(null)).node());
        } else {
            enterApp(user);
        }
    }

    private void logout(String message) {
        ctx.auth.logout();
        showEntry(message);
    }

    // ---- aplicação principal ---------------------------------------------------

    private void enterApp(User user) {
        tfOverlay = null;
        views.clear();
        navButtons.clear();
        content.getChildren().clear();
        researchNav.getChildren().clear();
        traderNav.getChildren().clear();
        navWrappers.clear();
        indicators.clear();
        navStacks.clear();
        indicatorPlaced = false;
        lockShown = false;
        ctx.transitions.forget();

        views.put("t-desk", new TradingDeskView(ctx));
        views.put("t-markets", new TraderScreens.Markets(ctx));
        views.put("t-bot", new TraderScreens.Bot(ctx));
        views.put("t-strategies", new TraderScreens.Strategies(ctx));
        views.put("t-signals", new TraderScreens.Signals(ctx));
        views.put("t-portfolio", new TraderScreens.Portfolio(ctx));
        views.put("t-positions", new TraderScreens.Positions(ctx));
        views.put("t-orders", new TraderScreens.Orders(ctx));
        views.put("t-performance", new TraderScreens.Performance(ctx));
        views.put("t-activity", new TraderScreens.Activity(ctx));
        views.put("t-settings", new TraderScreens.Settings(ctx));
        views.put("t-profile", new ProfileView(ctx));
        if (user.admin()) {
            registerResearchViews();
        }
        views.values().forEach(v -> {
            v.node().setVisible(false);
            content.getChildren().add(v.node());
        });

        BorderPane root = new BorderPane();
        root.setLeft(sidebar(user));
        VBox right = new VBox(topBar, content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        topBar.getStyleClass().add("topbar");
        topBar.setAlignment(Pos.CENTER_LEFT);
        root.setCenter(right);
        holder.getChildren().setAll(root);

        ctx.navigate = this::show;
        setupTopBar(user);
        updateLock(false);
        if (!listening) {
            listening = true;
            ctx.research.snapshot.addListener((o, a, s) -> {
                if (mainActive) {
                    render(s);
                }
            });
            ctx.jobs.jobs.addListener((javafx.collections.ListChangeListener<Object>) c -> {
                if (mainActive) {
                    render(ctx.research.snapshot.get());
                }
            });
        }
        lastView.put(true, "t-desk");
        lastView.put(false, "overview");
        mainActive = true;
        show("t-desk");
        render(ctx.research.snapshot.get());
        expiryWatch = new Timeline(new KeyFrame(Duration.seconds(10), e -> watchAdminSession()));
        expiryWatch.setCycleCount(Timeline.INDEFINITE);
        expiryWatch.play();
        if (!researchStarted) {
            researchStarted = true;
            ctx.research.start();
        }
    }

    private void registerResearchViews() {
        views.put("overview", new OverviewView(ctx));
        views.put("capture", new CaptureView(ctx));
        views.put("sessions", new SessionsView(ctx));
        views.put("dataset", new DatasetView(ctx));
        views.put("labels", new LabelsView(ctx));
        views.put("features", new FeaturesView(ctx));
        views.put("hypotheses", new HypothesesView(ctx));
        views.put("validation", new LockedView(ctx, "Validation", "Out-of-sample validation of approved hypotheses",
                "Research approved", "Explicit authorization to unlock VALIDATION"));
        views.put("execution", new ExecutionView(ctx));
        views.put("paper", new LockedView(ctx, "Paper / Shadow", "Simulated trading against the live market, no capital",
                "Research approved", "Validation passed", "Execution model approved", "Risk model approved"));
        views.put("live", new LockedView(ctx, "Live", "Real capital operation",
                "Paper / Shadow completed", "Risk model approved", "Explicit human authorization"));
        views.put("jobs", new JobsView(ctx));
        views.put("logs", new LogsView(ctx));
        views.put("users", new UsersView(ctx));
        views.put("settings", new SettingsView(ctx));
    }

    private void watchAdminSession() {
        if (ctx.adminAccess.expireIfNeeded() && !trader) {
            ctx.toasts.show(ToastType.WARNING, "Admin session expired. Re-authorize to open Research.");
            updateLock(false);
            show(lastView.get(true));
        }
    }

    // ---- navegação e autorização ----------------------------------------------

    private void show(String id) {
        boolean research = !id.startsWith("t-");
        if (!views.containsKey(id)) {
            if (research) {
                requestResearch(id);
            }
            return;
        }
        if (research) {
            if (!ctx.adminAccess.hasValidAdminSession()) {
                requestResearch(id);
                return;
            }
            ctx.adminAccess.touch();
        }
        display(id);
    }

    private void requestResearch(String target) {
        switch (ctx.adminAccess.evaluate()) {
            case ALREADY_AUTHORIZED -> display(views.containsKey(target) ? target : "overview");
            case AUTHORIZED_TRUSTED_NETWORK -> {
                try {
                    ctx.adminAccess.grantTrustedNetwork();
                    ctx.toasts.show(ToastType.SUCCESS, "Trusted network detected. Admin access unlocked.");
                    updateLock(true);
                    display(views.containsKey(target) ? target : "overview");
                } catch (AccessDeniedException e) {
                    deny(e.getMessage());
                }
            }
            case REQUIRES_2FA -> showTwoFactor(target);
            case FORBIDDEN_NOT_ADMIN -> {
                ctx.adminAccess.noteDenied("research workspace requested");
                deny("Access restricted to administrators.");
            }
            case SESSION_EXPIRED -> logout("Session expired. Please sign in again.");
        }
    }

    private void showTwoFactor(String target) {
        closeTwoFactor();
        TwoFactorView[] ref = new TwoFactorView[1];
        Runnable close = this::closeTwoFactor;
        ref[0] = new TwoFactorView(ctx, () -> {
            close.run();
            updateLock(true);
            display(views.containsKey(target) ? target : "overview");
        }, () -> {
            close.run();
            syncTabs();
        });
        tfOverlay = new StackPane(ref[0].node());
        tfOverlay.getStyleClass().add("page");
        content.getChildren().add(tfOverlay);
    }

    private void closeTwoFactor() {
        if (tfOverlay != null) {
            content.getChildren().remove(tfOverlay);
            tfOverlay = null;
        }
    }

    private void deny(String message) {
        ctx.toasts.show(ToastType.WARNING, message);
        lockIcon.play();
        syncTabs();
    }

    private void syncTabs() {
        workspaceSwitch.select(trader);
    }

    private final AnimatedIcon lockIcon = ctx.icons.icon("lock", 13, "muted");

    /** Cadeado no seletor: aparece enquanto Research está bloqueado; ao liberar, abre uma vez e some. */
    private void updateLock(boolean unlockedNow) {
        boolean unlocked = ctx.adminAccess.hasValidAdminSession();
        if (!unlocked && !lockShown) {
            lockHolder.getChildren().setAll(lockIcon.node());
            lockShown = true;
        } else if (unlocked && lockShown) {
            lockShown = false;
            if (unlockedNow) {
                AnimatedIcon open = ctx.icons.icon("unlock", 13, "ok");
                lockHolder.getChildren().setAll(open.node());
                open.play();
                PauseTransition p = new PauseTransition(javafx.util.Duration.millis(900));
                p.setOnFinished(e -> {
                    if (!lockShown) {
                        lockHolder.getChildren().clear();
                    }
                });
                p.play();
            } else {
                lockHolder.getChildren().clear();
            }
        }
    }

    private void display(String id) {
        closeTwoFactor();
        boolean toTrader = id.startsWith("t-");
        boolean changedWorkspace = toTrader != trader;
        trader = toTrader;
        lastView.put(toTrader, id);
        navHolder.setContent(navStacks.get(toTrader ? traderNav : researchNav));
        syncTabs();
        ((Label) brand.getChildren().get(1)).setText(toTrader ? "Trading Terminal" : "Research Control Center");
        stage.setTitle(toTrader ? "MVP Binance — Trading Terminal" : "MVP Binance — Research Control Center");
        var cls = rootStack.getStyleClass();
        cls.remove("trader");
        if (toTrader) {
            cls.add("trader");
        }
        ctx.transitions.show(views.values().stream().map(View::node).toList(), views.get(id).node(), changedWorkspace);
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("selected");
            if (k.equals(id)) {
                b.getStyleClass().add("selected");
            }
        });
        moveIndicator(toTrader ? traderNav : researchNav, id);
        views.get(id).onSnapshot(ctx.research.snapshot.get());
        chrome(ctx.research.snapshot.get());
    }

    private Node sidebar(User user) {
        brand.getChildren().setAll(Ui.label("MVP Binance", "brand"), Ui.label("", "brand-sub"));
        workspaceSwitch.left.setText("TRADING");
        workspaceSwitch.right.setText(user.admin() ? "RESEARCH" : "ADMIN");
        workspaceSwitch.right.setGraphic(lockHolder);
        workspaceSwitch.right.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        workspaceSwitch.right.setGraphicTextGap(6);
        workspaceSwitch.right.setTooltip(new javafx.scene.control.Tooltip(user.admin() ? "Research / Admin workspace (requires admin authorization)" : "Restricted to administrators"));
        workspaceSwitch.left.setOnAction(e -> show(lastView.get(true)));
        workspaceSwitch.right.setOnAction(e -> {
            show(lastView.get(false));
            syncTabs();
        });
        HBox switcher = new HBox(workspaceSwitch);
        HBox.setHgrow(workspaceSwitch, javafx.scene.layout.Priority.ALWAYS);
        switcher.getStyleClass().add("ws-switch");

        item(traderNav, "t-desk", "▦  Trading Desk");
        item(traderNav, "t-markets", "Markets");
        group(traderNav, "AUTOMATION");
        item(traderNav, "t-bot", "Bot");
        item(traderNav, "t-strategies", "Strategies");
        item(traderNav, "t-signals", "Signals");
        group(traderNav, "PORTFOLIO");
        item(traderNav, "t-portfolio", "Overview");
        item(traderNav, "t-positions", "Positions");
        item(traderNav, "t-orders", "Orders");
        item(traderNav, "t-performance", "Performance");
        group(traderNav, "SYSTEM");
        item(traderNav, "t-activity", "Activity");
        item(traderNav, "t-profile", "Profile");
        item(traderNav, "t-settings", "Settings");

        if (user.admin()) {
            VBox nav = researchNav;
            item(nav, "overview", "◈  Overview");
            group(nav, "DATA");
            item(nav, "capture", "Capture");
            item(nav, "sessions", "Sessions");
            item(nav, "dataset", "Dataset");
            group(nav, "RESEARCH");
            item(nav, "labels", "Labels");
            item(nav, "features", "Features");
            item(nav, "hypotheses", "Hypotheses");
            group(nav, "VALIDATION");
            item(nav, "validation", "🔒  Validation");
            group(nav, "EXECUTION");
            item(nav, "execution", "Simulator");
            item(nav, "paper", "🔒  Paper / Shadow");
            item(nav, "live", "🔒  Live");
            group(nav, "SYSTEM");
            item(nav, "jobs", "Jobs");
            item(nav, "logs", "Logs");
            item(nav, "users", "Users");
            item(nav, "settings", "Settings");
        }
        navStack(traderNav);
        navStack(researchNav);
        BorderPane side = new BorderPane();
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(230);
        side.setMinWidth(230);
        navHolder.setFitToWidth(true);
        navHolder.getStyleClass().add("page-scroll");
        side.setTop(new VBox(brand, switcher));
        side.setCenter(navHolder);
        side.setBottom(footer);
        footer.getStyleClass().add("sidebar-footer");
        return side;
    }

    private void group(VBox nav, String name) {
        nav.getChildren().add(Ui.label(name, "nav-group"));
    }

    private void item(VBox nav, String id, String text) {
        Button b = new Button(text.replaceAll("^[^\\p{L}]+", ""));
        b.getStyleClass().add("nav-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setGraphicTextGap(10);
        b.setOnAction(e -> show(id));
        AnimatedIcon navIcon = ctx.icons.svg(NAV_ICONS.getOrDefault(id, "dashboard"), 16, "muted");
        b.setGraphic(navIcon.node());
        b.hoverProperty().addListener((o, was, now) -> {
            if (now) {
                navIcon.play();
            }
        });
        Region hover = new Region();
        hover.getStyleClass().add("nav-hover");
        hover.setOpacity(0);
        hover.setMouseTransparent(true);
        StackPane wrap = new StackPane(hover, b);
        b.hoverProperty().addListener((o, was, now) -> {
            ctx.motion.fadeTo(hover, now ? 1 : 0, MotionTokens.FAST);
            ctx.motion.shiftTo(b, now ? 2 : 0, 0, MotionTokens.MICRO);
        });
        navButtons.put(id, b);
        navWrappers.put(id, wrap);
        nav.getChildren().add(wrap);
    }

    private void navStack(VBox nav) {
        Region ind = new Region();
        ind.getStyleClass().add("nav-indicator");
        ind.setPrefSize(3, 18);
        ind.setMaxSize(3, 18);
        ind.setMouseTransparent(true);
        StackPane st = new StackPane(nav, ind);
        StackPane.setAlignment(ind, Pos.TOP_LEFT);
        indicators.put(nav, ind);
        navStacks.put(nav, st);
    }

    private void moveIndicator(VBox nav, String id) {
        Region ind = indicators.get(nav);
        StackPane wrap = navWrappers.get(id);
        if (ind == null || wrap == null) {
            return;
        }
        Platform.runLater(() -> {
            navHolder.applyCss();
            navHolder.layout();
            double y = wrap.getBoundsInParent().getMinY() + (wrap.getHeight() - 18) / 2;
            if (!indicatorPlaced || wrap.getHeight() == 0) {
                ind.setTranslateY(y);
                indicatorPlaced = wrap.getHeight() > 0;
            } else {
                ctx.motion.slideYTo(ind, y, MotionTokens.STANDARD);
            }
        });
    }

    private void render(Snapshot s) {
        views.values().forEach(v -> v.onSnapshot(s));
        chrome(s);
    }

    private void chrome(Snapshot s) {
        if (trader) {
            TraderSnapshot t = ctx.trading.snapshot.get();
            footer.getChildren().setAll(row("mode", Ui.badge(t.mode, "info")), row("trading", Ui.badge(t.trading, "bad")),
                    row("account", Ui.label(Fmt.text(t.account), "mono-small")));
        } else {
            footer.getChildren().setAll(
                    row("environment", Ui.badge("TRAIN", "info")),
                    row("backend", Ui.badge(s.backendOnline ? "ONLINE" : "OFFLINE", s.backendOnline ? "ok" : "bad")),
                    row("schema", Ui.label(Fmt.text(s.labelSchema == null ? s.featureSchema : s.labelSchema), "mono-small")));
        }

        boolean mock = s.source == DataSource.MOCK;
        topBar.getChildren().setAll(Ui.label("DATA SOURCE", "card-title"), Ui.badge(s.source.name(), mock ? "warn" : "ok"),
                mock ? Ui.label("Fictional values — not real research data", "warn-text") : Ui.label("", "muted"), Ui.spacer());
        ctx.adminAccess.adminSession().filter(a -> !trader).ifPresent(a ->
                topBar.getChildren().add(Ui.badge("ADMIN SESSION · " + a.method().label, a.method() == AuthMethod.TRUSTED_IPV6 ? "purple" : "info")));
        topBar.getChildren().addAll(Ui.label("Updated " + Fmt.time(s.loadedAt), "muted"), refreshBtn, userMenu);
        topBar.setPadding(new Insets(8, 20, 8, 28));
    }

    private void setupTopBar(User u) {
        refreshBtn.setGraphic(refreshIcon.node());
        refreshBtn.setText("Refresh");
        refreshBtn.setGraphicTextGap(6);
        refreshBtn.setOnAction(e -> {
            refreshIcon.play();
            ctx.refresh();
        });
        java.util.List<UserMenu.Item> items = new java.util.ArrayList<>();
        items.add(new UserMenu.Item("Profile", "user", () -> show("t-profile")));
        items.add(new UserMenu.Item("Security", "shield", () -> show("t-profile")));
        if (u.admin()) {
            items.add(UserMenu.Item.SEPARATOR);
            items.add(new UserMenu.Item("Switch Workspace", "dashboard", () -> show(trader ? lastView.get(false) : lastView.get(true))));
            items.add(new UserMenu.Item("Admin / Research", "settings", () -> show(lastView.get(false))));
        }
        items.add(UserMenu.Item.SEPARATOR);
        items.add(new UserMenu.Item("About / Credits", "info", Credits::show));
        items.add(new UserMenu.Item("Logout", "logout", () -> logout(null)));
        userMenu.setUser(u.username(), u.admin() ? "Administrator" : "Trader", items);
    }

    private HBox row(String k, Node v) {
        HBox h = new HBox(8, Ui.label(k, "muted"), Ui.spacer(), v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
