package panel.app;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.text.Font;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.model.DataSource;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
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
import panel.ui.Credits;
import panel.ui.EmptyState;
import panel.ui.Ui;
import panel.ui.toast.ToastType;
import panel.ui.View;
import panel.ui.auth.ProfileView;
import panel.ui.auth.TwoFactorView;
import panel.ui.auth.UsersView;
import panel.ui.trader.TraderScreens;
import panel.ui.trader.TradingDeskView;
import panel.user.User;

public class PanelApp extends Application {
    private final AppContext ctx = new AppContext();
    private final Map<String, View> views = new LinkedHashMap<>();
    private final StackPane content = new StackPane();
    private final javafx.animation.Timeline chromeWatch = new Timeline(new KeyFrame(Duration.seconds(1), e -> { if (this.mainActive) { watchAdminSession(); updateStatusDock(ctx.research.snapshot.get()); } }));
    private boolean byxWorkspace;
    private boolean adminChrome;
    private final Map<Boolean, String> lastView = new LinkedHashMap<>();
    /** Telas de entrada V2 (login, setup, troca obrigatória, esqueci a senha): uma instância por entrada. */
    private panel.authview.AuthScreens authScreens;
    private String entryNotice;
    private User mustChangeUser;
    private final StackPane rootStack = new StackPane();
    private final StackPane lockHolder = new StackPane();
    private boolean lockShown;
    /** Shell V2 da sessão atual (um por login); conteúdo legado hospedado num LegacyHost próprio. */
    private panel.shell.ByxShell shell;
    private panel.shell.ShellPalette palette;
    private panel.shell.UserMenu userMenu;
    private panel.shell.NotificationPanel notificationPanel;
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
        rootStack.getStyleClass().add("byx-app");
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
        if (shell != null) shell.content().setComfortable("COMFORTABLE".equals(ctx.settings.density));
    }

    // ---- fluxo de autenticação -------------------------------------------------

    private void showEntry(String message) {
        mainActive = false;
        closeShell();
        if (activeView != null) { activeView.onHide(); activeView = null; }
        router.reset();
        chromeWatch.stop();
        if (expiryWatch != null) {
            expiryWatch.stop();
        }
        stage.setTitle(AppBranding.title("Login"));
        entryNotice = message;
        mustChangeUser = null;
        if (authScreens != null) authScreens.dispose();
        authScreens = new panel.authview.AuthScreens(ctx.motion, authServices(), this::show, this::afterLogin,
                this::afterPasswordChanged, this::showEntry, Credits::show,
                ctx.devOtp != null ? panel.auth.DevOtpProvider.LABEL : null);
        rootStack.getChildren().setAll(authScreens.node());
        show(ctx.auth.firstRun() ? panel.authview.AuthScreens.SETUP : panel.authview.AuthScreens.LOGIN);
    }

    /** Operações reais por trás das telas de entrada. */
    private panel.authview.AuthScreens.Services authServices() {
        return new panel.authview.AuthScreens.Services() {
            @Override public User login(String identifier, char[] password) { return ctx.auth.login(identifier, password); }
            @Override public void createInitialAdmin(String u, String email, char[] pw, String phone) { ctx.userService.createInitialAdmin(u, email, pw, phone); }
            @Override public void changeOwnPassword(long id, char[] current, char[] next) { ctx.userService.changeOwnPassword(id, current, next); }
            @Override public void endSession() { ctx.auth.logout(); }
        };
    }

    private void afterLogin(User user) {
        if (user.mustChangePassword()) {
            mustChangeUser = user;
            show(panel.authview.AuthScreens.CHANGE_PASSWORD);
        } else {
            enterApp(user);
        }
    }

    private void afterPasswordChanged() {
        User fresh = ctx.users.findById(mustChangeUser.id()).orElse(mustChangeUser);
        ctx.sessions.updateUser(fresh);
        enterApp(fresh);
    }

    private void logout(String message) {
        ctx.auth.logout();
        showEntry(message);
    }

    // ---- aplicação principal ---------------------------------------------------

    private void enterApp(User user) {
        if (authScreens != null) {
            authScreens.dispose();
            authScreens = null;
        }
        mustChangeUser = null;
        ctx.byx.start();
        tfOverlay = null;
        views.clear();
        content.getChildren().clear();
        lockShown = false;
        ctx.transitions.forget();
        router.reset();
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

        buildShell(user);
        ctx.navigate = this::show;
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
            toast(ToastType.WARNING, "Admin session expired. Re-authorize to open Research.");
            show(lastView.get(true));
        }
        if (authorized != adminChrome) {
            var settings = views.get("t-settings");
            if (settings != null && settings.node().isVisible()) settings.onSnapshot(ctx.research.snapshot.get());
            chrome(ctx.research.snapshot.get());
        }
    }

    // ---- navegação e autorização ----------------------------------------------

    /** Pedido de navegação (rail, switcher, busca, menu, dock, Views). Só o roteador troca a tela. */
    private void show(String id) {
        router.request(id);
    }

    /** Gate real do roteador: trading/BYX livres na sessão; Research exige sessão de admin verificada. */
    private panel.shell.ShellRouter.Decision evaluateRoute(String id, panel.nav.Navigator.Ticket ticket) {
        if (id.startsWith("auth:")) {
            return authRouteAllowed(id) ? panel.shell.ShellRouter.Decision.ALLOW : panel.shell.ShellRouter.Decision.DENY;
        }
        if (mainActive && ctx.sessions.user().isEmpty()) {
            return panel.shell.ShellRouter.Decision.DENY; // sem sessão nenhuma rota do app abre
        }
        boolean research = !id.startsWith("t-");
        if (!views.containsKey(id)) {
            return research ? requestResearch(id, ticket) : panel.shell.ShellRouter.Decision.DENY;
        }
        if (research) {
            if (!ctx.adminAccess.hasValidAdminSession()) {
                return requestResearch(id, ticket);
            }
            ctx.adminAccess.touch();
        }
        return panel.shell.ShellRouter.Decision.ALLOW;
    }

    private boolean checkingTrustedDevice;
    private final panel.nav.Navigator navigator = new panel.nav.Navigator();
    private final panel.shell.ShellRouter router = new panel.shell.ShellRouter(navigator, this::evaluateRoute, this::display);
    private View activeView;

    private panel.shell.ShellRouter.Decision requestResearch(String target, panel.nav.Navigator.Ticket ticket) {
        String destination = views.containsKey(target) ? target : "overview";
        switch (ctx.adminAccess.evaluate()) {
            case ALREADY_AUTHORIZED -> {
                if (!views.containsKey(target)) {
                    router.complete(ticket, destination); // destino desconhecido: Overview
                    return panel.shell.ShellRouter.Decision.PENDING;
                }
                return panel.shell.ShellRouter.Decision.ALLOW;
            }
            case REQUIRES_2FA -> {
                if (checkingTrustedDevice) return panel.shell.ShellRouter.Decision.PENDING; // o ticket mais recente decide
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
                        panel.nav.Navigator.Ticket latest = router.pending();
                        if (latest == null) return; // o usuário navegou para outro lugar enquanto a verificação rodava
                        String latestDestination = views.containsKey(latest.target()) ? latest.target() : "overview";
                        if (trusted && ctx.adminAccess.hasValidAdminSession()) {
                            updateLock(true);
                            router.complete(latest, latestDestination);
                        } else showTwoFactor(latest, latestDestination);
                    });
                }, "trusted-device-check");
                check.setDaemon(true); check.start();
                return panel.shell.ShellRouter.Decision.PENDING;
            }
            case FORBIDDEN_NOT_ADMIN -> {
                ctx.adminAccess.noteDenied("research workspace requested");
                deny("Access restricted to administrators.");
                return panel.shell.ShellRouter.Decision.DENY;
            }
            case SESSION_EXPIRED -> {
                logout("Session expired. Please sign in again.");
                return panel.shell.ShellRouter.Decision.DENY;
            }
        }
        return panel.shell.ShellRouter.Decision.DENY;
    }

    /** Compatibilidade com os harnesses de QA: verificação para o pedido pendente atual. */
    private void showTwoFactor(String target) {
        panel.nav.Navigator.Ticket t = router.pending();
        if (t != null) showTwoFactor(t, target);
    }

    private void showTwoFactor(panel.nav.Navigator.Ticket ticket, String target) {
        closeTwoFactor();
        TwoFactorView[] ref = new TwoFactorView[1];
        Runnable close = this::closeTwoFactor;
        ref[0] = new TwoFactorView(ctx, () -> {
            close.run();
            updateLock(true);
            router.complete(ticket, views.containsKey(target) ? target : "overview");
        }, () -> {
            close.run();
            router.cancelPending();
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

    /** Negado: a rota não muda, então rail e seletor (derivados dela) já estão certos. */
    private void deny(String message) {
        toast(ToastType.WARNING, message);
    }

    /** Toast na camada 80 do shell; antes do login, no host legado da entrada. */
    private void toast(ToastType type, String message) {
        if (shell == null) {
            return; // antes do login não há o que avisar por toast (as telas de entrada usam banners)
        }
        shell.overlay().toast(switch (type) {
            case SUCCESS -> panel.design.ByxOverlayHost.ToastKind.SUCCESS;
            case WARNING -> panel.design.ByxOverlayHost.ToastKind.WARNING;
            case ERROR -> panel.design.ByxOverlayHost.ToastKind.ERROR;
            default -> panel.design.ByxOverlayHost.ToastKind.INFO;
        }, message);
    }

    /** Cadeado no seletor V2 (fora do LegacyHost: ícone V2, não o AnimatedIcon legado). */
    private final Node lockIcon = panel.design.ByxIcon.of("lock", 13, "t3");

    /** Cadeado no seletor: aparece enquanto Research está bloqueado; ao liberar, abre uma vez e some. */
    private void updateLock(boolean unlockedNow) {
        boolean unlocked = ctx.adminAccess.hasValidAdminSession();
        if (!unlocked && !lockShown) {
            lockHolder.getChildren().setAll(lockIcon);
            lockShown = true;
        } else if (unlocked && lockShown) {
            lockShown = false;
            if (unlockedNow) {
                lockHolder.getChildren().setAll(panel.design.ByxIcon.of("unlock", 13, "pos"));
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

    /** Telas de entrada só sem sessão; setup só no primeiro uso; troca obrigatória só para quem precisa. */
    private boolean authRouteAllowed(String id) {
        if (authScreens == null) return false;
        return switch (id) {
            case panel.authview.AuthScreens.SETUP -> ctx.auth.firstRun();
            case panel.authview.AuthScreens.CHANGE_PASSWORD -> mustChangeUser != null
                    && ctx.sessions.user().filter(u -> u.user().id() == mustChangeUser.id()).isPresent();
            case panel.authview.AuthScreens.LOGIN, panel.authview.AuthScreens.FORGOT -> ctx.sessions.user().isEmpty() && !ctx.auth.firstRun();
            default -> false;
        };
    }

    /** Aplica a View da rota. Chamado só pelo roteador; rail, seletor e breadcrumb seguem a rota sozinhos. */
    private void display(String id) {
        if (id.startsWith("auth:")) {
            authScreens.show(id, entryNotice, mustChangeUser);
            return;
        }
        if (palette != null) palette.close();
        closeTwoFactor();
        boolean toTrader = id.startsWith("t-");
        boolean toByx = java.util.Set.of("t-byx", "t-wallet", "t-benefits", "t-treasury").contains(id);
        boolean changedWorkspace = toTrader != trader || toByx != byxWorkspace;
        byxWorkspace = toByx;
        trader = toTrader;
        lastView.put(toTrader, id);
        stage.setTitle(AppBranding.title(id.equals("t-byx") ? "BYX Network" : toTrader ? "Trading" : "Research"));
        View next = views.get(id);
        next.onSnapshot(ctx.research.snapshot.get());
        ctx.transitions.show(views.values().stream().map(View::node).toList(), next.node(), changedWorkspace);
        if (activeView != next) {
            if (activeView != null) activeView.onHide();
            activeView = next;
            next.onShow();
        }
        chrome(ctx.research.snapshot.get());
    }

    private void render(Snapshot s) {
        views.forEach((id, view) -> {
            if (id.startsWith("t-") || ctx.adminAccess.hasValidAdminSession()) view.onSnapshot(s);
        });
        chrome(s);
    }

    // ---- shell V2 ----------------------------------------------------------------

    private void buildShell(User user) {
        closeShell();
        panel.shell.LegacyHost contentHost = new panel.shell.LegacyHost(content);
        contentHost.setComfortable("COMFORTABLE".equals(ctx.settings.density));
        shell = new panel.shell.ByxShell(router, ctx.motion, contentHost);
        shell.setAvailable(views::containsKey);
        shell.switcher().setBadge(panel.shell.ShellContext.RESEARCH, lockHolder);
        shell.setCrumb(this::crumb);
        shell.topBar().setUser(user.username());
        palette = new panel.shell.ShellPalette(shell.overlay(), this::show, this::paletteIndex);
        shell.setOnOpenSearch(palette::open);
        userMenu = new panel.shell.UserMenu(shell.overlay(), shell.topBar().avatar(), router);
        userMenu.setIdentity(new panel.shell.UserMenu.Identity(user.username(), blankToNull(user.email()),
                user.admin() ? "Admin" : "Trader"));
        notificationPanel = new panel.shell.NotificationPanel(shell.overlay(), shell.topBar().notifications(), ctx.motion);
        userMenu.setItems(userMenuItems());
        rootStack.getChildren().setAll(shell);
    }

    private void closeShell() {
        if (shell != null) {
            shell.dispose();
            shell = null;
            palette = null;
            userMenu = null;
            notificationPanel = null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String crumb(String id) {
        TraderSnapshot t = ctx.trading.snapshot.get();
        if (id.equals("t-desk")) return "Desk / " + (t.symbol == null ? "ETHUSDT" : t.symbol);
        var route = panel.shell.ShellRoutes.get(id);
        // rótulo curto do rail quando existe (Research / Overview), senão o título (Research / Sessions)
        String title = route.map(r -> r.railLabel() != null ? r.railLabel() : r.title()).orElse(id);
        var context = panel.shell.ShellRoutes.contextOf(id);
        return context.workspace ? context.label + " / " + title : title;
    }

    /** Destinos V2 do menu. Sem tela ainda: COMING SOON; Security fica no Profile legado (senha, acesso admin, eventos). */
    private java.util.List<panel.shell.UserMenu.Item> userMenuItems() {
        String k = shell.shortcutPrefix();
        return java.util.List.of(
                panel.shell.UserMenu.Item.route("Profile", "profile", null, "t-profile"),
                panel.shell.UserMenu.Item.route("Security", "security", null, "t-profile"),
                panel.shell.UserMenu.Item.action("Notifications", "bell", null, () -> notificationPanel.open()),
                panel.shell.UserMenu.Item.route("Settings", "settings", k + ",", "t-settings"),
                panel.shell.UserMenu.Item.pending("Keyboard shortcuts", "keyboard", "?", "Arrives in step 11"),
                panel.shell.UserMenu.Item.pending("Help", "help", null, "Arrives in step 11"),
                panel.shell.UserMenu.Item.action("About BYX", "info", null, Credits::show),
                panel.shell.UserMenu.Item.action("Sign out", "logout", null, this::confirmSignOut).asDanger());
    }

    /** Sair sempre pergunta antes (handoff: user menu). */
    private void confirmSignOut() {
        if (shell == null) return;
        shell.overlay().confirm("Sign out", "You will need to sign in again to use BYX-MVP.", "Sign out", true, () -> logout(null));
    }

    /** Índice da busca: navegação com os gates reais (CommandPalette.commands) + comandos reais + ajuda. */
    private java.util.List<panel.shell.ShellPalette.Entry> paletteIndex() {
        boolean admin = ctx.sessions.user().map(u -> u.user().admin()).orElse(false);
        java.util.List<panel.shell.ShellPalette.Entry> out = new java.util.ArrayList<>();
        for (var c : panel.ui.CommandPalette.commands(admin, ctx.adminAccess.hasValidAdminSession())) {
            out.add(c.target() == null
                    ? panel.shell.ShellPalette.Entry.gated(panel.shell.ShellPalette.Group.NAVIGATION, c.title(), c.state())
                    : panel.shell.ShellPalette.Entry.nav(c.title(), c.target(), c.state().isEmpty() ? null : c.state()));
        }
        out.add(panel.shell.ShellPalette.Entry.command("Refresh data", ctx::refresh));
        out.add(panel.shell.ShellPalette.Entry.command("Open notifications", () -> notificationPanel.open()));
        out.add(panel.shell.ShellPalette.Entry.command("Sign out", this::confirmSignOut));
        out.add(new panel.shell.ShellPalette.Entry(panel.shell.ShellPalette.Group.HELP, "About BYX", null, Credits::show, null, null));
        out.add(panel.shell.ShellPalette.Entry.gated(panel.shell.ShellPalette.Group.HELP, "Keyboard shortcuts", "Arrives in step 11"));
        out.add(panel.shell.ShellPalette.Entry.gated(panel.shell.ShellPalette.Group.HELP, "Help and support", "Arrives in step 11"));
        return out;
    }

    /** Dock só com estado real; o que não pode ser lido é UNKNOWN. Igual ao anterior: o dock não toca em nada. */
    private void updateStatusDock(Snapshot s) {
        if (shell == null) return;
        shell.dock().setModel(panel.shell.DockModel.build(s, ctx.trading.snapshot.get(), ctx.byx.snapshot(),
                !"VERIFIED".equals(ctx.byx.snapshot().identity()) ? "Wallet unavailable"
                        : ctx.byxWallets.wallets().isEmpty() ? "Wallet not linked" : "Wallet linked",
                ctx.adminAccess.hasValidAdminSession(), views.containsKey("capture")));
    }

    private void chrome(Snapshot s) {
        if (shell == null) return;
        updateStatusDock(s);
        adminChrome = ctx.adminAccess.hasValidAdminSession();
        TraderSnapshot t = ctx.trading.snapshot.get();
        shell.topBar().setMockData((trader ? t.source : s.source) == DataSource.MOCK);
        shell.topBar().setAdminSession(adminChrome);
        shell.refreshCrumb();
    }

    private void openPalette() {
        if (palette != null) palette.open();
    }
}
