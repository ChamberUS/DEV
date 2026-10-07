package panel.security;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Audit log de segurança, separado do log genérico. Nunca recebe senha, OTP ou hash. */
public class SecurityAuditService {
    public record Entry(String ts, String event, String actor, String detail) {
    }

    private final LegacyAuditHistory history;
    private final Clock clock;

    /** O histórico legado é SOMENTE leitura; a escrita de auditoria é do serviço (aqui sempre negada). */
    public SecurityAuditService(LegacyAuditHistory history, Clock clock) {
        this.history = history == null ? LegacyAuditHistory.UNAVAILABLE : history;
        this.clock = clock;
    }

    public void record(AuditEvent event, String actor, String detail) {
        ServerAuthorization.require(ServerOperation.LEGACY_SECURITY_AUDIT_WRITE);
        throw new AccessDeniedException("legacy audit history is read-only"); // inalcançável com DENY_ALL; nunca escreve no banco legado
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
    private Entry present(Entry e) {
        if(!AuditEvent.LOGIN_FAILED.name().equals(e.event())||e.actor()==null)return e;
        if(e.actor().startsWith("attempt:")||e.actor().startsWith("user:")||e.actor().equals("-"))return e;
        if(SAFE_ACTOR.matcher(e.actor()).matches() && history.legacyUsernameExists(e.actor()))return e;
        return new Entry(e.ts(),e.event(),"legacy-attempt",e.detail());
    }

    private List<Entry> entries(List<String[]> rows) {
        List<Entry> l = new ArrayList<>();
        for (String[] r : rows) {
            l.add(present(new Entry(r[0], r[1], r[2], r[3])));
        }
        return l;
    }

    public List<Entry> recent(int limit) {
        return entries(history.recent(limit));
    }

    public List<Entry> recentFor(String actor, int limit) {
        return entries(history.recentFor(actor, limit));
    }
}
