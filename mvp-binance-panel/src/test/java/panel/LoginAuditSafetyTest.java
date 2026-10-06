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

    @Test
    void aPasswordTypedInTheUsernameFieldNeverReachesTheLog() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        int before = rawRows(f).size();
        failLogin(f, CANARY);
        List<String> rows = rawRows(f);
        assertEquals(before + 1, rows.size(), "the attempt is still recorded");
        String last = rows.get(rows.size() - 1);
        assertTrue(last.contains("|LOGIN_FAILED|attempt:") && last.endsWith("|invalid credentials"), last);
        assertFalse(String.join("\n", rows).contains("CANARY"), "no raw identifier anywhere in audit_log");
        assertFalse(f.audit.recent(50).toString().contains("CANARY"), "nor through the reader");
    }

    @Test
    void lineBreaksAndControlCharactersCannotForgeEntries() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        int before = rawRows(f).size();
        failLogin(f, "alice\n2026-10-01T00:00:00Z|LOGIN_SUCCESS|boss|role=ADMIN\r\n");
        failLogin(f, "x\u0000\u001b[31m\ty");
        List<String> rows = rawRows(f);
        assertEquals(before + 2, rows.size(), "exactly one row per attempt");
        assertTrue(rows.stream().skip(before).allMatch(r -> r.contains("|LOGIN_FAILED|attempt:") && r.chars().noneMatch(Character::isISOControl)));
        assertFalse(rows.stream().anyMatch(r -> r.contains("LOGIN_SUCCESS|boss|role=ADMIN") && !r.contains("|LOGIN_SUCCESS|boss|role=ADMIN|")), "no forged success line");
        // o próprio gravador também neutraliza controles e limita o tamanho (outras fontes de ator/detalhe)
        f.audit.record(AuditEvent.LOGIN_FAILED, "x\ny", "a\r\nb" + "z".repeat(500));
        String stored = rawRows(f).get(rawRows(f).size() - 1);
        assertTrue(stored.chars().noneMatch(Character::isISOControl) && stored.length() < 260, stored.length() + " chars");
    }

    @Test
    void knownAccountsAreRecordedAsThemselvesAndFingerprintsDoNotLeakOrCollide() throws Exception {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        failLogin(f, "boss");
        failLogin(f, "boss@example.com"); // por e-mail: ainda aparece como a conta, não como o e-mail
        List<String> rows = rawRows(f);
        assertTrue(rows.get(rows.size() - 2).contains("|LOGIN_FAILED|boss|"), rows.get(rows.size() - 2));
        assertTrue(rows.get(rows.size() - 1).contains("|LOGIN_FAILED|boss|") && !rows.get(rows.size() - 1).contains("example.com"));
        failLogin(f, "ghost-1");
        failLogin(f, "ghost-1");
        failLogin(f, "ghost-2");
        List<String> r2 = rawRows(f);
        String a = actor(r2.get(r2.size() - 3)), b = actor(r2.get(r2.size() - 2)), c = actor(r2.get(r2.size() - 1));
        assertEquals(a, b, "repeated attempts correlate within the process");
        assertNotEquals(a, c);
        String unkeyed = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("ghost-1".getBytes()), 0, 6);
        assertNotEquals("attempt:" + unkeyed, a, "the fingerprint is keyed: a guessed password can not be confirmed offline from the log");
    }

    private static String actor(String row) {
        return row.split("\\|")[2];
    }

    @Test
    void legacyRowsAreMaskedOnReadAndNeverRewritten() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.db.with(c -> {
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
