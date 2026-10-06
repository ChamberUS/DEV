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
    private static final Path DB_FILE = Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "panel.db");

    public final Settings settings = Settings.load();
    private final Clock clock = Clock.systemUTC();
    private final Database db = Database.open(DB_FILE);
    public final SecurityAuditService audit = new SecurityAuditService(db, clock);
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
            : new panel.localservice.AuthorityClient(new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()));
    public final AuthService auth = new AuthService(authority, sessions, clock);
    public final panel.auth.TrustedDeviceService trustedDevices = new panel.auth.TrustedDeviceService(authority, clock);
    public final AdminAccessService adminAccess = new AdminAccessService(sessions, authority, auth, trustedDevices, clock);
    public final UserService userService = new UserService(authority, auth, sessions);
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
    /**
     * L10b: o produto normal só tem o gateway de leitura/verificação on-chain. O signer de teste (assina e transmite por um keyring externo)
     * NÃO existe no artefato: a classe vive só no código de teste e só entra por esta injeção explícita; nenhuma flag, arquivo de
     * configuração ou variável de ambiente o liga.
     */
    public final panel.adapter.ByxGasGrantGateway byxGasGateway = injected != null && injected.gasGateway() != null ? injected.gasGateway()
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
