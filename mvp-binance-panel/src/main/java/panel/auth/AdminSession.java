package panel.auth;

import java.time.Duration;
import java.time.Instant;

/** Autorização temporária adicional para a área Research/Admin. Expira por inatividade. */
public class AdminSession {
    private final Instant authorizedAt;
    private final AuthMethod method;
    private final Duration timeout;
    private Instant expiresAt;

    public AdminSession(Instant authorizedAt, AuthMethod method, Duration timeout) {
        this.authorizedAt = authorizedAt;
        this.method = method;
        this.timeout = timeout;
        this.expiresAt = authorizedAt.plus(timeout);
    }

    public Instant authorizedAt() {
        return authorizedAt;
    }

    public AuthMethod method() {
        return method;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    public void touch(Instant now) {
        expiresAt = now.plus(timeout);
    }
}
