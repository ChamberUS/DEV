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
import panel.auth.DevOtpProvider;
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
    public final DevOtpProvider devOtp = security.devMode() ? new DevOtpProvider() : null;
    public final panel.auth.EmailOtpProvider emailProvider = devOtp != null ? devOtp : new panel.auth.ResendEmailOtpProvider(secrets, providers);
    public final panel.auth.SmsOtpProvider smsProvider = devOtp != null ? devOtp : new panel.auth.TwilioVerifySmsProvider(secrets, providers);
    public final panel.auth.TrustedDeviceService trustedDevices = new panel.auth.TrustedDeviceService(db, secrets, sessions, audit, clock);
    public final AdminAccessService adminAccess = new AdminAccessService(sessions, security, new OtpService(clock),
            emailProvider, smsProvider, trustedDevices, audit, clock);
    public final PasswordHasher hasher = new PasswordHasher();
    public final AuthService auth = new AuthService(users, hasher, sessions,
            new InMemoryRateLimiter(5, Duration.ofSeconds(60), clock), audit, clock);
    public final UserService userService = new UserService(users, hasher, adminAccess, audit, sessions, clock);

    public final CommandAdapter cli = new AdaptiveTraderCli(() -> settings.cliPath);
    public final JobManager jobs = new JobManager(cli, settings::project, this::refresh, adminAccess::requireAdmin);
    public final ResearchService research = new ResearchService(settings, new panel.adapter.LocalBackendGateway(new FileResearchBackend(cli)), new MockResearchBackend(), jobs);
    public final panel.service.CaptureMonitorService captureMonitor;
    public final TradingService trading = new TradingService(settings, new ResearchModeTradingProvider(), new MockTradingProvider());
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
        userService.onContactsChanged = trustedDevices::revokeAllForCurrentUser;
        applyMotionSettings();
        research.snapshot.addListener((o, a, s) -> trading.update(s));
        trading.update(research.snapshot.get());
    }

    public void applyMotionSettings() {
        try {
            motion.preference.set(MotionPreference.valueOf(settings.motion));
        } catch (IllegalArgumentException e) {
            motion.preference.set(MotionPreference.FULL);
        }
        motion.animatedIcons.set(settings.animatedIcons);
        icons.setLottieEnabled(settings.animatedIcons);
    }

    public void refresh() {
        research.refresh();
    }
}
