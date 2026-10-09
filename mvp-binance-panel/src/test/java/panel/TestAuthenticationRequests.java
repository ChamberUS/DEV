package panel;

import panel.auth.AuthenticationRequest;
import panel.auth.AuthService;
import panel.user.User;

/** Stateless UI test double; never packaged or used by production composition. */
public final class TestAuthenticationRequests {
    public static panel.auth.SessionOperation operation(Runnable action) {
        return new panel.auth.SessionOperation() {
            @Override public void run() { action.run(); }
            @Override public boolean deliver(Runnable callback) { callback.run(); return true; }
        };
    }
    @FunctionalInterface public interface Authenticator { User login(String id, char[] password); }
    public static AuthenticationRequest create(Authenticator auth) { return create(auth, () -> { }); }
    public static AuthenticationRequest create(Authenticator auth, Runnable cleanOwnResource) {
        return new AuthenticationRequest() {
            private boolean active = true, opened, cleaned;
            @Override public User authenticate(String id, char[] password) {
                synchronized (this) { if (!active) throw new AuthService.StaleAuthenticationException(); }
                User user = auth.login(id, password);
                synchronized (this) {
                    opened = true;
                    if (!active) { clean(); throw new AuthService.StaleAuthenticationException(); }
                    return user;
                }
            }
            @Override public synchronized boolean isCurrent() { return active; }
            @Override public synchronized boolean deliver(Runnable callback) {
                if (!active) return false;
                callback.run();
                return true;
            }
            private void clean() { if (opened && !cleaned) { cleaned = true; cleanOwnResource.run(); } }
            @Override public synchronized void cancel() { active = false; clean(); }
        };
    }
    private TestAuthenticationRequests() { }
}
