package panel.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import panel.adapter.AdaptiveTraderCli;
import panel.adapter.CommandAdapter;
import panel.adapter.FileResearchBackend;
import panel.adapter.MockResearchBackend;
import panel.adapter.MockTradingProvider;
import panel.adapter.ResearchModeTradingProvider;
import panel.auth.AdminAccessService;
import panel.auth.AuthService;
import panel.auth.SessionManager;
import panel.model.Settings;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.ViewTransitionService;
import panel.motion.icon.AnimationRepository;
import panel.ui.toast.ToastHost;
import panel.security.Database;
import panel.security.SecurityAuditService;
import panel.service.JobManager;
import panel.service.ResearchService;
import panel.service.TradingService;
import panel.user.UserService;

/** Composição das dependências; as telas recebem apenas este contexto. */
public class AppContext {
    /** Resolvido na CONSTRUÇÃO (não na carga da classe): testes com home temporário nunca tocam o home real. */
    private static Path appHome() {
        // Existing injected QA compositions keep their isolated temporary home.
        // DEFAULT has no property/env switch: Windows uses the real account's known folder.
        return INJECTED.get() != null ? panel.security.RuntimeStorage.legacyDirectory() : panel.security.RuntimeStorage.directory();
    }

    public final Settings settings = StartupTrace.time("Settings.load", Settings::load);
    private final Clock clock = Clock.systemUTC();
    private final Database db = StartupTrace.time("Database.openRuntime (SQLite)", () -> Database.openRuntime(appHome().resolve(Database.RUNTIME_FILE_NAME)));
    /** Legado (panel.db, rollback-only): SÓ leitura imutável do histórico de auditoria; nunca read-write, nunca DDL, nunca recriado. */
    private final panel.security.LegacyAuditHistory legacyHistory = StartupTrace.time("LegacyPanelDb.openReadOnly", () -> panel.security.LegacyPanelDb.openReadOnly(panel.security.RuntimeStorage.legacyDirectory().resolve("panel.db")).map(h -> (panel.security.LegacyAuditHistory) h)
            .orElse(panel.security.LegacyAuditHistory.UNAVAILABLE));
    public final SecurityAuditService audit = new SecurityAuditService(legacyHistory, clock);
    public final SessionManager sessions = new SessionManager();
    /**
     * A autenticação é do SERVIÇO local (autoridade). O painel só a apresenta: não há banco de usuários, hash de senha, limitador, provedor de OTP, keychain legado nem
     * flag que reabra o caminho antigo. Testes injetam um {@link panel.localservice.AuthorityGateway} (dublê) por {@link #create}; o caminho normal usa SEMPRE o
     * cliente real do serviço pareado e verificado.
     */
    public record Providers(panel.localservice.AuthorityGateway gateway, String developmentLabel, panel.adapter.ByxGasGrantGateway gasGateway) {
        public Providers(panel.localservice.AuthorityGateway gateway, String developmentLabel) {
            this(gateway, developmentLabel, null);
        }
    }

    private static final ThreadLocal<Providers> INJECTED = new ThreadLocal<>();

    public static AppContext create(panel.adapter.CaptureProcessProbe captureProbe, Providers injected) {
        INJECTED.set(injected);
        try {
            return new AppContext(captureProbe);
        } finally {
            INJECTED.remove();
        }
    }

    private final Providers injected = INJECTED.get();
    /** Rótulo exibido quando a verificação usa uma autoridade injetada (teste); null no caminho normal. */
    public final String developmentLabel = injected == null ? null : injected.developmentLabel();
    public final panel.localservice.AuthorityGateway authority = injected != null && injected.gateway() != null ? injected.gateway()
            : StartupTrace.time("AuthorityClient", () -> new panel.localservice.AuthorityClient(new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome())));
    public final AuthService auth = new AuthService(authority, sessions, clock);
    public final panel.auth.TrustedDeviceService trustedDevices = new panel.auth.TrustedDeviceService(auth, clock);
    public final AdminAccessService adminAccess = new AdminAccessService(sessions, authority, auth, trustedDevices, clock);
    public final UserService userService = new UserService(authority, auth, sessions);
    public final panel.service.ByxNetworkService byx = StartupTrace.time("byx", () -> new panel.service.ByxNetworkService(
            // a leitura pública da chain é DO SERVIÇO (IPC verificado): o painel não conhece endpoint, host nem porta da chain
            new panel.adapter.ServiceChainGateway(new panel.localservice.ChainStatusClient(new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome())), clock),
            adminAccess::requireAdmin, clock));
    public final panel.service.ByxWalletIdentityService byxWallets = StartupTrace.time("byxWallets", () -> new panel.service.ByxWalletIdentityService(
            sessions, new panel.repository.ByxWalletRepository(db), byx, clock));
    public final panel.service.ByxBenefitsService byxBenefits = StartupTrace.time("byxBenefits", () -> new panel.service.ByxBenefitsService(
            byxWallets, new panel.adapter.CosmosByxChainGateway(clock), clock, panel.service.ByxBenefitsService.defaults()));
    public final panel.service.ByxPaymentService byxPayments = StartupTrace.time("byxPayments", () -> new panel.service.ByxPaymentService(
            sessions,byxWallets,new panel.repository.ByxPaymentRepository(db),
            new panel.adapter.CosmosByxPaymentVerifier(clock),clock,panel.service.ByxPaymentPolicy::load));
    public final panel.service.EntitlementService byxEntitlements = StartupTrace.time("byxEntitlements", () -> new panel.service.EntitlementService(byxBenefits,byxPayments));

    public final panel.repository.GasGrantRepository byxGasJournal = StartupTrace.time("byxGasJournal", () -> new panel.repository.GasGrantRepository(db));
    /**
     * L10b: o produto normal só tem o gateway de leitura/verificação on-chain. O signer de teste (assina e transmite por um keyring externo)
     * NÃO existe no artefato: a classe vive só no código de teste e só entra por esta injeção explícita; nenhuma flag, arquivo de
     * configuração ou variável de ambiente o liga.
     */
    public final panel.adapter.ByxGasGrantGateway byxGasGateway = injected != null && injected.gasGateway() != null ? injected.gasGateway()
            : StartupTrace.time("CosmosGasGrantGateway", () -> new panel.adapter.CosmosGasGrantGateway(clock));
    public final panel.service.GasSponsorshipService byxGas = StartupTrace.time("byxGas", () -> new panel.service.GasSponsorshipService(
            sessions, byxWallets, byxBenefits, byxGasJournal, byxGasGateway, panel.service.GasSponsorshipPolicy::load, clock));

    public final panel.service.TreasuryService byxTreasury = StartupTrace.time("byxTreasury", () -> new panel.service.TreasuryService(
            byxWallets, new panel.adapter.CosmosByxChainGateway(clock), byxGasGateway, byxGasJournal,
            panel.service.GasSponsorshipPolicy::load, clock));

    public final CommandAdapter cli = StartupTrace.time("cli", () -> new AdaptiveTraderCli(() -> settings.cliPath));
    public final JobManager jobs = StartupTrace.time("jobs", () -> new JobManager(cli, settings::project, this::refresh, adminAccess::requireAdmin));
    public final ResearchService research = StartupTrace.time("ResearchService", () -> new ResearchService(settings, new panel.adapter.LocalBackendGateway(new FileResearchBackend(cli)), new MockResearchBackend(), jobs));
    public final panel.service.CaptureMonitorService captureMonitor;
    /** Estado da captura científica real (somente leitura, fora da FX, com limite de tempo); iniciado só depois do login. */
    public final panel.service.ScientificCaptureService scientificCapture;
    /** Resultado de cada sondagem do serviço local (a UI registra o seu); a sondagem não navega nem concede nada. */
    public volatile Consumer<panel.localservice.LocalServiceStatus> onLocalService = s -> { };
    public final panel.localservice.LocalServiceMonitor localService = StartupTrace.time("localService", () -> new panel.localservice.LocalServiceMonitor(
            new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()), s -> onLocalService.accept(s)));
    /** Mercado público ETHUSDT via serviço local (nunca direto da Binance). Cada lote de dados avisa a UI, que atualiza só o Trading. */
    public volatile Runnable onMarketData = () -> { };
    public final panel.localservice.MarketFeedClient market = StartupTrace.time("market", () -> new panel.localservice.MarketFeedClient(
            new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()), () -> onMarketData.run()));
    public final TradingService trading = StartupTrace.time("trading", () -> new TradingService(settings, new ResearchModeTradingProvider(market::snapshot), new MockTradingProvider()));
    public final MotionService motion = StartupTrace.time("MotionService", MotionService::new);
    public final ViewTransitionService transitions = new ViewTransitionService(motion);
    public final AnimationRepository icons = StartupTrace.time("AnimationRepository", () -> new AnimationRepository(motion));
    public final ToastHost toasts = StartupTrace.time("toasts", () -> new ToastHost(motion, icons));
    public Runnable refreshDensity = () -> { };
    public Consumer<String> navigate = id -> { };

    public AppContext() { this(null); }

    public AppContext(panel.adapter.CaptureProcessProbe captureProbe) {
        captureMonitor = new panel.service.CaptureMonitorService(captureProbe != null ? captureProbe :
                new panel.adapter.LocalCaptureProcessProbe(Path.of(System.getProperty("user.home"), ".mvp-binance-capture"),
                        settings.project().resolve("data/microstructure"), Path.of(settings.cliPath)), () -> {
                    var scope = auth.captureSession();
                    return () -> adminAccess.requireAdmin(scope);
                }, adminAccess::hasValidAdminSession);
        scientificCapture = new panel.service.ScientificCaptureService(
                panel.adapter.ScientificCaptureResolver.forLocal(Path.of(System.getProperty("user.home"), ".mvp-binance-capture"),
                        settings.project().resolve("data/microstructure"), Path.of(settings.cliPath))::observe, clock);
        sessions.onLogout(captureMonitor::stop);
        sessions.onLogout(scientificCapture::stop);
        sessions.onLogout(byx::pause);
        userService.onContactsChanged = trustedDevices::revokeAllForCurrentUser;
        userService.onCredentialsChanged = adminAccess::credentialsChanged;
        StartupTrace.mark("AppContext fields done");
        applyMotionSettings();
        research.snapshot.addListener((o, a, s) -> trading.update(s));
        trading.update(research.snapshot.get());
    }

    private final panel.motion.SystemMotionProbe systemMotion = panel.motion.SystemMotionProbe.macOs();
    private volatile boolean systemReduced;
    private final java.util.concurrent.atomic.AtomicLong systemMotionGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean presentationClosed;

    /** Preferência do app (Settings), sem o ajuste do sistema. */
    public MotionPreference appMotion() {
        try {
            return MotionPreference.valueOf(settings.motion);
        } catch (IllegalArgumentException e) {
            return MotionPreference.FULL;
        }
    }

    /** O valor efetivo (app + sistema) difere do escolhido: o sistema reduziu. */
    public boolean motionReducedBySystem() {
        return panel.motion.MotionPolicy.systemApplied(appMotion(), settings.followSystemMotion, systemReduced);
    }

    /** Relê a configuração do macOS fora da thread FX e reaplica só se mudou. Chamar com o app aberto e ao ganhar o foco. */
    public void refreshSystemMotion() {
        if (presentationClosed) return;
        long ticket = systemMotionGeneration.incrementAndGet();
        Thread t = new Thread(() -> {
            boolean now = systemMotion.reduced();
            try {
                javafx.application.Platform.runLater(() -> {
                    if (presentationClosed || ticket != systemMotionGeneration.get()) return;
                    if (now != systemReduced) {
                        systemReduced = now;
                        applyMotionSettings();
                    }
                });
            } catch (IllegalStateException noToolkit) {
                // No FX state is touched when the toolkit is unavailable.
            }
        }, "system-motion");
        t.setDaemon(true);
        t.start();
    }

    public void closePresentation() {
        presentationClosed = true;
        systemMotionGeneration.incrementAndGet();
        onMarketData = () -> { };
        onLocalService = status -> { };
        navigate = id -> { };
    }

    /** Release runtime storage only after its workers have stopped. */
    public void closeRuntimeStorage() {
        if (legacyHistory instanceof panel.security.LegacyPanelDb legacy) legacy.close();
        db.close();
    }

    public void applyMotionSettings() {
        motion.preference.set(panel.motion.MotionPolicy.effective(appMotion(), settings.followSystemMotion, systemReduced));
        motion.animatedIcons.set(settings.animatedIcons);
        icons.setLottieEnabled(settings.animatedIcons);
    }

    public void refresh() {
        research.refresh();
    }
}
