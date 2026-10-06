package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService.LoginException;
import panel.security.AuditEvent;

/** L5: o texto digitado no login nunca vai para o log de auditoria, a tentativa continua registrada e o histórico antigo não é reescrito. */
class LoginAuditSafetyTest {
    private static final char[] PW = "correct-horse-1".toCharArray();
    private static final String CANARY = "CANARY-hunter2-Pass!";

    private static List<String> rawRows(AuthFixture f) {
        return f.db.with(c -> {
            List<String> out = new ArrayList<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT ts||'|'||event||'|'||IFNULL(actor,'')||'|'||IFNULL(detail,'') FROM audit_log ORDER BY id")) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
            return out;
        });
    }

    private static void failLogin(AuthFixture f, String identifier) {
        assertThrows(LoginException.class, () -> f.auth.login(identifier, "wrong-password-1".toCharArray()));
    }

    

    

    

    private static String actor(String row) {
        return row.split("\\|")[2];
    }

    @Test
    void legacyRowsAreMaskedOnReadAndNeverRewritten() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.db.with(c -> { // o HISTÓRICO legado: a conta "boss" existe na tabela users do banco antigo (só lida, para mascarar linhas antigas)
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username,email,password_hash,role,status,created_at,updated_at) VALUES ('boss','boss@example.com','x','ADMIN','ACTIVE','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')")) {
                ps.executeUpdate();
            }
            for (String[] row : new String[][] {{"CANARY-OLD-pw", "x"}, {"boss", "y"}, {"line\nbreak", "z"}}) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(ts,event,actor,detail) VALUES (?,?,?,?)")) {
                    ps.setString(1, "2026-10-01T00:00:00Z");
                    ps.setString(2, "LOGIN_FAILED");
                    ps.setString(3, row[0]);
                    ps.setString(4, "invalid credentials " + row[1]);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        var shown = f.audit.recent(10);
        assertTrue(shown.stream().noneMatch(e -> e.actor() != null && (e.actor().contains("CANARY") || e.actor().contains("\n"))), "old raw identifiers are masked in every reader");
        assertTrue(shown.stream().anyMatch(e -> "legacy-attempt".equals(e.actor())));
        assertTrue(shown.stream().anyMatch(e -> "boss".equals(e.actor())), "a real account name stays visible");
        assertTrue(rawRows(f).stream().anyMatch(r -> r.contains("|CANARY-OLD-pw|")), "the history on disk is untouched: nothing deleted, nothing rewritten");
        assertEquals(3, rawRows(f).stream().filter(r -> r.contains("2026-10-01T00:00:00Z|LOGIN_FAILED")).count());
    }

    @Test
    void theLoginErrorNeverEchoesTheIdentifier() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        LoginException e = assertThrows(LoginException.class, () -> f.auth.login(CANARY, "x".toCharArray()));
        assertFalse((e.getMessage() + e.failure + e.retryAfter).contains("CANARY"));
    }
}
