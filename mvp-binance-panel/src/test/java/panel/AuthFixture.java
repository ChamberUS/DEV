package panel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import panel.auth.AdminAccessService;
import panel.auth.AuthService;
import panel.auth.DevOtpProvider;
import panel.auth.InMemoryRateLimiter;
import panel.auth.NetworkIdentityProvider;
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
    static final String TRUSTED = "2001:db8::1";
    static final String OTHER = "2001:db8::2";

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

    AuthFixture(Set<String> localAddresses, boolean twoFactorConfigured) {
        NetworkIdentityProvider net = () -> localAddresses;
        SecurityConfig config = new SecurityConfig(TRUSTED, 30, false);
        auth = new AuthService(users, hasher, sessions, new InMemoryRateLimiter(3, Duration.ofSeconds(60), clock), audit, clock);
        OtpService otp = new OtpService(clock, Duration.ofMinutes(5), Duration.ofSeconds(30), 5);
        access = twoFactorConfigured
                ? new AdminAccessService(sessions, config, net, otp, otpProvider, otpProvider, audit, clock)
                : new AdminAccessService(sessions, config, net, otp, new UnconfiguredEmailOtpProvider(), new UnconfiguredSmsOtpProvider(), audit, clock);
        userService = new UserService(users, hasher, access, audit, sessions, clock);
    }

    static AuthFixture trusted() {
        return new AuthFixture(Set.of(panel.auth.Ipv6.normalize(TRUSTED).orElseThrow()), true);
    }

    static AuthFixture untrusted() {
        return new AuthFixture(Set.of(panel.auth.Ipv6.normalize(OTHER).orElseThrow()), true);
    }

    void seedAdmin() {
        userService.createInitialAdmin("boss", "boss@example.com", "correct-horse-1".toCharArray(), "+5511999991234");
    }

    /** Cria um USER diretamente via admin temporário. */
    void seedUser() {
        seedAdmin();
        auth.login("boss", "correct-horse-1".toCharArray());
        access.grantTrustedNetwork();
        userService.createUser("alice", "alice@example.com", "temporary-pass-1".toCharArray(), null, Role.USER);
        auth.logout();
    }
}
