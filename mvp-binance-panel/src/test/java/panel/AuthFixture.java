package panel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import panel.auth.AdminAccessService;
import panel.auth.AuthService;
import panel.auth.DevOtpProvider;
import panel.auth.InMemoryRateLimiter;
import panel.auth.OtpService;
import panel.auth.PasswordHasher;
import panel.auth.SessionManager;
import panel.auth.UnconfiguredEmailOtpProvider;
import panel.auth.UnconfiguredSmsOtpProvider;
import panel.security.Database;
import panel.security.Role;
import panel.security.SecurityAuditService;
import panel.security.SecurityConfig;
import panel.user.SqliteUserRepository;
import panel.user.UserService;

/** Monta a pilha de autenticação em memória com rede e OTP falsos (determinístico, sem rede real). */
class AuthFixture {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final MutableClock clock = new MutableClock();
    final Database db = Database.inMemory();
    final SqliteUserRepository users = new SqliteUserRepository(db);
    final SecurityAuditService audit = new SecurityAuditService(db, clock);
    final SessionManager sessions = new SessionManager();
    final PasswordHasher hasher = new PasswordHasher(1024, 1, 1);
    final DevOtpProvider otpProvider = new DevOtpProvider();
    final AuthService auth;
    final AdminAccessService access;
    final UserService userService;

    final MemorySecrets secrets = new MemorySecrets();
    final panel.auth.TrustedDeviceService devices = new panel.auth.TrustedDeviceService(db,secrets,sessions,audit,clock);

    AuthFixture(boolean configured) {
        SecurityConfig config = new SecurityConfig(30, true);
        auth = new AuthService(users, hasher, sessions, new InMemoryRateLimiter(3, Duration.ofSeconds(60), clock), audit, clock);
        OtpService otp = new OtpService(clock, Duration.ofMinutes(5), Duration.ofSeconds(30), 5);
        access = new AdminAccessService(sessions,users,config,otp,configured?otpProvider:new UnconfiguredEmailOtpProvider(),
                configured?otpProvider:new UnconfiguredSmsOtpProvider(),devices,audit,clock);
        userService = new UserService(users,hasher,access,audit,sessions,clock);
        userService.onContactsChanged=devices::revokeAllForCurrentUser;
        userService.onCredentialsChanged=access::credentialsChanged;
    }
    static AuthFixture ready() { return new AuthFixture(true); }
    panel.auth.AdminSession authorize() {
        clock.advance(Duration.ofSeconds(31));
        var flow=access.startTwoFactor();flow.sendEmailCode();
        if(flow.verifyEmail(otpProvider.lastCode())!=OtpService.Result.OK)throw new AssertionError();
        flow.sendSmsCode();if(flow.verifySms(otpProvider.lastCode())!=OtpService.Result.OK)throw new AssertionError();
        return access.adminSession().orElseThrow();
    }

    void seedAdmin() {
        userService.createInitialAdmin("boss", "boss@example.com", "correct-horse-1".toCharArray(), "+5511999991234");
    }

    /** Cria um USER diretamente via admin temporário. */
    void seedUser() {
        seedAdmin();
        auth.login("boss", "correct-horse-1".toCharArray());
        authorize();
        userService.createUser("alice", "alice@example.com", "temporary-pass-1".toCharArray(), null, Role.USER);
        auth.logout();
    }
}
