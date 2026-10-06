package panel.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import panel.user.User;

/** Presentation state only. Neither role nor elevation here authorizes an operation. Logout clears both. */
public class SessionManager {
    private UserSession user;
    private AdminSession admin;
    private final List<Runnable> onLogout = new ArrayList<>();

    public synchronized void login(User u, java.time.Instant now) {
        logout();
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

    /** Revoga a elevação administrativa se ela pertence a esta conta (troca/reset de senha, mudança de papel). */
    public synchronized boolean revokeAdminFor(long userId) {
        if (user != null && user.user().id() == userId && admin != null) {
            admin = null;
            return true;
        }
        return false;
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
