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
import panel.auth.InMemoryRateLimiter;
import panel.auth.OtpService;
import panel.auth.PasswordHasher;
import panel.auth.SessionManager;
import panel.model.Settings;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.ViewTransitionService;
import panel.motion.icon.AnimationRepository;
import panel.ui.toast.ToastHost;
import panel.security.Database;
import panel.security.SecurityAuditService;
import panel.security.SecurityConfig;
import panel.service.JobManager;
import panel.service.ResearchService;
import panel.service.TradingService;
import panel.user.SqliteUserRepository;
import panel.user.UserService;

/** Composição das dependências; as telas recebem apenas este contexto. */
public class AppContext {
    private static final Path DB_FILE = Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "panel.db");

    public final Settings settings = Settings.load();
    public final SecurityConfig security = SecurityConfig.load();
    private final Clock clock = Clock.systemUTC();
    private final Database db = Database.open(DB_FILE);
    public final SqliteUserRepository users = new SqliteUserRepository(db);
    public final SecurityAuditService audit = new SecurityAuditService(db, clock);
    public final SessionManager sessions = new SessionManager();
    public final panel.security.SecretStore secrets = new panel.security.MacOsKeychainSecretStore();
    public final panel.security.ProviderConfig providers = panel.security.ProviderConfig.load();
    /**
     * Provedores de OTP. O caminho normal usa SEMPRE os reais (Resend e Twilio Verify): nenhum arquivo, variável de ambiente ou flag de
     * runtime troca isso. Um provedor de desenvolvimento só pode ser injetado por código que o construa explicitamente
     * ({@link #create}); a classe do provedor de desenvolvimento não faz parte do artefato de produção, só do código de teste.
     * Isto não resiste a quem pode modificar os próprios binários.
     */
    public record Providers(panel.auth.EmailOtpProvider email, panel.auth.SmsOtpProvider sms, String developmentLabel) {
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
    /** Rótulo exibido quando a verificação usa um provedor de desenvolvimento injetado; null no caminho normal. */
    public final String developmentLabel = injected == null ? null : injected.developmentLabel();
    public final panel.auth.EmailOtpProvider emailProvider = injected != null ? injected.email() : new panel.auth.ResendEmailOtpProvider(secrets, providers);
    public final panel.auth.SmsOtpProvider smsProvider = injected != null ? injected.sms() : new panel.auth.TwilioVerifySmsProvider(secrets, providers);
    public final panel.auth.TrustedDeviceService trustedDevices = new panel.auth.TrustedDeviceService(db, secrets, sessions, audit, clock);
    public final AdminAccessService adminAccess = new AdminAccessService(sessions, users, security, new OtpService(clock),
            emailProvider, smsProvider, trustedDevices, audit, clock);
    public final PasswordHasher hasher = new PasswordHasher();
    public final AuthService auth = new AuthService(users, hasher, sessions,
            new InMemoryRateLimiter(5, Duration.ofSeconds(60), clock), audit, clock);
    public final UserService userService = new UserService(users, hasher, adminAccess, audit, sessions, clock);

    public final panel.service.ByxNetworkService byx = new panel.service.ByxNetworkService(
            new panel.adapter.CosmosByxChainGateway(clock), adminAccess::requireAdmin, clock);
    public final panel.service.ByxWalletIdentityService byxWallets = new panel.service.ByxWalletIdentityService(
            sessions, new panel.repository.ByxWalletRepository(db), byx, clock);
    public final panel.service.ByxBenefitsService byxBenefits = new panel.service.ByxBenefitsService(
            byxWallets, new panel.adapter.CosmosByxChainGateway(clock), clock, panel.service.ByxBenefitsService.defaults());
    public final panel.service.ByxPaymentService byxPayments = new panel.service.ByxPaymentService(
            sessions,byxWallets,new panel.repository.ByxPaymentRepository(db),
            new panel.adapter.CosmosByxPaymentVerifier(clock),clock,panel.service.ByxPaymentPolicy::load);
    public final panel.service.EntitlementService byxEntitlements = new panel.service.EntitlementService(byxBenefits,byxPayments);

    public final panel.repository.GasGrantRepository byxGasJournal = new panel.repository.GasGrantRepository(db);
    public final panel.adapter.ByxGasGrantGateway byxGasGateway = security.devMode()
            && "I_ACKNOWLEDGE_TEST_ONLY".equals(System.getenv("BYX_LOCALNET_TEST_SIGNER"))
            ? new panel.adapter.LocalnetGasTestSigner(clock, true, Path.of("scripts/byx_gas_test.py"))
            : new panel.adapter.CosmosGasGrantGateway(clock);
    public final panel.service.GasSponsorshipService byxGas = new panel.service.GasSponsorshipService(
            sessions, byxWallets, byxBenefits, byxGasJournal, byxGasGateway, panel.service.GasSponsorshipPolicy::load, clock);

    public final panel.service.TreasuryService byxTreasury = new panel.service.TreasuryService(
            byxWallets, new panel.adapter.CosmosByxChainGateway(clock), byxGasGateway, byxGasJournal,
            panel.service.GasSponsorshipPolicy::load, clock);

    public final CommandAdapter cli = new AdaptiveTraderCli(() -> settings.cliPath);
    public final JobManager jobs = new JobManager(cli, settings::project, this::refresh, adminAccess::requireAdmin);
    public final ResearchService research = new ResearchService(settings, new panel.adapter.LocalBackendGateway(new FileResearchBackend(cli)), new MockResearchBackend(), jobs);
    public final panel.service.CaptureMonitorService captureMonitor;
    /** Resultado de cada sondagem do serviço local (a UI registra o seu); a sondagem não navega nem concede nada. */
    public volatile Consumer<panel.localservice.LocalServiceStatus> onLocalService = s -> { };
    public final panel.localservice.LocalServiceMonitor localService = new panel.localservice.LocalServiceMonitor(
            new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()), s -> onLocalService.accept(s));
    /** Mercado público ETHUSDT via serviço local (nunca direto da Binance). Cada lote de dados avisa a UI, que atualiza só o Trading. */
    public volatile Runnable onMarketData = () -> { };
    public final panel.localservice.MarketFeedClient market = new panel.localservice.MarketFeedClient(
            new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()), () -> onMarketData.run());
    public final TradingService trading = new TradingService(settings, new ResearchModeTradingProvider(market::snapshot), new MockTradingProvider());
    public final MotionService motion = new MotionService();
    public final ViewTransitionService transitions = new ViewTransitionService(motion);
    public final AnimationRepository icons = new AnimationRepository(motion);
    public final ToastHost toasts = new ToastHost(motion, icons);
    public Runnable refreshDensity = () -> { };
    public Consumer<String> navigate = id -> { };

    public AppContext() { this(null); }

    public AppContext(panel.adapter.CaptureProcessProbe captureProbe) {
        captureMonitor = new panel.service.CaptureMonitorService(captureProbe != null ? captureProbe :
                new panel.adapter.LocalCaptureProcessProbe(Path.of(System.getProperty("user.home"), ".mvp-binance-capture"),
                        settings.project().resolve("data/microstructure"), Path.of(settings.cliPath)), adminAccess::requireAdmin);
        sessions.onLogout(captureMonitor::stop);
        sessions.onLogout(byx::pause);
        userService.onContactsChanged = trustedDevices::revokeAllForCurrentUser;
        userService.onCredentialsChanged = adminAccess::credentialsChanged;
        applyMotionSettings();
        research.snapshot.addListener((o, a, s) -> trading.update(s));
        trading.update(research.snapshot.get());
    }

    private final panel.motion.SystemMotionProbe systemMotion = panel.motion.SystemMotionProbe.macOs();
    private volatile boolean systemReduced;

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
        Thread t = new Thread(() -> {
            boolean now = systemMotion.reduced();
            if (now != systemReduced) {
                systemReduced = now;
                try {
                    javafx.application.Platform.runLater(this::applyMotionSettings);
                } catch (IllegalStateException noToolkit) {
                    // sem toolkit (testes): o próximo applyMotionSettings já usa o valor novo
                }
            }
        }, "system-motion");
        t.setDaemon(true);
        t.start();
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
