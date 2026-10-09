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
import panel.ui.DatasetView;
import panel.ui.ExecutionView;
import panel.ui.FeaturesView;
import panel.ui.HypothesesView;
import panel.ui.JobsView;
import panel.ui.LabelsView;
import panel.ui.LockedView;
import panel.ui.LogsView;
import panel.ui.SessionsView;
import panel.ui.SettingsView;
import panel.ui.Credits;
import panel.ui.EmptyState;
import panel.ui.Ui;
import panel.ui.toast.ToastType;
import panel.ui.View;
import panel.ui.auth.UsersView;
import panel.ui.trader.TraderScreens;
import panel.tradeview.TradingDesk;
import panel.user.User;

public class PanelApp extends Application {
    private AppContext ctx;

    /** Ponto de composição: o app normal usa os provedores reais; harnesses de QA sobrescrevem para injetar provedores de teste. */
    protected AppContext createContext() {
        return new AppContext();
    }
    private final Map<String, View> views = new LinkedHashMap<>();
    /** Views já portadas para V2: vivem no host V2 do shell, não no LegacyHost. */
    private static final java.util.Set<String> V2_VIEWS = java.util.Set.of("t-home", "t-desk", "t-markets", "overview", "capture",
            "t-byx", "t-wallet", "t-benefits", "t-treasury", "t-chain-data", "t-mascot-gallery", "t-tx-lab",
            "t-profile", "t-security", "t-sessions", "t-notifications", "t-account-activity", "t-settings",
            "h-faq", "h-help", "h-diagnostics", "h-about", "h-overview", "h-whats-new", "h-terms", "h-privacy", "h-shortcuts",
            "sys-status", "sys-unavailable");
    /** Package B: entry page and welcome state per ACCOUNT, in memory (preference persistence is not authorized in this build). */
    private final panel.homeview.LandingPreference landing = new panel.homeview.LandingPreference();
    private panel.shell.NavigationGuard.Handle leaveGuard;
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
    private Node tfOverlay;

    @Override
    public void init() {
        // Application.init runs on the launcher thread: settings and SQLite never block an FX pulse.
        this.ctx = StartupTrace.time("createContext (AppContext)", this::createContext);
    }

    @Override
    public void start(Stage stage) {
        StartupTrace.mark("PanelApp.start enter");
        StartupTrace.heartbeat();
        this.stage = stage;
        // fontes do tema V2 (login): uma vez, pelo carregador do tema; as do tema legado (Inter, JetBrains Mono Bold) só depois da 1ª imagem
        StartupTrace.time("fonts (V2)", () -> { panel.design.ByxFonts.load(); return null; });
        EmptyState.init(ctx.icons);
        Ui.init(ctx.motion);
        panel.ui.Dialogs.init(ctx.motion);
        ctx.refreshDensity = this::applyDensity;
        rootStack.getStyleClass().add("byx-app");
        ctx.motion.setActive(false);
        Scene scene = new Scene(rootStack, 1440, 900);
        localeView = new panel.i18n.LocaleView(scene);
        localeView.bind(stage.titleProperty());
        // cena: só o tema V2; as folhas legadas valem apenas dentro de LegacyHost
        StartupTrace.time("ByxTheme.apply", () -> { panel.design.ByxTheme.apply(scene); return null; });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> ctx.adminAccess.touch());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> ctx.adminAccess.touch());
        stage.focusedProperty().addListener((o, a, focused) -> { if (focused) ctx.refreshSystemMotion(); });
        ctx.refreshSystemMotion();
        ctx.onMarketData = this::onMarketTick;
        ctx.onLocalService = r -> Platform.runLater(() -> recovery.retryFinished("service", panel.systemview.SystemStatusModel.serviceState(r)));
        stage.iconifiedProperty().addListener((o, a, iconified) -> ctx.motion.setActive(stage.isShowing() && !iconified));
        stage.showingProperty().addListener((o, a, showing) -> ctx.motion.setActive(showing && !stage.isIconified()));
        ctx.motion.reference.bind(rootStack);
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> onUncaught(e)); // só a thread FX; trabalhadores seguem o padrão
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        applyDensity();
        StartupTrace.time("showEntry (login scene)", () -> { showEntry(null); return null; });
        stage.show();
        StartupTrace.mark("stage.show() returned");
        Platform.runLater(() -> {
            StartupTrace.mark("first pulse after show");
            StartupTrace.time("fonts (legacy theme)", () -> {
                for (String f : new String[] {"Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold", "JetBrainsMono-Bold"}) {
                    Font.loadFont(getClass().getResourceAsStream("/fonts/" + f + ".ttf"), 13);
                }
                return null;
            });
            runtimeDiagnostics(scene);
        });
    }

    private void runtimeDiagnostics(Scene scene) {
        if (!Boolean.getBoolean("byx.runtime.diagnostics")) return;
        String ui = "BYX_RUNTIME classes=" + PanelApp.class.getProtectionDomain().getCodeSource().getLocation()
                + " java=" + System.getProperty("java.version") + " javafx=" + System.getProperty("javafx.version")
                + " scene=" + scene.getWidth() + "x" + scene.getHeight()
                + " scale=" + stage.getOutputScaleX() + "x" + stage.getOutputScaleY()
                + " motion=" + ctx.motion.preference.get() + " density=" + ctx.settings.density;
        var cssFiles = java.util.List.copyOf(scene.getStylesheets());
        var home = java.nio.file.Path.of(System.getProperty("user.home"));
        scene.getRoot().applyCss();
        String font = scene.getRoot().lookupAll(".label").stream().filter(n -> n instanceof Label).findFirst()
                .map(n -> " font=" + ((Label)n).getFont()).orElse("");
        Thread worker = new Thread(() -> {
            StringBuilder diagnostic = new StringBuilder(ui).append(" motionSource=")
                    .append(java.nio.file.Files.exists(home.resolve(".mvp-binance-panel/settings.properties")) ? "persisted-settings" : "default");
            for (String css : cssFiles) {
                try (var input = java.net.URI.create(css).toURL().openStream()) {
                    diagnostic.append(" css=").append(css).append(" sha256=")
                            .append(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
                } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
                    diagnostic.append(" cssReadError=").append(error.getClass().getSimpleName());
                }
            }
            System.out.println(diagnostic.append(font));
        }, "runtime-diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        if (localeView != null) localeView.close();
        mainActive = false;
        router.reset();
        chromeWatch.stop();
        if (expiryWatch != null) expiryWatch.stop();
        closeTwoFactor();
        if (authScreens != null) authScreens.dispose();
        disposePublic();
        views.values().forEach(View::dispose);
        views.clear();
        closeShell();
        twoFactorWorker.shutdownNow();
        ctx.auth.close();
        ctx.closePresentation();
        ctx.adminAccess.close();
        ctx.market.stop(); ctx.localService.stop(); ctx.research.close(); ctx.captureMonitor.close();
        ctx.scientificCapture.close(); ctx.byx.close(); ctx.byxBenefits.close();
    }

    private void applyDensity() {
        if (shell != null) shell.content().setComfortable("COMFORTABLE".equals(ctx.settings.density));
    }

    // ---- fluxo de autenticação -------------------------------------------------

    private void showEntry(String message) {
        if (localeOwner != null) { panel.i18n.Strings.resetSession(); panel.design.ByxTheme.resetSession(); localeOwner = null; }
        signingOut = false;
        rootStack.setDisable(false);
        researchCheckGeneration++;
        authServiceGeneration++;
        checkingTrustedDevice = false;
        mainActive = false;
        ctx.market.stop(); // logout/troca de usuário cancela a assinatura de mercado
        ctx.localService.stop();
        ctx.scientificCapture.stop();
        closeTwoFactor();
        views.values().forEach(View::dispose);
        views.clear();
        closeShell();
        activeView = null;
        router.reset();
        chromeWatch.stop();
        if (expiryWatch != null) {
            expiryWatch.stop();
        }
        stage.setTitle(AppBranding.title("Login"));
        entryNotice = message;
        mustChangeUser = null;
        if (authScreens != null) authScreens.dispose();
        disposePublic();
        authScreens = StartupTrace.time("AuthScreens construct", () -> new panel.authview.AuthScreens(ctx.motion, authServices(), this::show, this::afterLogin,
                this::afterPasswordChanged, this::showEntry, this::show,
                ctx.developmentLabel));
        rootStack.getChildren().setAll(authScreens.node());
        startAuthService(authScreens); // a autenticação depende do serviço do próprio bundle: inicia se preciso, FORA da thread FX
        lastDisplayed = null;
        previousRoute = null;
        recovery.reset();
        boolean noAccounts = ctx.auth.firstRun();
        var situation = panel.systemview.FirstRunModel.resolve(noAccounts, ctx.settings.onboardingCompleted, false, message != null && message.toLowerCase().contains("expired"));
        StartupTrace.time("show(LOGIN)", () -> { show(!noAccounts ? panel.authview.AuthScreens.LOGIN : panel.systemview.FirstRunModel.showsWelcome(situation)
                ? panel.authview.AuthScreens.WELCOME : panel.authview.AuthScreens.SETUP); return null; });
    }

    /**
     * Garante o serviço local numa thread própria (até 25 s, limite do ServiceLauncher) e informa o login: "Starting local service…" →
     * "Sign in" ou "Retry". Só apresenta; a autenticação continua passando pelo mesmo canal verificado. Resultado de um login já
     * descartado (logout/troca de tela) é ignorado.
     */
    private long authServiceGeneration;

    private void startAuthService(panel.authview.AuthScreens screens) {
        if (!ctx.authority.launchesBundledService()) {
            return; // autoridade injetada (teste/QA): não há serviço do bundle para subir, o login não é bloqueado
        }
        long readinessTicket = ++authServiceGeneration;
        screens.setServiceReadiness(panel.authview.AuthScreens.ServiceReadiness.STARTING, () -> startAuthService(screens));
        Thread launcher = new Thread(() -> {
            boolean ok;
            try {
                ok = ctx.authority.ensureService();
            } catch (RuntimeException e) {
                ok = false;
            }
            boolean ready = ok;
            Platform.runLater(() -> {
                if (authScreens == screens && readinessTicket == authServiceGeneration) {
                    StartupTrace.mark("auth service " + (ready ? "READY" : "UNAVAILABLE"));
                    screens.setServiceReadiness(ready ? panel.authview.AuthScreens.ServiceReadiness.READY
                            : panel.authview.AuthScreens.ServiceReadiness.UNAVAILABLE, () -> startAuthService(screens));
                }
            });
        }, "service-launcher");
        launcher.setDaemon(true);
        launcher.start();
    }

    /** Operações reais por trás das telas de entrada. */
    private panel.authview.AuthScreens.Services authServices() {
        return new panel.authview.AuthScreens.Services() {
            @Override public panel.auth.AuthenticationRequest beginLogin() { return ctx.auth.beginLogin(); }
            @Override public void createInitialAdmin(String u, String email, char[] pw, String phone) { ctx.userService.createInitialAdmin(u, email, pw, phone); }
            @Override public panel.auth.SessionOperation preparePasswordChange(long id, char[] current, char[] next) {
                var scope = ctx.auth.captureSession();
                return new panel.auth.SessionOperation() {
                    @Override public void run() { ctx.userService.changeOwnPassword(scope, id, current, next); }
                    @Override public boolean deliver(Runnable callback) { return scope.present(callback); }
                };
            }
            @Override public panel.auth.SessionOperation prepareLogout() { return ctx.auth.prepareLogout(); }
        };
    }

    /** Rota no instante em que a sessão expirou; consumida no próximo login. */
    private panel.authview.SessionReturn pendingReturn;
    private boolean sessionExpiredShown;

    /**
     * Sessão do usuário sumiu com o app aberto (P3.11): diálogo persistente (camada 70) com uma ação; Esc e fundo
     * não fecham. O alvo de retorno é a rota deste instante. Nenhum timer navega enquanto o diálogo está aberto.
     */
    private void sessionExpired() {
        if (sessionExpiredShown || shell == null) return;
        sessionExpiredShown = true;
        closeTwoFactor();
        long userId = activeUserId;
        pendingReturn = panel.authview.SessionReturn.capture(router.route(), userId);
        router.cancelPending();
        javafx.scene.control.Label title = new javafx.scene.control.Label("Session expired");
        title.getStyleClass().add("byx-section-title");
        javafx.scene.control.Label body = new javafx.scene.control.Label("Your session ended. Sign in again to continue where you were.");
        body.getStyleClass().addAll("byx-body", "byx-secondary");
        body.setWrapText(true);
        panel.design.ByxButton again = new panel.design.ByxButton("Sign in again", panel.design.ByxButton.Variant.PRIMARY, ctx.motion);
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(12, title, body, again);
        card.getStyleClass().add("byx-dialog");
        card.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        card.setAccessibleText("Session expired");
        again.setOnAction(e -> {
            sessionExpiredShown = false;
            showEntry("Your session expired. Sign in again to continue.");
        });
        shell.overlay().openDialog(card, true, again, null);
    }

    private long activeUserId = -1;

    private void afterLogin(User user) {
        if (user.mustChangePassword()) {
            mustChangeUser = user;
            show(panel.authview.AuthScreens.CHANGE_PASSWORD);
        } else {
            enterApp(user);
        }
    }

    private void afterPasswordChanged() {
        var screens = authScreens;
        var scope = ctx.auth.captureSession();
        Thread refresh = new Thread(() -> {
            User user;
            try { user = ctx.auth.refreshUser(scope).orElse(null); } catch (RuntimeException e) { user = null; }
            User fresh = user;
            Platform.runLater(() -> {
                if (authScreens != screens || !scope.isLatestOutcome()) return;
                if (fresh == null) showEntry("Session expired. Please sign in again.");
                else enterApp(fresh);
            });
        }, "password-session-readback");
        refresh.setDaemon(true);
        refresh.start();
    }

    private panel.i18n.LocaleView localeView;
    private Long localeOwner;
    private boolean signingOut;

    private void logout(String message) {
        if (signingOut) return;
        signingOut = true;
        toast(ToastType.INFO, "Signing out…");
        panel.shell.avatar.Operations operations = ops();
        panel.shell.avatar.Operations.Token logoutOp = operations.begin("auth.logout");
        mainActive = false;
        closeTwoFactor();
        rootStack.setDisable(true);
        var release = ctx.auth.prepareLogout();
        Thread worker = new Thread(() -> {
            try { release.run(); } catch (RuntimeException unavailable) { /* AuthService clears presentation in finally. */ }
            Platform.runLater(() -> {
                boolean delivered = release.deliver(() -> {
                    operations.end(logoutOp, true, null);
                    signingOut = false;
                    rootStack.setDisable(false);
                    showEntry(message);
                });
                if (!delivered) operations.end(logoutOp, true, "SUPERSEDED"); // a newer session owns the UI: this one only stops claiming to be busy
            });
        }, "authority-sign-out");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- aplicação principal ---------------------------------------------------

    private void enterApp(User user) {
        if (localeOwner != null && localeOwner.longValue() != user.id()) { panel.i18n.Strings.resetSession(); panel.design.ByxTheme.resetSession(); }
        localeOwner = user.id();
        signingOut = false;
        rootStack.setDisable(false);
        researchCheckGeneration++;
        checkingTrustedDevice = false;
        activeUserId = user.id();
        sessionExpiredShown = false;
        if (authScreens != null) {
            authScreens.dispose();
            authScreens = null;
        }
        mustChangeUser = null;
        disposePublic();
        ctx.byx.start();
        tfOverlay = null;
        views.clear();
        content.getChildren().clear();
        lockShown = false;
        ctx.transitions.forget();
        router.reset();
        if (activeView != null) { activeView.onHide(); activeView = null; }

        views.put("t-home", new panel.homeview.HomeScreen(ctx.motion, java.time.Clock.systemUTC(), homeData(user), this::show));
        views.put("t-desk", new TradingDesk(ctx.motion, ctx.trading.snapshot::get, java.time.Clock.systemDefaultZone()));
        panel.byxview.ByxData byxData = new ByxDataAdapter(ctx);
        java.time.Clock clock = java.time.Clock.systemUTC();
        views.put("t-byx", new panel.byxview.NetworkScreen(ctx.motion, clock, byxData));
        views.put("t-wallet", new panel.byxview.WalletScreen(ctx.motion, clock, byxData, this::show));
        views.put("t-benefits", new panel.byxview.BenefitsScreen(clock, byxData, null, null, this::ops, ctx.motion));
        views.put("t-treasury", new panel.byxview.TreasuryScreen(byxData));
        views.put("t-mascot-gallery", new panel.mascot.MascotGallery(ctx.motion, panel.mascot.MascotAssets.shared())); // vazio até ser aberto (lazy); só aparece na palette em LOCAL_QA
        if (qaBuild()) { // Transaction Lab (sintético): só em LOCAL_QA; no build DEFAULT a tela nem é construída e o serviço responde TX_DISABLED a tudo
            TxLabBuild.register(views, ctx);
        }
        views.put("t-chain-data", new panel.byxview.ChainDataScreen(ctx.motion, clock, new panel.localservice.ModuleReadClient(new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome())), byxData));
        // LEGACY / NO V2 REFERENCE: vincular e revogar a posse (prova externa); o V2 de BYX é somente leitura
        views.put("t-wallet-verify", new panel.ui.ByxWalletView(ctx));
        views.put("t-markets", new panel.tradeview.MarketsPage(ctx.trading.snapshot::get, java.time.Clock.systemDefaultZone(), this::show));
        views.put("t-bot", new TraderScreens.Bot(ctx));
        views.put("t-strategies", new TraderScreens.Strategies(ctx));
        views.put("t-signals", new TraderScreens.Signals(ctx));
        views.put("t-portfolio", new TraderScreens.Portfolio(ctx));
        views.put("t-positions", new TraderScreens.Positions(ctx));
        views.put("t-orders", new TraderScreens.Orders(ctx));
        views.put("t-performance", new TraderScreens.Performance(ctx));
        views.put("t-activity", new TraderScreens.Activity(ctx));
        panel.accountview.AccountData accountData = new AccountDataAdapter(ctx);
        java.util.function.Supplier<panel.design.ByxOverlayHost> overlayOf = () -> shell == null ? null : shell.overlay();
        views.put("t-profile", new panel.accountview.ProfileScreen(ctx.motion, accountData, this::show, this::confirmSignOut));
        views.put("t-security", new panel.accountview.SecurityScreen(ctx.motion, clock, accountData, this::show, overlayOf));
        views.put("t-sessions", new panel.accountview.SessionsScreen(ctx.motion, clock, accountData, overlayOf));
        views.put("t-notifications", new panel.accountview.NotificationsScreen(ctx.motion));
        views.put("t-account-activity", new panel.accountview.ActivityScreen(clock, accountData));
        views.put("t-settings", new panel.accountview.SettingsScreen(ctx.motion, accountData, this::show, overlayOf));
        registerHelpViews();
        registerSystemViews();
        if (user.admin()) {
            registerResearchViews();
        }
        views.forEach((id, v) -> {
            v.node().setVisible(false);
            if (!V2_VIEWS.contains(id)) content.getChildren().add(v.node());
        });

        buildShell(user);
        views.forEach((id, v) -> { if (V2_VIEWS.contains(id)) shell.v2Content().getChildren().add(v.node()); });
        ctx.navigate = this::show;
        updateLock(false);
        if (!listening) {
            listening = true;
            ctx.research.snapshot.addListener((o, a, s) -> {
                recovery.retryFinished("backend", panel.shell.DockModel.backend(s));
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
        ctx.localService.start(); // sondagem do serviço local: só leitura de estado, nunca navega
        ctx.scientificCapture.start(); // captura científica real: só leitura, fora da FX, depois do login
        ctx.market.start(); // assinatura tipada do mercado público (ETHUSDT) no serviço local
        // retorno depois de sessão expirada (P3.11): rota capturada na expiração, resolvida para esta sessão
        lastDisplayed = null;
        previousRoute = null;
        String start = pendingReturn == null ? primaryRoute(user)
                : pendingReturn.resolve(user.id(), views::containsKey, panel.shell.ShellRoutes::isResearch); // Research exige verificação
        pendingReturn = null;
        show(start);
        render(ctx.research.snapshot.get());
        // B02: the one-step welcome appears at most once per ACCOUNT per run, and never when this install already completed onboarding.
        if (!landing.welcomeSeen(panel.homeview.LandingPreference.key(user.id()))
                && panel.systemview.FirstRunModel.showsOnboarding(ctx.settings.onboardingCompleted, true)) {
            long welcomeUser = user.id();
            Platform.runLater(() -> { // só se nenhuma outra camada abriu nesse intervalo
                if (mainActive && shell != null && activeUserId == welcomeUser && shell.overlay().openDialogs() == 0 && shell.mainOverlay() == null) openOnboarding(false);
            });
        }
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
        views.put("overview", new panel.researchview.ResearchOverview(ctx.motion, new panel.researchview.ResearchOverview.Source() {
            @Override public panel.model.Snapshot snapshot() { return ctx.research.snapshot.get(); }
            @Override public boolean labelsRunning() { return ctx.research.labelsRunning(); }
            @Override public int failedJobs() { return (int) ctx.jobs.jobs.stream().filter(j -> j.state.get() == panel.model.JobState.FAILED).count(); }
        }, this::show));
        views.put("capture", new panel.researchview.CaptureScreen(ctx.motion, java.time.Clock.systemUTC(), ctx.captureMonitor,
                ctx.adminAccess::hasValidAdminSession, ctx.research.snapshot::get));
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
        if (sessionExpiredShown) return; // diálogo de sessão expirada aberto: nada navega por trás
        if (mainActive && ctx.sessions.user().isEmpty()) {
            sessionExpired();
            return;
        }
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
        if ("sys-onboarding".equals(id)) { // diálogo, não rota
            if (shell != null && shell.overlay().openDialogs() == 0) openOnboarding(true);
            return;
        }
        if (panel.shell.ShellRoutes.HOME.equals(id)) { // AB01: logo, palette, shortcut and cards share one action (no-op when already Home)
            router.requestHome();
            return;
        }
        router.request(id);
    }

    /** Gate real do roteador: trading/BYX livres na sessão; Research exige sessão de admin verificada. */
    private panel.shell.ShellRouter.Decision evaluateRoute(String id, panel.nav.Navigator.Ticket ticket) {
        if (id.startsWith("auth:")) {
            return authRouteAllowed(id) ? panel.shell.ShellRouter.Decision.ALLOW : panel.shell.ShellRouter.Decision.DENY;
        }
        if (!mainActive) {
            // sem sessão só as páginas públicas (lista no roteador): Trading, Research, BYX, Account e Security nunca abrem
            return panel.shell.ShellRoutes.PUBLIC.contains(id) ? panel.shell.ShellRouter.Decision.ALLOW : panel.shell.ShellRouter.Decision.DENY;
        }
        if (ctx.sessions.user().isEmpty()) {
            return panel.shell.ShellRouter.Decision.DENY; // sem sessão nenhuma rota do app abre
        }
        if (activeView != null && shell != null && !id.equals(router.route()) && activeView.hasUnsavedChanges()) {
            confirmLeave(id, ticket);
            return panel.shell.ShellRouter.Decision.PENDING; // a rota só muda depois da confirmação
        }
        boolean research = panel.shell.ShellRoutes.isResearch(id);
        if (!views.containsKey(id)) {
            if (research) return requestResearch(id, ticket);
            unavailableRequested = id; // rota interna que não existe: Page unavailable (sem 404 de web, sem redirecionar sozinho)
            router.complete(ticket, "sys-unavailable");
            return panel.shell.ShellRouter.Decision.PENDING;
        }
        if (research) {
            if (!ctx.adminAccess.hasValidAdminSession()) {
                return requestResearch(id, ticket);
            }
            ctx.adminAccess.touch();
        }
        return panel.shell.ShellRouter.Decision.ALLOW;
    }

    /**
     * Edição não salva (Profile, Settings): aviso de 3 escolhas (Stay here / Discard and go / Save and go), foco inicial em Stay.
     * Stay (ou Esc) mantém a rota e limpa o pedido pendente; nada é descartado em silêncio. Save and go só navega se a página
     * confirmou que salvou (leitura de volta); falha mantém a edição e a página.
     */
    private void confirmLeave(String target, panel.nav.Navigator.Ticket ticket) {
        View leaving = activeView;
        Runnable stay = () -> {
            if (router.pending() == ticket) router.cancelPending();
        };
        if (leaveGuard != null && leaveGuard.dialog().isOpen()) leaveGuard.dialog().close(); // a newer request replaces the older prompt
        leaveGuard = panel.shell.NavigationGuard.open(shell.overlay(), ctx.motion, leaving.unsavedChangeCount(), leaving.canSaveChanges(), stay, () -> {
            if (router.pending() != ticket || activeView != leaving) return;
            leaving.discardChanges();
            show(target);
        }, () -> router.pending() == ticket && activeView == leaving && leaving.saveChanges(), () -> {
            if (router.pending() == ticket && activeView == leaving) show(target);
        });
    }

    private boolean checkingTrustedDevice;
    private long researchCheckGeneration;
    private final panel.nav.Navigator navigator = new panel.nav.Navigator();
    private final panel.shell.ShellRouter router = new panel.shell.ShellRouter(navigator, this::evaluateRoute, this::display);
    private View activeView;

    private panel.shell.ShellRouter.Decision requestResearch(String target, panel.nav.Navigator.Ticket ticket) {
        // Both evaluation and trusted-device checks use IPC. Only presentation runs on FX.
        if (checkingTrustedDevice) return panel.shell.ShellRouter.Decision.PENDING;
        checkingTrustedDevice = true;
        panel.shell.avatar.Operations operations = ops();
        panel.shell.avatar.Operations.Token gateOp = operations.begin("research.verification"); // real IPC below; ended with its real result
        long checkGeneration = researchCheckGeneration;
        var sessionId = ctx.sessions.user().orElseThrow().id();
        var scope = ctx.auth.captureSession();
        Thread check = new Thread(() -> {
            panel.auth.AccessDecision decision;
            boolean trusted = false;
            try {
                decision = ctx.adminAccess.evaluate(scope);
                if (decision == panel.auth.AccessDecision.REQUIRES_2FA) trusted = ctx.adminAccess.tryTrustedDevice(scope);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    operations.end(gateOp, false, "RESEARCH_CHECK_FAILED");
                    if (checkGeneration != researchCheckGeneration) return;
                    checkingTrustedDevice = false;
                    if (mainActive && ctx.sessions.user().filter(u -> u.id().equals(sessionId)).isPresent()
                            && router.pending() != null) researchGateFailed(e);
                });
                return;
            }
            var outcome = decision;
            boolean deviceTrusted = trusted;
            Platform.runLater(() -> {
                operations.end(gateOp, true, null); // the check returned; what it decided is shown by the existing UI, not by the avatar
                if (checkGeneration != researchCheckGeneration) return;
                checkingTrustedDevice = false;
                if (!mainActive || ctx.sessions.user().filter(u -> u.id().equals(sessionId)).isEmpty()) return;
                panel.nav.Navigator.Ticket latest = router.pending();
                if (latest == null || !panel.shell.ShellRoutes.isResearch(latest.target())) return;
                String destination = views.containsKey(latest.target()) ? latest.target() : "overview";
                try {
                    switch (outcome) {
                        case ALREADY_AUTHORIZED -> router.complete(latest, destination);
                        case REQUIRES_2FA -> {
                            if (deviceTrusted && ctx.adminAccess.hasValidAdminSession()) {
                                updateLock(true);
                                router.complete(latest, destination);
                            } else showTwoFactor(latest, destination);
                        }
                        case FORBIDDEN_NOT_ADMIN -> {
                            router.cancelPending();
                            ctx.adminAccess.noteDenied("research workspace requested");
                            deny("Access restricted to administrators.");
                        }
                        case SESSION_EXPIRED -> {
                            router.cancelPending();
                            sessionExpired();
                        }
                    }
                } catch (RuntimeException e) { researchGateFailed(e); }
            });
        }, "trusted-device-check");
        check.setDaemon(true);
        check.start();
        return panel.shell.ShellRouter.Decision.PENDING;
    }

    /** Falha ao abrir a verificação do Research: fail-closed. Código fixo, sem mensagem da exceção (pode ter contato/código); o Research segue bloqueado. */
    private void researchGateFailed(Throwable error) {
        System.err.println("BYX_RESEARCH_GATE_ERROR code=overlay_open_failed type=" + error.getClass().getSimpleName());
        closeTwoFactor();
        router.cancelPending();
        if (shell != null) toast(ToastType.ERROR, "Admin verification could not be opened. Research stays locked.");
    }

    /** Compatibilidade com os harnesses de QA: verificação para o pedido pendente atual. */
    private void showTwoFactor(String target) {
        panel.nav.Navigator.Ticket t = router.pending();
        if (t != null) showTwoFactor(t, target);
    }

    /** Verificação de admin V2 sobre a área principal; concluir só aplica a rota se o ticket ainda for o atual. */
    private void showTwoFactor(panel.nav.Navigator.Ticket ticket, String target) {
        closeTwoFactor();
        if (shell == null) return;
        User user = ctx.sessions.user().orElseThrow().user();
        panel.authview.AdminVerificationView[] ref = new panel.authview.AdminVerificationView[1];
        var scope = ctx.auth.captureSession();
        ref[0] = new panel.authview.AdminVerificationView(ctx.motion, () -> startAdminVerification(scope), twoFactorWorker,
                javafx.application.Platform::runLater, user.maskedEmail(), user.maskedPhone(), ctx.developmentLabel != null, () -> {
                    closeTwoFactor();
                    updateLock(true);
                    router.complete(ticket, views.containsKey(target) ? target : "overview");
                }, () -> {
                    closeTwoFactor();
                    router.cancelPending();
                });
        tfOverlay = ref[0];
        shell.setMainOverlay(ref[0]);
    }

    private final java.util.concurrent.ExecutorService twoFactorWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "two-factor-provider");
        t.setDaemon(true);
        return t;
    });

    /** Abre o desafio real; providers ausentes viram NOT CONFIGURED com o status real de cada um. */
    private panel.authview.AdminVerificationView.Flow startAdminVerification(panel.auth.AuthService.SessionScope scope) {
        TwoFactorFlowAdapter adapter;
        try {
            adapter = new TwoFactorFlowAdapter(ctx.adminAccess.startTwoFactor(scope));
        } catch (panel.auth.TwoFactorNotConfiguredException e) {
            throw new panel.authview.AdminVerificationView.NotConfigured(
                    "Email and SMS providers run inside the local service and are not configured there.\nProvider setup is part of the authority migration (see the migration plan).");
        }
        return adapter;
    }

    private record TwoFactorFlowAdapter(panel.auth.TwoFactorFlow flow) implements panel.authview.AdminVerificationView.Flow {
        @Override public void sendEmailCode() { flow.sendEmailCode(); }
        @Override public panel.auth.TwoFactorResult verifyEmail(String code) { return flow.verifyEmail(code); }
        @Override public void sendSmsCode() { flow.sendSmsCode(); }
        @Override public panel.auth.TwoFactorResult verifySms(String code) { return flow.verifySms(code); }
        @Override public void finish(boolean trust) { flow.finish(trust); }
        @Override public long resendSeconds(boolean phone) { return flow.resendSeconds(phone); }
        @Override public void cancel() { flow.cancel(); }
    }

    private void closeTwoFactor() {
        if (tfOverlay != null) {
            ((panel.authview.AdminVerificationView) tfOverlay).dispose();
            ctx.adminAccess.cancelChallenge();
            if (shell != null) shell.setMainOverlay(null);
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
            case panel.authview.AuthScreens.SETUP, panel.authview.AuthScreens.WELCOME -> ctx.auth.firstRun();
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
        if (!mainActive) {
            showPublic(id);
            return;
        }
        if (palette != null) palette.close();
        if (shell != null) shell.overlay().closePopovers();
        if (leaveGuard != null && leaveGuard.dialog().isOpen()) leaveGuard.dialog().close();
        leaveGuard = null;
        closeTwoFactor();
        if (lastDisplayed != null && !lastDisplayed.equals(id) && !lastDisplayed.equals("sys-unavailable")) previousRoute = lastDisplayed;
        lastDisplayed = id;
        if (id.equals("sys-unavailable")) ((panel.systemview.PageUnavailableScreen) views.get(id)).setRequested(unavailableRequested);
        boolean toTrader = !panel.shell.ShellRoutes.isResearch(id);
        boolean toByx = java.util.Set.of("t-byx", "t-wallet", "t-benefits", "t-treasury", "t-chain-data", "t-mascot-gallery", "t-tx-lab", "t-wallet-verify").contains(id);
        boolean changedWorkspace = toTrader != trader || toByx != byxWorkspace;
        byxWorkspace = toByx;
        trader = toTrader;
        lastView.put(toTrader, id);
        stage.setTitle(AppBranding.title(id.equals("t-home") ? "Home" : id.equals("t-byx") ? "BYX Network" : id.startsWith("h-") ? "Help" : id.startsWith("sys-") ? "System" : toTrader ? "Trading" : "Research"));
        View next = views.get(id);
        next.onSnapshot(ctx.research.snapshot.get());
        ctx.transitions.show(views.values().stream().map(View::node).toList(), next.node(), changedWorkspace);
        if (shell != null) shell.showV2(V2_VIEWS.contains(id));
        if (changedWorkspace && activeView != null && shell != null) shell.workspaceTransition(); // curta, sem esperar: o conteúdo já está pronto
        if (activeView != next) {
            if (activeView != null) activeView.onHide();
            activeView = next;
            next.onShow();
        }
        chrome(ctx.research.snapshot.get());
    }

    private String lastMarketFeed;
    private boolean marketTickPending;

    /** Dado de mercado novo (thread do cliente → FX, coalescido). Só o Trading é tocado; o dock só quando o estado do feed muda. */
    private void onMarketTick() {
        synchronized (this) {
            if (marketTickPending) return;
            marketTickPending = true;
        }
        Platform.runLater(() -> {
            synchronized (this) { marketTickPending = false; }
            if (!mainActive) return;
            Snapshot s = ctx.research.snapshot.get();
            ctx.trading.update(s);
            for (String id : new String[] {"t-home", "t-desk", "t-markets"}) {
                View v = views.get(id);
                if (v != null && v == activeView) v.onSnapshot(s); // fora de vista, o Desk se atualiza ao ser exibido
            }
            String feed = ctx.trading.snapshot.get().feed;
            if (!java.util.Objects.equals(feed, lastMarketFeed)) {
                lastMarketFeed = feed;
                chrome(s); // dock/status refletem a mudança de estado do feed
            }
        });
    }

    private void render(Snapshot s) {
        views.forEach((id, view) -> {
            if (!panel.shell.ShellRoutes.isResearch(id) || ctx.adminAccess.hasValidAdminSession()) view.onSnapshot(s);
        });
        chrome(s);
    }

    // ---- System: status, recuperação, erros, onboarding --------------------------------

    private final panel.systemview.RecoveryTracker recovery = new panel.systemview.RecoveryTracker();
    private String lastDisplayed;
    private String previousRoute;
    private String unavailableRequested;
    private int unexpectedCount;

    private void registerSystemViews() {
        views.put("sys-status", new panel.systemview.SystemStatusScreen(ctx.motion, java.time.Clock.systemUTC(), this::statusInputs, recovery, this::retryService));
        views.put("sys-unavailable", new panel.systemview.PageUnavailableScreen(ctx.motion, () -> previousRoute != null && views.containsKey(previousRoute) ? previousRoute : null,
                this::show, primaryRouteForCurrent()));
    }

    private String primaryRouteForCurrent() {
        return panel.authview.SessionReturn.DEFAULT_ROUTE;
    }

    /**
     * Entry page after sign-in. Precedence (SPEC §1): authorized deep link (resolved by the caller) > this account's choice > an explicit
     * non-default workspace stored by an earlier version > Home. "TRADING" is the legacy default and not read as a choice of the Terminal.
     */
    private String primaryRoute(User user) {
        String account = panel.homeview.LandingPreference.key(user.id());
        if (landing.hasChoice(account)) {
            return landing.route(account, views::containsKey);
        }
        return switch (ctx.settings.primaryWorkspace) {
            case "BYX" -> "t-byx";
            case "RESEARCH" -> user.admin() ? "overview" : panel.shell.ShellRoutes.HOME;
            default -> panel.shell.ShellRoutes.HOME;
        };
    }

    /** Build LOCAL_QA = o serviço tem a chain pública configurada (o perfil é decidido no build do serviço; o painel só observa). */
    private boolean qaBuild() {
        String s = ctx.byx.snapshot().chainState();
        return TxLabBuild.available() && s != null && !"NOT_CONFIGURED".equals(s);
    }

    private panel.systemview.SystemStatusModel.Inputs statusInputs() {
        boolean linked;
        try {
            linked = ctx.byxWallets.wallets().stream().anyMatch(w -> w.validAt(java.time.Instant.now()));
        } catch (RuntimeException unavailable) {
            linked = false;
        }
        return new panel.systemview.SystemStatusModel.Inputs(ctx.research.snapshot.get(), ctx.trading.snapshot.get(), ctx.byx.snapshot(),
                ctx.sessions.user().isPresent(), ctx.adminAccess.hasValidAdminSession(), linked, views.containsKey("overview"), ctx.localService.snapshot(), ctx.scientificCapture.current());
    }

    /** Retry só existe onde há uma nova tentativa REAL: backend (refresh da pesquisa) e nó BYX (leitura da cadeia). */
    private void retryService(String id) {
        switch (id) {
            case "backend" -> {
                recovery.retryStarted("backend");
                ctx.refresh();
            }
            case "service" -> {
                recovery.retryStarted("service");
                ctx.localService.refreshNow();
            }
            case "node" -> {
                recovery.retryStarted("node");
                ctx.byx.refresh().whenComplete((s, e) -> Platform.runLater(() -> recovery.retryFinished("node", panel.shell.DockModel.network(ctx.byx.snapshot().connection()))));
            }
            default -> { }
        }
    }

    /** Recuperação: transições reais viram chip, uma notificação de "restored" e a faixa global do backend. Nada navega. */
    private void updateRecovery() {
        if (shell == null) return;
        java.time.Instant now = java.time.Instant.now();
        for (var c : panel.systemview.SystemStatusModel.components(statusInputs())) {
            var ev = recovery.update(c.id(), c.state(), now);
            if (ev == panel.systemview.RecoveryTracker.Event.RESTORED) {
                shell.overlay().toast(panel.design.ByxOverlayHost.ToastKind.SUCCESS, c.name() + " connection restored.");
            }
        }
        shell.setGlobalBar(recovery.lost("backend") ? panel.systemview.ErrorPatterns.globalBar("Backend connection lost. Values shown are the last known ones.")
                : null);
    }

    /** Exceção não tratada na thread FX: detalhe só no log; a UI mostra o fallback seguro (sem stack trace, token ou caminho). */
    private void onUncaught(Throwable error) {
        System.err.println("UNEXPECTED " + error);
        error.printStackTrace();
        if (shell == null || !mainActive) return;
        if (shell.mainOverlay() != null) {
            toast(ToastType.ERROR, "Something went wrong.");
            return;
        }
        unexpectedCount++;
        String code = panel.systemview.ErrorArchitecture.referenceCode(error, java.time.Instant.now());
        String disabled = unexpectedCount >= 3 ? "the same problem happened again" : null;
        Runnable close = () -> shell.setMainOverlay(null);
        shell.setMainOverlay(new panel.systemview.UnexpectedErrorScreen(ctx.motion, code, disabled,
                () -> { close.run(); String r = router.route(); if (r != null) router.request(r); },
                () -> { close.run(); show("h-diagnostics"); },
                () -> { close.run(); show(panel.authview.SessionReturn.DEFAULT_ROUTE); }));
    }

    /** Onboarding (diálogo persistente): só guarda o workspace de abertura e a conclusão; nada mais é tocado. */
    /** B02 welcome (one step). replay = the user asked for it explicitly (palette/Home), otherwise first use of this account in this run. */
    private void openOnboarding(boolean replay) {
        if (shell == null || !mainActive) return;
        var owner = shell;
        long userId = activeUserId;
        String account = panel.homeview.LandingPreference.key(userId);
        var user = ctx.sessions.user().map(u -> u.user()).orElse(null);
        if (user == null || user.id() != userId) return;
        landing.markWelcomeSeen(account); // marked on display: a quick logout/login in the same run does not show it again
        var net = ctx.byx.snapshot();
        var facts = new panel.homeview.WelcomeDialog.Facts(user.username(), user.admin() ? "Admin" : "Trader",
                !"ENABLED".equalsIgnoreCase(ctx.trading.snapshot.get().trading), net == null ? null : net.environment());
        panel.homeview.WelcomeDialog.open(owner.overlay(), ctx.motion, facts, landing.startFor(account), r -> {
            if (shell != owner || !mainActive || activeUserId != userId) return;
            panel.homeview.LandingPreference.Start before = landing.startFor(account);
            landing.choose(account, r.start());
            if (r.start() != before || replay) toast(ToastType.INFO, "Start page applies until you quit the app.");
            show(r.start() == panel.homeview.LandingPreference.Start.TERMINAL && views.containsKey("t-desk") ? "t-desk" : panel.shell.ShellRoutes.HOME);
        }, () -> { });
    }

    private panel.homeview.HomeScreen.Data homeData(User user) {
        return new panel.homeview.HomeScreen.Data() {
            @Override public String displayName() { return user.username(); }
            @Override public TraderSnapshot trader() { return ctx.trading.snapshot.get(); }
            @Override public panel.model.ByxSnapshot network() { return ctx.byx.snapshot(); }
            @Override public boolean researchVisible() { return views.containsKey("overview"); }
            @Override public boolean walletVerified() {
                return "VERIFIED".equals(ctx.byx.snapshot().identity()) && panel.shell.WalletStatus.empty(() -> ctx.byxWallets.wallets()).isPresent();
            }
            @Override public panel.homeview.HomeMarkets.Catalog catalog() { return panel.homeview.HomeMarkets.productionCatalog(); }
            @Override public void retryCatalog() { /* the supported catalog is a platform constant; nothing to re-request */ }
            @Override public void replayWelcome() { openOnboarding(true); }
        };
    }

    // ---- Help (sessão) e modo público ------------------------------------------------

    private void registerHelpViews() {
        String mod = shell == null ? (panel.shell.ByxShell.isMac() ? "⌘" : "Ctrl") : "Ctrl";
        mod = panel.shell.ByxShell.isMac() ? "⌘" : "Ctrl";
        java.util.function.Consumer<String> copy = text -> {
            var c = new javafx.scene.input.ClipboardContent();
            c.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
        };
        views.put("h-faq", new panel.helpview.FaqScreen(ctx.motion, panel.helpview.HelpContent.faq(), this::show));
        views.put("h-help", new panel.helpview.SupportScreen(ctx.motion, this::show, false));
        views.put("h-diagnostics", new panel.helpview.DiagnosticsScreen(ctx.motion, this::diagnostics, copy));
        views.put("h-about", new panel.helpview.AboutScreen(ctx.motion, this::show, copy, false));
        views.put("h-overview", new panel.helpview.OverviewScreen(ctx.motion, this::show));
        views.put("h-whats-new", new panel.helpview.WhatsNewScreen(panel.helpview.HelpContent.whatsNew()));
        views.put("h-terms", new panel.helpview.LegalScreen("Terms of Use", panel.helpview.HelpContent.legal(), true));
        views.put("h-privacy", new panel.helpview.LegalScreen("Privacy", panel.helpview.HelpContent.legal(), false));
        views.put("h-shortcuts", new panel.helpview.ShortcutsScreen(mod));
    }

    /** Relatório por allow-list: só estados e versões do runtime; nenhuma credencial é lida. */
    private panel.helpview.DiagnosticsReport diagnostics() {
        Snapshot s = ctx.research.snapshot.get();
        TraderSnapshot t = ctx.trading.snapshot.get();
        var net = ctx.byx.snapshot();
        boolean user = ctx.sessions.user().isPresent();
        return new panel.helpview.DiagnosticsReport().set("Application", AppBranding.NAME).set("Version", AppInfo.VERSION).set("Build", AppInfo.build())
                .set("Environment", AppInfo.ENVIRONMENT).set("Java", AppInfo.java()).set("JavaFX", AppInfo.javafx()).set("Operating system", AppInfo.os())
                .set("Backend", panel.shell.DockModel.backend(s).name()).set("Market feed", ((panel.design.StatusState) panel.shell.DockModel.feed(t.feed)[0]).name())
                .set("Capture", ((panel.design.StatusState) panel.shell.DockModel.capture(ctx.scientificCapture.current())[0]).name())
                .set("Research", views.containsKey("overview") ? "Available to this account" : "Not available to this account")
                .set("BYX node", panel.byxview.NetworkModel.state(net).text).set("Local service", ctx.localService.snapshot().summary()).set("Wallet", panel.shell.WalletStatus.diagnostics(net.identity(), () -> ctx.byxWallets.wallets()))
                .set("Authentication", !user ? "Signed out" : ctx.adminAccess.hasValidAdminSession() ? "Signed in · admin session active" : "Signed in")
                .set("Motion mode", ctx.motion.preference.get().name() + (ctx.motionReducedBySystem() ? " (system)" : "")).set("Data source", ctx.settings.dataSource.name()).set("Density", ctx.settings.density);
    }

    private panel.helpview.PublicHost publicHost;

    private void disposePublic() {
        if (publicHost != null) {
            publicHost.dispose();
            publicHost = null;
        }
    }

    /** Página pública antes do login (o roteador já permitiu): About, FAQ, Help, Terms, Privacy, em um host sem rail nem dock. */
    private void showPublic(String id) {
        if (publicHost == null) {
            java.util.function.Consumer<String> copy = text -> {
                var c = new javafx.scene.input.ClipboardContent();
                c.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            };
            java.util.Map<String, View> pages = new java.util.LinkedHashMap<>();
            pages.put("h-about", new panel.helpview.AboutScreen(ctx.motion, this::show, copy, true));
            pages.put("h-faq", new panel.helpview.FaqScreen(ctx.motion, panel.helpview.HelpContent.faq(), this::show));
            pages.put("h-help", new panel.helpview.SupportScreen(ctx.motion, this::show, true));
            var legal = panel.helpview.HelpContent.legal();
            pages.put("h-terms", new panel.helpview.LegalScreen("Terms of Use", legal, true));
            pages.put("h-privacy", new panel.helpview.LegalScreen("Privacy", legal, false));
            publicHost = new panel.helpview.PublicHost(ctx.motion, pages, this::show, () -> showEntry(null));
        }
        if (rootStack.getChildren().size() != 1 || rootStack.getChildren().get(0) != publicHost) {
            rootStack.getChildren().setAll(publicHost);
        }
        stage.setTitle(AppBranding.title("Help"));
        publicHost.show(id);
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
        shell.topBar().mascot().bindMenuOpen(userMenu.openProperty()); // the eyes look at the menu while the existing menu is open
        rootStack.getChildren().setAll(shell);
    }

    /** Real pending operations report here so the header avatar can show them (D-13). Tokens of a previous shell/session are inert. */
    private panel.shell.avatar.Operations ops() {
        return shell == null ? panel.shell.avatar.Operations.NONE : shell.topBar().operations();
    }

    private void closeShell() {
        if (shell != null) {
            if (leaveGuard != null) leaveGuard.dialog().close();
            leaveGuard = null;
            if (userMenu != null) userMenu.dispose();
            if (notificationPanel != null) notificationPanel.dispose();
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
        if (id.equals("t-desk")) return t.symbol == null || t.symbol.isBlank() ? "Desk" : "Desk / " + t.symbol;
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
                panel.shell.UserMenu.Item.route("Security", "security", null, "t-security"),
                panel.shell.UserMenu.Item.action("Notifications", "bell", null, () -> notificationPanel.open()),
                panel.shell.UserMenu.Item.route("Settings", "settings", k + ",", "t-settings"),
                panel.shell.UserMenu.Item.action("Keyboard shortcuts", "keyboard", "?", () -> shell.openShortcuts()),
                panel.shell.UserMenu.Item.route("Help", "help", null, "h-help"),
                panel.shell.UserMenu.Item.route("About BYX", "info", null, "h-about"),
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
        for (var c : panel.ui.CommandPalette.commands(admin, ctx.adminAccess.hasValidAdminSession(), qaBuild())) {
            out.add(c.target() == null
                    ? panel.shell.ShellPalette.Entry.gated(panel.shell.ShellPalette.Group.NAVIGATION, c.title(), c.state())
                    : panel.shell.ShellPalette.Entry.nav(c.title(), c.target(), c.state().isEmpty() ? null : c.state()));
        }
        out.add(panel.shell.ShellPalette.Entry.command("Refresh data", ctx::refresh));
        out.add(panel.shell.ShellPalette.Entry.command("Open notifications", () -> notificationPanel.open()));
        out.add(panel.shell.ShellPalette.Entry.command("Sign out", this::confirmSignOut));
        out.add(panel.shell.ShellPalette.Entry.nav("System Status", "sys-status", null));
        out.add(panel.shell.ShellPalette.Entry.command("Onboarding tour", () -> show("sys-onboarding")));
        for (String[] h : new String[][] {{"About BYX", "h-about"}, {"Help and support", "h-help"}, {"FAQ", "h-faq"}, {"Diagnostics", "h-diagnostics"},
                {"Product overview", "h-overview"}, {"What's new", "h-whats-new"}, {"Terms of Use", "h-terms"}, {"Privacy", "h-privacy"}}) {
            out.add(new panel.shell.ShellPalette.Entry(panel.shell.ShellPalette.Group.HELP, h[0], h[1], null, null, null));
        }
        out.add(new panel.shell.ShellPalette.Entry(panel.shell.ShellPalette.Group.HELP, "Keyboard shortcuts", null, () -> shell.openShortcuts(), null, null));
        var faq = (panel.helpview.FaqScreen) views.get("h-faq");
        if (faq != null) {
            for (var q : panel.helpview.HelpContent.faq().items()) {
                out.add(new panel.shell.ShellPalette.Entry(panel.shell.ShellPalette.Group.HELP, "FAQ · " + q.question(), null, () -> { show("h-faq"); faq.open(q.id()); }, null, null));
            }
        }
        return out;
    }

    /** Dock só com estado real; o que não pode ser lido é UNKNOWN. Igual ao anterior: o dock não toca em nada. */
    private void updateStatusDock(Snapshot s) {
        if (shell == null) return;
        updateRecovery();
        shell.dock().setModel(panel.shell.DockModel.build(s, ctx.trading.snapshot.get(), ctx.byx.snapshot(),
                panel.shell.WalletStatus.dock(ctx.byx.snapshot().identity(), () -> ctx.byxWallets.wallets()),
                ctx.adminAccess.hasValidAdminSession(), views.containsKey("capture"), ctx.scientificCapture.current()));
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
