package panel.user;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import panel.auth.PasswordHasher;
import panel.auth.SessionManager;
import panel.security.AccessDeniedException;
import panel.security.AdminGate;
import panel.security.AuditEvent;
import panel.security.Role;
import panel.security.SecurityAuditService;

/** Regras de usuários. Operações administrativas passam sempre por AdminGate (barreira de serviço). */
public class UserService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{8,15}");

    private final UserRepository repo;
    private final PasswordHasher hasher;
    private final AdminGate gate;
    private final SecurityAuditService audit;
    private final SessionManager sessions;
    private final Clock clock;

    public UserService(UserRepository repo, PasswordHasher hasher, AdminGate gate, SecurityAuditService audit, SessionManager sessions, Clock clock) {
        this.repo = repo;
        this.hasher = hasher;
        this.gate = gate;
        this.audit = audit;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Só funciona enquanto não existe nenhum usuário. */
    public User createInitialAdmin(String username, String email, char[] password, String phone) {
        if (repo.count() > 0) {
            throw new AccessDeniedException("Initial setup is no longer available.");
        }
        User u = create(username, email, password, phone, Role.ADMIN, false);
        audit.record(AuditEvent.INITIAL_ADMIN_CREATED, u.username(), "");
        return u;
    }

    public User createUser(String username, String email, char[] temporaryPassword, String phone, Role role) {
        User admin = gate.requireAdmin();
        User u = create(username, email, temporaryPassword, phone, role, true);
        audit.record(AuditEvent.USER_CREATED, admin.username(), "target=" + u.username() + " role=" + role);
        return u;
    }

    public List<User> listUsers() {
        gate.requireAdmin();
        return repo.findAll();
    }

    public void setStatus(long id, UserStatus status) {
        User admin = gate.requireAdmin();
        User t = load(id);
        if (status == UserStatus.DISABLED) {
            if (t.id() == admin.id()) {
                throw new IllegalArgumentException("You cannot disable your own account.");
            }
            if (t.admin() && t.active() && repo.countActiveAdmins() <= 1) {
                throw new IllegalArgumentException("At least one active administrator is required.");
            }
        }
        save(t, t.role(), status, t.passwordHash(), t.mustChangePassword());
        audit.record(status == UserStatus.DISABLED ? AuditEvent.USER_DISABLED : AuditEvent.USER_ENABLED, admin.username(), "target=" + t.username());
    }

    public void changeRole(long id, Role role) {
        User admin = gate.requireAdmin();
        User t = load(id);
        if (t.admin() && role != Role.ADMIN && t.active() && repo.countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("At least one active administrator is required.");
        }
        save(t, role, t.status(), t.passwordHash(), t.mustChangePassword());
        audit.record(AuditEvent.ROLE_CHANGED, admin.username(), "target=" + t.username() + " role=" + role);
    }

    public void resetPassword(long id, char[] temporaryPassword) {
        User admin = gate.requireAdmin();
        User t = load(id);
        requirePolicy(temporaryPassword, t.username());
        save(t, t.role(), t.status(), hasher.hash(temporaryPassword), true);
        audit.record(AuditEvent.PASSWORD_RESET, admin.username(), "target=" + t.username());
    }

    /** O próprio usuário troca a senha (exige a senha atual, salvo troca forçada pós-login com senha temporária já verificada). */
    public void changeOwnPassword(long id, char[] current, char[] next) {
        User t = load(id);
        if (!hasher.verify(current, t.passwordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        requirePolicy(next, t.username());
        if (hasher.verify(next, t.passwordHash())) {
            throw new IllegalArgumentException("New password must differ from the current one.");
        }
        User u = save(t, t.role(), t.status(), hasher.hash(next), false);
        sessions.updateUser(u);
        audit.record(AuditEvent.PASSWORD_CHANGED, t.username(), "");
    }

    private User create(String username, String email, char[] password, String phone, Role role, boolean mustChange) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must have 3-32 letters, digits, '.', '_' or '-'.");
        }
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Invalid email.");
        }
        String ph = phone == null || phone.isBlank() ? null : phone.replaceAll("[\\s()-]", "");
        if (ph != null && !PHONE.matcher(ph).matches()) {
            throw new IllegalArgumentException("Invalid phone number.");
        }
        requirePolicy(password, username);
        if (repo.findByUsernameOrEmail(username.trim()).isPresent() || repo.findByUsernameOrEmail(email.trim()).isPresent()) {
            throw new IllegalArgumentException("Username or email already in use.");
        }
        Instant now = clock.instant();
        return repo.insert(new User(0, username.trim(), email.trim(), hasher.hash(password), role, UserStatus.ACTIVE, ph, false, false, mustChange, now, now, null));
    }

    private static void requirePolicy(char[] password, String username) {
        String err = PasswordPolicy.check(password, username);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    private User load(long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
    }

    private User save(User t, Role role, UserStatus status, String hash, boolean mustChange) {
        User u = new User(t.id(), t.username(), t.email(), hash, role, status, t.phone(), t.emailVerified(), t.phoneVerified(), mustChange, t.createdAt(), clock.instant(), t.lastLoginAt());
        repo.update(u);
        sessions.updateUser(u);
        return u;
    }
}
