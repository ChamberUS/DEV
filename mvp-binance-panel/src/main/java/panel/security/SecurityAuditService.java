package panel.security;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Audit log de segurança, separado do log genérico. Nunca recebe senha, OTP ou hash. */
public class SecurityAuditService {
    public record Entry(String ts, String event, String actor, String detail) {
    }

    private final Database db;
    private final Clock clock;

    public SecurityAuditService(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void record(AuditEvent event, String actor, String detail) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(ts,event,actor,detail) VALUES (?,?,?,?)")) {
                ps.setString(1, clock.instant().toString());
                ps.setString(2, event.name());
                ps.setString(3, actor);
                ps.setString(4, detail);
                return ps.executeUpdate();
            }
        });
    }

    public List<Entry> recent(int limit) {
        return db.with(c -> {
            List<Entry> l = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts,event,actor,detail FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                ResultSet r = ps.executeQuery();
                while (r.next()) {
                    l.add(new Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4)));
                }
            }
            return l;
        });
    }

    public List<Entry> recentFor(String actor, int limit) {
        return db.with(c -> {
            List<Entry> l = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts,event,actor,detail FROM audit_log WHERE actor=? COLLATE NOCASE ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, actor);
                ps.setInt(2, limit);
                ResultSet r = ps.executeQuery();
                while (r.next()) {
                    l.add(new Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4)));
                }
            }
            return l;
        });
    }
}
