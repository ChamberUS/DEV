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
            Map.entry("t-treasury", "treasury"), Map.entry("t-wallet", "wallet"), Map.entry("t-benefits", "benefits"), Map.entry("t-byx", "network"), Map.entry("t-desk", "dashboard"), Map.entry("t-markets", "chart"), Map.entry("t-bot", "bot"), Map.entry("t-strategies", "settings"),
            Map.entry("t-signals", "signal"), Map.entry("t-portfolio", "dashboard"), Map.entry("t-positions", "positions"), Map.entry("t-orders", "orders"),
            Map.entry("t-performance", "chart"), Map.entry("t-activity", "clock"), Map.entry("t-profile", "user"), Map.entry("t-settings", "settings"),
            Map.entry("overview", "dashboard"), Map.entry("capture", "capture"), Map.entry("sessions", "clock"), Map.entry("dataset", "positions"),
            Map.entry("labels", "orders"), Map.entry("features", "chart"), Map.entry("hypotheses", "hypotheses"), Map.entry("validation", "lock"),
            Map.entry("execution", "chart"), Map.entry("paper", "lock"), Map.entry("live", "lock"), Map.entry("jobs", "refresh"),
            Map.entry("logs", "orders"), Map.entry("users", "user"), Map.entry("settings", "settings"));

    private final AppContext ctx = new AppContext();
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final StackPane content = new StackPane();
    private final HBox footer = new HBox(14);
    private final javafx.animation.Timeline chromeWatch = new Timeline(new KeyFrame(Duration.seconds(1), e -> { if (this.mainActive) { watchAdminSession(); updateStatusDock(ctx.research.snapshot.get()); } }));
    private final VBox byxNav = new VBox(2);
    private final Button byxSwitch = Ui.button("BYX", "ghost");
    private boolean byxWorkspace;
    private boolean adminChrome;
    private final panel.ui.CommandPalette palette = new panel.ui.CommandPalette(this::show);
    private final HBox topBar = new HBox(10);
    private final Button commandSearch = Ui.button("", "ghost");
    private final Label breadcrumb = Ui.label("", "breadcrumb");
    private final Label mockBadge = Ui.badge("MOCK", "warn");
    private final VBox researchNav = new VBox(2);
    private final VBox traderNav = new VBox(2);
    private final VBox brand = new VBox();
    private final ScrollPane navHolder = new ScrollPane();
    private final Map<Boolean, String> lastView = new LinkedHashMap<>();
    private final SegmentedSwitch workspaceSwitch = new SegmentedSwitch(ctx.motion);
    private final StackPane holder = new StackPane();
    private final StackPane rootStack = new StackPane();
    /** Telas e cromo legados (ainda não portados): folhas antigas presas aqui, nunca na cena. */
    private final panel.shell.LegacyHost legacy = new panel.shell.LegacyHost();
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
        for (String f : new String[] {"SchibstedGrotesk-Regular", "SchibstedGrotesk-SemiBold", "Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold", "JetBrainsMono-Regular", "JetBrainsMono-Medium", "JetBrainsMono-SemiBold", "JetBrainsMono-Bold"}) {
            Font.loadFont(getClass().getResourceAsStream("/fonts/" + f + ".ttf"), 13);
        }
        EmptyState.init(ctx.icons);
        Ui.init(ctx.motion);
        panel.ui.Dialogs.init(ctx.motion);
        ctx.refreshDensity = this::applyDensity;
        legacy.getChildren().addAll(holder, ctx.toasts);
        StackPane.setAlignment(ctx.toasts, Pos.BOTTOM_RIGHT);
        rootStack.getStyleClass().add("byx-app");
        rootStack.getChildren().add(legacy);
        ctx.motion.setActive(false);
        Scene scene = new Scene(rootStack, 1440, 900);
        // cena: só o tema V2; as folhas legadas valem apenas dentro de LegacyHost
        panel.design.ByxTheme.apply(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (mainActive && new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.K,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN).match(e)) {
                openPalette(); e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> ctx.adminAccess.touch());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> ctx.adminAccess.touch());
        stage.iconifiedProperty().addListener((o, a, iconified) -> ctx.motion.setActive(stage.isShowing() && !iconified));
        stage.showingProperty().addListener((o, a, showing) -> ctx.motion.setActive(showing && !stage.isIconified()));
        ctx.motion.reference.bind(rootStack);
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        applyDensity();
        showEntry(null);
        stage.show();
        Platform.runLater(() -> runtimeDiagnostics(scene));
    }

    private void runtimeDiagnostics(Scene scene) {
        StringBuilder diagnostic = new StringBuilder("BYX_RUNTIME classes=").append(PanelApp.class.getProtectionDomain().getCodeSource().getLocation())
                .append(" java=").append(System.getProperty("java.version"))
                .append(" javafx=").append(System.getProperty("javafx.version"))
                .append(" scene=").append(scene.getWidth()).append("x").append(scene.getHeight())
                .append(" scale=").append(stage.getOutputScaleX()).append("x").append(stage.getOutputScaleY())
                .append(" motion=").append(ctx.motion.preference.get()).append(" density=").append(ctx.settings.density)
                .append(" motionSource=").append(java.nio.file.Files.exists(java.nio.file.Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "settings.properties")) ? "persisted-settings" : "default");
        for (String css : scene.getStylesheets()) {
            try (var input = java.net.URI.create(css).toURL().openStream()) {
                diagnostic.append(" css=").append(css).append(" sha256=")
                        .append(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
                diagnostic.append(" cssReadError=").append(error.getClass().getSimpleName());
            }
        }
        scene.getRoot().applyCss();
        scene.getRoot().lookupAll(".label").stream().filter(n -> n instanceof Label).findFirst()
                .ifPresent(n -> diagnostic.append(" font=").append(((Label)n).getFont()));
        System.out.println(diagnostic);
    }

    @Override
    public void stop() { ctx.research.close(); ctx.captureMonitor.close(); ctx.byx.close(); ctx.byxBenefits.close(); }

    private void applyDensity() {
        legacy.setComfortable("COMFORTABLE".equals(ctx.settings.density));
    }

    // ---- fluxo de autenticação -------------------------------------------------

    private void showEntry(String message) {
        mainActive = false;
        palette.close();
        if (activeView != null) { activeView.onHide(); activeView = null; }
        navigator.reset();
        chromeWatch.stop();
        if (expiryWatch != null) {
            expiryWatch.stop();
        }
        legacy.setContext("trader");
        stage.setTitle(AppBranding.title("Login"));
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
        ctx.byx.start();
        tfOverlay = null;
        views.clear();
        navButtons.clear();
        content.getChildren().clear();
        researchNav.getChildren().clear();
        traderNav.getChildren().clear();
        byxNav.getChildren().clear();
        navWrappers.clear();
        indicators.clear();
        navStacks.clear();
        indicatorPlaced = false;
        lockShown = false;
        ctx.transitions.forget();
        navigator.reset();
        if (activeView != null) { activeView.onHide(); activeView = null; }

        views.put("t-desk", new TradingDeskView(ctx));
        views.put("t-byx", new panel.ui.ByxNetworkView(ctx));
        views.put("t-wallet", new panel.ui.ByxWalletView(ctx));
        views.put("t-benefits", new panel.ui.ByxBenefitsView(ctx));
        views.put("t-treasury", new panel.ui.ByxTreasuryView(ctx));
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
        VBox right = new VBox(topBar, content, footer);
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
        chromeWatch.setCycleCount(Timeline.INDEFINITE);
        chromeWatch.play();
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
        ctx.adminAccess.expireIfNeeded();
        boolean authorized = ctx.adminAccess.hasValidAdminSession();
        updateLock(false);
        if (!authorized && !trader) {
            ctx.toasts.show(ToastType.WARNING, "Admin session expired. Re-authorize to open Research.");
            show(lastView.get(true));
        }
        if (authorized != adminChrome) {
            var settings = views.get("t-settings");
            if (settings != null && settings.node().isVisible()) settings.onSnapshot(ctx.research.snapshot.get());
            chrome(ctx.research.snapshot.get());
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

    private boolean checkingTrustedDevice;
    private final panel.nav.Navigator navigator = new panel.nav.Navigator();
    private View activeView;
    private String dockSignature = "";

    private void requestResearch(String target) {
        navigator.begin(target);
        switch (ctx.adminAccess.evaluate()) {
            case ALREADY_AUTHORIZED -> display(views.containsKey(target) ? target : "overview");
            case REQUIRES_2FA -> {
                if (checkingTrustedDevice) return; // verificação em andamento; o ticket mais recente decide o destino
                checkingTrustedDevice = true;
                var sessionId = ctx.sessions.user().orElseThrow().id();
                Thread check = new Thread(() -> {
                    boolean result;
                    try { result = ctx.adminAccess.tryTrustedDevice(); }
                    catch (RuntimeException e) { result = false; }
                    boolean trusted = result;
                    javafx.application.Platform.runLater(() -> {
                        checkingTrustedDevice = false;
                        if (ctx.sessions.user().filter(u -> u.id().equals(sessionId)).isEmpty()) return;
                        panel.nav.Navigator.Ticket latest = navigator.consumePending();
                        if (latest == null) return; // o usuário navegou para outro lugar enquanto a verificação rodava
                        String destination = latest.target();
                        if (trusted && ctx.adminAccess.hasValidAdminSession()) {
                            updateLock(true); display(views.containsKey(destination) ? destination : "overview");
                        } else showTwoFactor(destination);
                    });
                }, "trusted-device-check");
                check.setDaemon(true); check.start();
            }
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
            navigator.cancelPending();
            syncTabs();
        });
        tfOverlay = new StackPane(ref[0].node());
        tfOverlay.getStyleClass().add("page");
        content.getChildren().add(tfOverlay);
    }

    private void closeTwoFactor() {
        if (tfOverlay != null) {
            ctx.adminAccess.cancelChallenge();
            content.getChildren().remove(tfOverlay);
            tfOverlay = null;
        }
    }

    private void deny(String message) {
        ctx.toasts.show(ToastType.WARNING, message);
        syncTabs();
    }

    private void syncTabs() {
        workspaceSwitch.select(trader && !byxWorkspace);
        workspaceSwitch.left.getStyleClass().remove("selected");
        workspaceSwitch.right.getStyleClass().remove("selected");
        byxSwitch.getStyleClass().remove("selected");
        (byxWorkspace ? byxSwitch : trader ? workspaceSwitch.left : workspaceSwitch.right).getStyleClass().add("selected");
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
        palette.close();
        closeTwoFactor();
        navigator.displayed(id);
        boolean toTrader = id.startsWith("t-");
        boolean toByx = java.util.Set.of("t-byx", "t-wallet", "t-benefits", "t-treasury").contains(id);
        boolean changedWorkspace = toTrader != trader || toByx != byxWorkspace;
        byxWorkspace = toByx;
        trader = toTrader;
        lastView.put(toTrader, id);
        navHolder.setContent(navStacks.get(toByx ? byxNav : toTrader ? traderNav : researchNav));
        syncTabs();
        brand.setAccessibleText(AppBranding.NAME + " " + AppBranding.ATTRIBUTION);
        stage.setTitle(AppBranding.title(id.equals("t-byx") ? "BYX Network" : toTrader ? "Trading" : "Research"));
        legacy.setContext(toByx ? "byx" : toTrader ? "trader" : "research");
        View next = views.get(id);
        next.onSnapshot(ctx.research.snapshot.get());
        ctx.transitions.show(views.values().stream().map(View::node).toList(), next.node(), changedWorkspace);
        if (activeView != next) {
            if (activeView != null) activeView.onHide();
            activeView = next;
            next.onShow();
        }
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("selected");
            if (k.equals(id)) {
                b.getStyleClass().add("selected");
            }
        });
        moveIndicator(toByx ? byxNav : toTrader ? traderNav : researchNav, id);
        chrome(ctx.research.snapshot.get());
    }

    private Node sidebar(User user) {
        brand.getChildren().setAll(panel.ui.LedgerMark.create());
        brand.setAlignment(Pos.CENTER);
        brand.getStyleClass().add("rail-brand");
        workspaceSwitch.left.setText("Trading");
        workspaceSwitch.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        workspaceSwitch.right.setText("Research");
        if (byxSwitch.getParent() == null) workspaceSwitch.addOption(byxSwitch);
        workspaceSwitch.right.setVisible(user.admin());
        workspaceSwitch.right.setManaged(user.admin());
        byxSwitch.setOnAction(e -> show("t-byx"));
        byxSwitch.getStyleClass().add("ws-tab");
        workspaceSwitch.right.setGraphic(lockHolder);
        workspaceSwitch.right.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        workspaceSwitch.right.setGraphicTextGap(6);
        workspaceSwitch.right.setTooltip(new javafx.scene.control.Tooltip(user.admin() ? "Research / Admin workspace (requires admin authorization)" : "Restricted to administrators"));
        workspaceSwitch.left.setOnAction(e -> show("t-desk"));
        workspaceSwitch.right.setOnAction(e -> {
            show(lastView.get(false));
            syncTabs();
        });

        item(traderNav, "t-desk", "▦  Trading Desk");
        item(traderNav, "t-markets", "Markets");

        item(byxNav, "t-byx", "BYX Network");
        item(byxNav, "t-wallet", "Wallet");
        item(byxNav, "t-benefits", "Benefits");
        item(byxNav, "t-treasury", "Treasury");
        group(traderNav, "AUTOMATION");
        item(traderNav, "t-bot", "Bot");
        item(traderNav, "t-wallet", "Wallet");
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
        navStack(byxNav);
        BorderPane side = new BorderPane();
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(60);
        side.setMinWidth(60);
        side.setMaxWidth(60);
        navHolder.setFitToWidth(true);
        navHolder.getStyleClass().add("page-scroll");
        side.setTop(brand);
        side.setCenter(navHolder);
        Button settings = Ui.button("Settings", "ghost");
        settings.getStyleClass().add("rail-settings");
        settings.setGraphic(ctx.icons.svg("settings", 20, "muted").node());
        settings.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        settings.setOnAction(e -> show("t-settings"));
        settings.setTooltip(new javafx.scene.control.Tooltip("Settings · ⌘K"));
        side.setBottom(settings);
        footer.getStyleClass().add("status-dock");
        return side;
    }

    private void group(VBox nav, String name) {

    }

    private void item(VBox nav, String id, String text) {
        Button b = new Button(railLabel(id, text));

        b.setAccessibleText(text);
        b.getStyleClass().add("nav-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        b.setGraphicTextGap(3);
        b.setWrapText(false);
        b.setMinWidth(60);
        b.setMaxWidth(60);
        b.setOnAction(e -> show(id));
        AnimatedIcon navIcon = ctx.icons.svg(NAV_ICONS.getOrDefault(id, "dashboard"), 20, "muted");
        b.setGraphic(navIcon.node());
        StackPane wrap = new StackPane(b);
        wrap.setMaxWidth(60);
        nav.setAlignment(Pos.TOP_CENTER);
        nav.setSpacing(6);
        ctx.motion.reference.tooltip(b, wrap, text.replaceAll("^[^\\p{L}]+", ""));
        navButtons.put(id, b);
        navWrappers.put(id, wrap);
        boolean primary = nav == traderNav ? java.util.Set.of("t-desk", "t-markets", "t-bot", "t-wallet", "t-orders").contains(id)
                : nav == byxNav || java.util.Set.of("overview", "capture", "labels", "features", "hypotheses").contains(id);
        if (primary) nav.getChildren().add(wrap);
    }

    private void navStack(VBox nav) {
        Region ind = new Region();
        ind.getStyleClass().add("nav-indicator");
        ind.setPrefSize(2, 52);
        ind.setMaxSize(2, 52);
        ind.setTranslateX(4);
        ind.setMouseTransparent(true);
        StackPane st = new StackPane(nav, ind);
        StackPane.setAlignment(ind, Pos.TOP_LEFT);
        indicators.put(nav, ind);
        navStacks.put(nav, st);
    }

    private void moveIndicator(VBox nav, String id) {
        Region ind = indicators.get(nav);
        StackPane wrap = navWrappers.get(id);
        if (ind == null || wrap == null || !nav.getChildren().contains(wrap)) {
            return;
        }
        Platform.runLater(() -> {
            navHolder.applyCss();
            navHolder.layout();
            double y = wrap.getBoundsInParent().getMinY() + (wrap.getHeight() - 52) / 2;
            if (!indicatorPlaced || wrap.getHeight() == 0) {
                ind.setTranslateY(y);
                indicatorPlaced = wrap.getHeight() > 0;
            } else {
                ind.setTranslateY(y);
            }
        });
    }

    private void render(Snapshot s) {
        views.forEach((id, view) -> {
            if (id.startsWith("t-") || ctx.adminAccess.hasValidAdminSession()) view.onSnapshot(s);
        });
        chrome(s);
    }

    private void updateStatusDock(Snapshot s) {
        TraderSnapshot t = ctx.trading.snapshot.get();
        var network = ctx.byx.snapshot();
        String[][] items = {
            {"SYSTEM HEALTH", "dock-group"},
            {"Backend · " + (s.backendOnline ? "Online" : "Offline"), s.backendOnline ? "status-ok" : "status-bad"},
            {"Feed · " + Fmt.text(t.feed), "muted"},
            {"Capture · " + Fmt.text(s.capture.recorder()), "muted"},
            {"Network · " + network.connection(), "muted"},
            {"MODE", "dock-group"}, {t.mode + " · " + t.source, "muted"},
            {"Paper locked · Live OFF", "status-bad"}, {"ENVIRONMENT", "dock-group"},
            {network.environment(), "status-byx"},
            {!"VERIFIED".equals(network.identity()) ? "Wallet unavailable" : ctx.byxWallets.wallets().isEmpty() ? "Wallet unlinked" : "Wallet linked", "muted"}};
        // só reconstrói o dock quando algum texto/estilo muda (evita recriar nós a cada segundo)
        String signature = java.util.Arrays.deepToString(items);
        if (signature.equals(dockSignature) && !footer.getChildren().isEmpty()) return;
        dockSignature = signature;
        footer.setAlignment(Pos.CENTER_LEFT);
        var labels = new java.util.ArrayList<Node>();
        for (String[] item : items) labels.add(Ui.label(item[0], item[1]));
        footer.getChildren().setAll(labels);
    }

    private void chrome(Snapshot s) {
        updateStatusDock(s);
        adminChrome = ctx.adminAccess.hasValidAdminSession();
        TraderSnapshot t = ctx.trading.snapshot.get();
        boolean mock = (trader ? t.source : s.source) == DataSource.MOCK;
        String current = lastView.get(trader);
        String title = navButtons.containsKey(current) ? navButtons.get(current).getAccessibleText() : "Overview";
        if (commandSearch.getGraphic() == null) {
            HBox graphic = new HBox(8, ctx.icons.staticIcon("search", 16, "muted").node(),
                    Ui.label("Search or jump to…", "muted"), Ui.spacer(), Ui.label("⌘K", "muted"));
            graphic.setAlignment(Pos.CENTER_LEFT); graphic.setPrefWidth(276);
            commandSearch.setGraphic(graphic); commandSearch.setGraphicTextGap(0);
            commandSearch.getStyleClass().add("command-search");
            commandSearch.setAccessibleText("Search or jump to · Command K");
            commandSearch.setOnAction(e -> openPalette());
        }
        breadcrumb.setText(current.equals("t-desk") ? "Desk / " + (t.symbol == null ? "ETHUSDT" : t.symbol)
                : (byxWorkspace ? "BYX" : trader ? "Trading" : "Research") + " / " + title.replaceAll("^[^\\p{L}]+", ""));
        mockBadge.setVisible(mock); mockBadge.setManaged(mock);
        userMenu.setText(adminChrome ? "ADMIN SESSION" : "ACCOUNT");
        if (!topBar.getChildren().contains(commandSearch))
            topBar.getChildren().setAll(workspaceSwitch, breadcrumb, Ui.spacer(), commandSearch, mockBadge, userMenu);
    }

    private void openPalette() {
        palette.open(legacy, ctx.sessions.user().map(u -> u.user().admin()).orElse(false),
                ctx.adminAccess.hasValidAdminSession());
    }

    private static String railLabel(String id, String fallback) {
        return switch (id) {
            case "t-desk" -> "Desk";
            case "t-byx" -> "Network";
            case "t-benefits" -> "Benefits";
            case "t-portfolio" -> "Portfolio";
            case "t-performance" -> "Results";
            case "t-strategies" -> "Strategy";
            case "hypotheses" -> "Hypotheses";
            case "validation" -> "Locked";
            default -> fallback.replaceAll("^[^\\p{L}]+", "");
        };
    }

    private void setupTopBar(User u) {
        refreshBtn.setGraphic(refreshIcon.node());
        refreshBtn.setText("Refresh");
        refreshBtn.setGraphicTextGap(6);
        refreshBtn.setOnAction(e -> {
            ctx.refresh();
        });
        java.util.List<UserMenu.Item> items = new java.util.ArrayList<>();
        items.add(new UserMenu.Item("Refresh", "refresh", ctx::refresh));
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
