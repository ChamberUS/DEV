package panel.auth;

import java.time.Duration;
import java.time.Instant;

/** Presentation of an absolute authority deadline; this object never authorizes an operation. */
public class AdminSession {
    private final Instant authorizedAt;
    private final AuthMethod method;
    private final Instant expiresAt;

    public AdminSession(Instant authorizedAt, AuthMethod method, Duration timeout) {
        if (method == AuthMethod.PASSKEY) throw new IllegalArgumentException("Passkey is not implemented");
        this.authorizedAt = authorizedAt;
        this.method = method;
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
        // Compatibility only: activity cannot extend the authority deadline.
    }
}
