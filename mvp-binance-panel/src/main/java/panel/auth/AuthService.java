package panel.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import panel.security.AuditEvent;
import panel.security.SecurityAuditService;
import panel.user.User;
import panel.user.UserRepository;
import panel.user.UserStatus;

public class AuthService {
    public enum Failure { INVALID_CREDENTIALS, ACCOUNT_DISABLED, RATE_LIMITED }

    public static class LoginException extends RuntimeException {
        public final Failure failure;
        public final Duration retryAfter;

        public LoginException(Failure failure, Duration retryAfter) {
            super(failure.name());
            this.failure = failure;
            this.retryAfter = retryAfter;
        }
    }

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final SessionManager sessions;
    private final RateLimiter limiter;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final String dummyHash;
    /** Chave aleatória por processo: impressão do identificador de uma tentativa sem conta (não reversível, não correlaciona entre execuções). */
    private final byte[] attemptKey = new java.security.SecureRandom().generateSeed(32);

    public AuthService(UserRepository users, PasswordHasher hasher, SessionManager sessions, RateLimiter limiter, SecurityAuditService audit, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.sessions = sessions;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = hasher.hash("dummy-password-for-timing".toCharArray());
    }

    private String fingerprint(String normalized) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(attemptKey, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 6);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean firstRun() {
        return users.count() == 0;
    }

    public User login(String identifier, char[] password) {
        String key = identifier == null ? "" : identifier.trim().toLowerCase();
        Optional<Duration> blocked = limiter.blockedFor(key);
        if (blocked.isPresent()) {
            throw new LoginException(Failure.RATE_LIMITED, blocked.get());
        }
        Optional<User> found = key.isEmpty() ? Optional.empty() : users.findByUsernameOrEmail(identifier.trim());
        boolean ok = hasher.verify(password, found.map(User::passwordHash).orElse(dummyHash)) && found.isPresent();
        if (!ok) {
            limiter.recordFailure(key);
            // O texto digitado NUNCA vai para o log: pode ser uma senha digitada no campo de usuário. Conta existente = o próprio usuário;
            // sem conta = impressão HMAC curta (mantém o registro e permite contar tentativas repetidas sem guardar o conteúdo).
            audit.record(AuditEvent.LOGIN_FAILED, found.map(User::username).orElse(key.isEmpty() ? "-" : "attempt:" + fingerprint(key)), "invalid credentials");
            throw new LoginException(Failure.INVALID_CREDENTIALS, null);
        }
        User u = found.get();
        if (u.status() == UserStatus.DISABLED) {
            audit.record(AuditEvent.LOGIN_FAILED, u.username(), "account disabled");
            throw new LoginException(Failure.ACCOUNT_DISABLED, null);
        }
        limiter.recordSuccess(key);
        User updated = new User(u.id(), u.username(), u.email(), u.passwordHash(), u.role(), u.status(), u.phone(), u.emailVerified(), u.phoneVerified(),
                u.mustChangePassword(), u.createdAt(), u.updatedAt(), clock.instant());
        users.update(updated);
        sessions.login(updated, clock.instant());
        audit.record(AuditEvent.LOGIN_SUCCESS, u.username(), "role=" + u.role());
        return updated;
    }

    public void logout() {
        sessions.user().ifPresent(s -> audit.record(AuditEvent.LOGOUT, s.user().username(), ""));
        sessions.logout();
    }
}
