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
        ServerAuthorization.require(ServerOperation.LEGACY_SECURITY_AUDIT_WRITE);
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(ts,event,actor,detail) VALUES (?,?,?,?)")) {
                ps.setString(1, clock.instant().toString());
                ps.setString(2, event.name());
                ps.setString(3, safe(actor));
                ps.setString(4, safe(detail));
                return ps.executeUpdate();
            }
        });
    }

    private static String safe(String value) {
        if(value==null)return null;
        String v = value.replaceAll("\\p{Cntrl}+", " ") // quebras de linha e controles não forjam linhas de log
                .replaceAll("[^\\s@]+@[^\\s@]+", "[email redacted]")
                .replaceAll("\\+[0-9]{8,15}", "[phone redacted]");
        return v.length() > 200 ? v.substring(0, 200) : v;
    }

    private static final java.util.regex.Pattern SAFE_ACTOR = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}|(user|attempt):[A-Za-z0-9]{1,32}|-");

    /**
     * Linhas antigas de LOGIN_FAILED guardaram o identificador digitado (que pode ter sido uma senha). Elas NÃO são apagadas nem
     * reescritas: só são mascaradas na leitura quando o ator não é um usuário existente nem um formato seguro.
     */
    private Entry present(java.sql.Connection c, Entry e) throws java.sql.SQLException {
        if(!AuditEvent.LOGIN_FAILED.name().equals(e.event())||e.actor()==null)return e;
        if(e.actor().startsWith("attempt:")||e.actor().startsWith("user:")||e.actor().equals("-"))return e;
        if(SAFE_ACTOR.matcher(e.actor()).matches()) {
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM users WHERE username=? COLLATE NOCASE")) {
                ps.setString(1,e.actor());
                try(ResultSet r=ps.executeQuery()) { if(r.next())return e; }
            }
        }
        return new Entry(e.ts(),e.event(),"legacy-attempt",e.detail());
    }

    public List<Entry> recent(int limit) {
        return db.with(c -> {
            List<Entry> l = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts,event,actor,detail FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                ResultSet r = ps.executeQuery();
                while (r.next()) {
                    l.add(present(c, new Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4))));
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
                    l.add(present(c, new Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4))));
                }
            }
            return l;
        });
    }
}
