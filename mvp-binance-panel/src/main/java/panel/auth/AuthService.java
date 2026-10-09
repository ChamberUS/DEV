package panel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import panel.localservice.AuthorityGateway;
import panel.security.Role;
import panel.user.User;
import panel.user.UserStatus;

/**
 * Login pelo SERVIÇO local (a autoridade). O painel NÃO verifica senha, não lê conta, não aplica limite de tentativas e não decide papel: envia o que o usuário
 * digitou e apresenta o que o serviço respondeu. {@link SessionManager} guarda só a REPRESENTAÇÃO da sessão (usuário/papel para renderizar a interface); esconder
 * um botão não é autorização. Não existe caminho para autenticar contra o banco legado nem flag que o reabra.
 */
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

    private final AuthorityGateway gateway;
    private final SessionManager sessions;
    private final Clock clock;
    // Never hold this lock during IPC, subprocess launch, or gateway cleanup.
    private final Object lifecycle = new Object();
    private final java.util.concurrent.locks.ReentrantLock transport = new java.util.concurrent.locks.ReentrantLock(true);
    private final java.util.concurrent.ExecutorService cleanup = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "authentication-cleanup");
        t.setDaemon(true);
        return t;
    });
    private long epoch;
    private Request current;
    private Request gatewayOwner; // accessed only under the worker-only transport lock
    private boolean closed;

    public AuthService(AuthorityGateway gateway, SessionManager sessions, Clock clock) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    public boolean firstRun() { return false; }

    /** Reserve ownership when the request is created, before it enters any worker queue. */
    public Request beginLogin() {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("authentication closed");
            Request prior = current;
            Request next = new Request(++epoch);
            current = next;
            if (prior != null) prior.cancelled = true;
            sessions.logout();
            if (prior != null) enqueueCleanup(prior);
            return next;
        }
    }

    public final class Request implements AuthenticationRequest {
        private final long generation;
        private volatile boolean cancelled;
        private boolean started;
        private boolean delivered;
        private java.util.UUID sessionId;
        private long invalidatedEpoch = -1;

        private Request(long generation) { this.generation = generation; }

        @Override public boolean isCurrent() {
            synchronized (lifecycle) { return owns(this) && (sessionId == null || sessions.user().filter(u -> u.id().equals(sessionId)).isPresent()); }
        }

        @Override public boolean deliver(Runnable callback) {
            synchronized (lifecycle) {
                if (!isCurrent() || delivered) return false;
                delivered = true;
                callback.run();
                return true;
            }
        }

        @Override public User authenticate(String identifier, char[] password) {
            char[] retryPassword = password.clone();
            transport.lock(); // workers only; never a UI ownership lock
            try {
                synchronized (lifecycle) {
                    if (!owns(this) || started) throw new StaleAuthenticationException();
                    started = true;
                }
                // Replacement also releases the previous session before adopting a new token.
                if (gatewayOwner != null) releaseGateway(gatewayOwner);
                gatewayOwner = this;
                AuthorityGateway.Reply reply = gateway.login(identifier == null ? "" : identifier.trim(), password);
                if (reply.serviceUnavailable() && isCurrent() && gateway.ensureService() && isCurrent()) {
                    reply = gateway.login(identifier == null ? "" : identifier.trim(), retryPassword);
                }
                synchronized (lifecycle) {
                    if (owns(this) && reply.ok()) {
                        User user = toUser(reply.result());
                        sessions.login(user, clock.instant());
                        sessionId = sessions.user().orElseThrow().id();
                        return user;
                    }
                }
                releaseGateway(this);
                if (!isCurrent()) throw new StaleAuthenticationException();
                switch (reply.code()) {
                    case "INVALID_CREDENTIALS" -> throw new LoginException(Failure.INVALID_CREDENTIALS, null);
                    case "RATE_LIMITED" -> throw new LoginException(Failure.RATE_LIMITED, Duration.ofSeconds(Math.max(1,
                            reply.result() == null ? 30 : reply.result().path("retryAfterSec").asLong(30))));
                    default -> throw new IllegalStateException("authority unavailable");
                }
            } catch (RuntimeException failure) {
                releaseGateway(this);
                if (!isCurrent()) throw new StaleAuthenticationException();
                throw failure;
            } finally {
                java.util.Arrays.fill(password, '\0');
                java.util.Arrays.fill(retryPassword, '\0');
                transport.unlock();
            }
        }

        @Override public void cancel() {
            synchronized (lifecycle) {
                cancelled = true;
                if (current == this) {
                    current = null;
                    epoch++;
                    if (sessionId != null && sessions.user().filter(u -> u.id().equals(sessionId)).isPresent()) sessions.logout();
                }
            }
            enqueueCleanup(this);
        }

        @Override public String toString() { return "AuthenticationRequest[generation=" + generation + "]"; }
    }

    /** Only a discarded operation: UI suppresses it; genuine current failures keep their normal states. */
    public static final class StaleAuthenticationException extends IllegalStateException {
        public StaleAuthenticationException() { super("authentication operation superseded"); }
    }

    private boolean owns(Request request) {
        return !closed && request != null && current == request && !request.cancelled && request.generation == epoch;
    }

    private void enqueueCleanup(Request request) {
        try { cleanup.execute(() -> {
            transport.lock();
            try { releaseGateway(request); } finally { transport.unlock(); }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
            // close queues the final owned cleanup before shutting down this executor.
        }
    }

    /** transport lock guarantees this logout cannot consume a newer token. No presentation is changed here. */
    private void releaseGateway(Request request) {
        if (gatewayOwner != request) return;
        try { gateway.logout(); }
        finally { try { gateway.forgetSession(); } finally { gatewayOwner = null; } }
    }

    /** Compatibility for worker/QA callers; production UI reserves beginLogin at submit. */
    public User login(String identifier, char[] password) { return beginLogin().authenticate(identifier, password); }

    /** Capture and invalidate the active generation now; execute the returned gateway cleanup on a worker. */
    public LogoutRequest prepareLogout() {
        Request owner;
        long generation;
        synchronized (lifecycle) {
            owner = current;
            current = null;
            generation = ++epoch;
            if (owner != null) owner.cancelled = true;
            sessions.logout();
        }
        return new LogoutRequest(owner, generation);
    }

    /** Cleanup and its eventual navigation belong to the same invalidated epoch. */
    public final class LogoutRequest implements SessionOperation {
        private final Request owner;
        private final long generation;
        private boolean delivered;

        private LogoutRequest(Request owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        @Override public void run() {
            transport.lock();
            try { if (owner != null) releaseGateway(owner); } finally { transport.unlock(); }
        }

        /** Cache-only completion: superseded logout must not touch a newer screen. */
        @Override public boolean deliver(Runnable callback) {
            synchronized (lifecycle) {
                if (closed || current != null || epoch != generation || delivered) return false;
                delivered = true;
                callback.run();
                return true;
            }
        }
    }

    public void logout() { prepareLogout().run(); }

    /** Immutable presentation/session ownership. Never exposes the opaque Service token. */
    public final class SessionScope {
        private final Request owner;
        private final java.util.UUID id;
        private SessionScope(Request owner, java.util.UUID id) { this.owner = owner; this.id = id; }
        private boolean currentLocked() {
            return owns(owner) && id != null && sessions.user().filter(u -> u.id().equals(id)).isPresent();
        }
        public boolean isCurrent() { synchronized (lifecycle) { return currentLocked(); } }
        /** A genuine current-session failure may show its fallback, until another generation begins. */
        public boolean isLatestOutcome() {
            synchronized (lifecycle) {
                return currentLocked() || !closed && owner != null && current == null && owner.invalidatedEpoch == epoch;
            }
        }
        public AuthorityGateway.Reply call(java.util.function.Function<AuthorityGateway, AuthorityGateway.Reply> operation) {
            transport.lock();
            try {
                if (!isCurrent() || gatewayOwner != owner) return new AuthorityGateway.Reply(false, "STALE_AUTH_OPERATION", null);
                AuthorityGateway.Reply reply = operation.apply(gateway);
                return isCurrent() ? reply : new AuthorityGateway.Reply(false, "STALE_AUTH_OPERATION", null);
            } finally { transport.unlock(); }
        }
        /** Atomic cache-only publication. The callback must never perform I/O. */
        public boolean present(Runnable update) {
            synchronized (lifecycle) {
                if (!currentLocked()) return false;
                update.run();
                return true;
            }
        }
        public void invalidate() {
            synchronized (lifecycle) {
                if (!currentLocked()) return;
                current = null;
                epoch++;
                owner.invalidatedEpoch = epoch;
                owner.cancelled = true;
                sessions.logout();
            }
            enqueueCleanup(owner);
        }
    }

    public SessionScope captureSession() {
        synchronized (lifecycle) {
            return new SessionScope(current, sessions.user().map(UserSession::id).orElse(null));
        }
    }

    public Optional<User> refreshUser() { return refreshUser(captureSession()); }

    public Optional<User> refreshUser(SessionScope scope) {
        AuthorityGateway.Reply reply = scope.call(AuthorityGateway::sessionStatus);
        if (!reply.ok()) {
            if (!reply.code().equals("STALE_AUTH_OPERATION")) scope.invalidate();
            return Optional.empty();
        }
        User user = toUser(reply.result());
        return scope.present(() -> sessions.updateUser(user)) ? Optional.of(user) : Optional.empty();
    }

    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            Request owner = current;
            current = null;
            epoch++;
            if (owner != null) owner.cancelled = true;
            sessions.logout();
            if (owner != null) enqueueCleanup(owner);
            cleanup.shutdown(); // finish owned bounded cleanup; never wait on the FX thread
        }
    }

    /** Apresentação: o que o serviço devolveu. O verificador de senha nunca existe aqui. */
    public static User toUser(JsonNode r) {
        String phone = r.path("phone").asText("");
        return new User(r.path("userId").asLong(0), r.path("username").asText(""), r.path("email").asText(""), "", "ADMIN".equals(r.path("role").asText()) ? Role.ADMIN : Role.USER,
                UserStatus.ACTIVE, phone.isEmpty() ? null : phone, r.path("emailVerified").asBoolean(false), r.path("phoneVerified").asBoolean(false), r.path("mustChangePassword").asBoolean(false),
                Instant.ofEpochMilli(r.path("createdAtMs").asLong(0)), Instant.ofEpochMilli(r.path("createdAtMs").asLong(0)),
                r.path("lastLoginAtMs").asLong(0) == 0 ? null : Instant.ofEpochMilli(r.path("lastLoginAtMs").asLong()));
    }
}
