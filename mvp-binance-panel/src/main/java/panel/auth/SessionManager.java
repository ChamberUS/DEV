package panel.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import panel.user.User;

/** Guarda UserSession e AdminSession atuais. Logout encerra as duas. */
public class SessionManager {
    private UserSession user;
    private AdminSession admin;
    private final List<Runnable> onLogout = new ArrayList<>();

    public synchronized void login(User u, java.time.Instant now) {
        user = new UserSession(u, now);
        admin = null;
    }

    public synchronized Optional<UserSession> user() {
        return Optional.ofNullable(user);
    }

    public synchronized Optional<AdminSession> admin() {
        return Optional.ofNullable(admin);
    }

    public synchronized void grantAdmin(AdminSession s) {
        admin = s;
    }

    public synchronized void revokeAdmin() {
        admin = null;
    }

    public synchronized void updateUser(User u) {
        if (user != null && user.user().id() == u.id()) {
            user = new UserSession(u, user.loggedInAt());
        }
    }

    public synchronized void logout() {
        user = null;
        admin = null;
        new ArrayList<>(onLogout).forEach(Runnable::run);
    }

    public synchronized void onLogout(Runnable r) {
        onLogout.add(r);
    }
}
