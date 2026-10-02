package panel.user;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import panel.security.Database;
import panel.security.Role;

public class SqliteUserRepository implements UserRepository {
    private static final String COLS = "id,username,email,password_hash,role,status,phone,email_verified,phone_verified,must_change_password,created_at,updated_at,last_login_at";
    private final Database db;

    public SqliteUserRepository(Database db) {
        this.db = db;
    }

    @Override
    public long count() {
        return db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM users")) {
                return r.getLong(1);
            }
        });
    }

    @Override
    public long countActiveAdmins() {
        return db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'")) {
                return r.getLong(1);
            }
        });
    }

    @Override
    public Optional<User> findById(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM users WHERE id=?")) {
                ps.setLong(1, id);
                ResultSet r = ps.executeQuery();
                return r.next() ? Optional.of(map(r)) : Optional.<User>empty();
            }
        });
    }

    @Override
    public Optional<User> findByUsernameOrEmail(String identifier) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM users WHERE username=? OR email=?")) {
                ps.setString(1, identifier);
                ps.setString(2, identifier);
                ResultSet r = ps.executeQuery();
                return r.next() ? Optional.of(map(r)) : Optional.<User>empty();
            }
        });
    }

    @Override
    public List<User> findAll() {
        return db.with(c -> {
            List<User> l = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + COLS + " FROM users ORDER BY id")) {
                while (r.next()) {
                    l.add(map(r));
                }
            }
            return l;
        });
    }

    @Override
    public User insert(User u) {
        long id = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username,email,password_hash,role,status,phone,email_verified,phone_verified,must_change_password,created_at,updated_at,last_login_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, u, 1);
                ps.executeUpdate();
                return ps.getGeneratedKeys().getLong(1);
            }
        });
        return findById(id).orElseThrow();
    }

    @Override
    public void update(User u) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET username=?,email=?,password_hash=?,role=?,status=?,phone=?,email_verified=?,phone_verified=?,must_change_password=?,created_at=?,updated_at=?,last_login_at=? WHERE id=?")) {
                bind(ps, u, 1);
                ps.setLong(13, u.id());
                return ps.executeUpdate();
            }
        });
    }

    private static void bind(PreparedStatement ps, User u, int i) throws SQLException {
        ps.setString(i, u.username());
        ps.setString(i + 1, u.email());
        ps.setString(i + 2, u.passwordHash());
        ps.setString(i + 3, u.role().name());
        ps.setString(i + 4, u.status().name());
        ps.setString(i + 5, u.phone());
        ps.setInt(i + 6, u.emailVerified() ? 1 : 0);
        ps.setInt(i + 7, u.phoneVerified() ? 1 : 0);
        ps.setInt(i + 8, u.mustChangePassword() ? 1 : 0);
        ps.setString(i + 9, u.createdAt().toString());
        ps.setString(i + 10, u.updatedAt().toString());
        ps.setString(i + 11, u.lastLoginAt() == null ? null : u.lastLoginAt().toString());
    }

    private static User map(ResultSet r) throws SQLException {
        String last = r.getString("last_login_at");
        return new User(r.getLong("id"), r.getString("username"), r.getString("email"), r.getString("password_hash"),
                Role.valueOf(r.getString("role")), UserStatus.valueOf(r.getString("status")), r.getString("phone"),
                r.getInt("email_verified") == 1, r.getInt("phone_verified") == 1, r.getInt("must_change_password") == 1,
                Instant.parse(r.getString("created_at")), Instant.parse(r.getString("updated_at")), last == null ? null : Instant.parse(last));
    }
}
