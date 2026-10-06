package panel.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService.Failure;
import panel.auth.AuthService.LoginException;
import panel.security.Database;
import panel.security.Role;
import panel.security.SecurityAuditService;
import panel.user.SqliteUserRepository;
import panel.user.User;
import panel.user.UserStatus;

/** L4: o limitador persiste, não vaza existência, expira, é limitado e não vira lockout permanente nem DoS fácil. */
class PersistentRateLimiterTest {
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private Path home;
    private Path file;
    private final TestClock clock = new TestClock();
    private final List<Database> open = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        home = Files.createTempDirectory(Path.of("/tmp"), "rl");
        file = home.resolve("data").resolve("panel.db");
    }

    @AfterEach
    void down() throws Exception {
        open.forEach(Database::close);
        try (var walk = Files.walk(home)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private Database db() {
        Database d = Database.open(file);
        open.add(d);
        return d;
    }

    private PersistentRateLimiter limiter(Database d) {
        return new PersistentRateLimiter(d, clock, PersistentRateLimiter.Policy.login(), "login");
    }

    private void fail(RateLimiter l, String key, int n) {
        for (int i = 0; i < n; i++) {
            l.recordFailure(key);
        }
    }

    private long rows(Database d) {
        return d.with(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM rate_limits")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void protectionSurvivesRestart() {
        Database d1 = db();
        fail(limiter(d1), "alice", 5);
        assertTrue(limiter(d1).blockedFor("alice").isPresent());
        d1.close();
        open.clear();
        PersistentRateLimiter afterRestart = limiter(db()); // processo novo: espelho vazio, mesmo arquivo
        Duration left = afterRestart.blockedFor("alice").orElseThrow();
        assertTrue(left.toSeconds() > 20 && left.toSeconds() <= 30, "the 5th failure arms 30 s and a restart does not clear it: " + left);
        assertTrue(afterRestart.blockedFor("bob").isEmpty());
    }

    @Test
    void delayIsProgressiveCappedAndNeverPermanent() {
        PersistentRateLimiter l = limiter(db());
        fail(l, "alice", 4);
        assertTrue(l.blockedFor("alice").isEmpty(), "free attempts first");
        l.recordFailure("alice");
        assertEquals(30, l.blockedFor("alice").orElseThrow().toSeconds());
        clock.advance(Duration.ofSeconds(31));
        l.recordFailure("alice");
        assertEquals(60, l.blockedFor("alice").orElseThrow().toSeconds());
        clock.advance(Duration.ofSeconds(61));
        l.recordFailure("alice");
        assertEquals(120, l.blockedFor("alice").orElseThrow().toSeconds());
        for (int i = 0; i < 50; i++) { // insistência: o teto vale
            clock.advance(l.blockedFor("alice").map(d -> d.plusSeconds(1)).orElse(Duration.ZERO));
            l.recordFailure("alice");
            assertTrue(l.blockedFor("alice").orElseThrow().compareTo(Duration.ofMinutes(5)) <= 0, "never above the 5 min cap");
        }
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(l.blockedFor("alice").isEmpty(), "the block ends by itself: no permanent lockout");
    }

    @Test
    void attemptsDuringABlockDoNotExtendIt() throws Exception {
        Database d = db();
        PersistentRateLimiter l = limiter(d);
        TestAuth a = new TestAuth(d, l);
        fail(l, "alice", 5);
        Duration before = l.blockedFor("alice").orElseThrow();
        for (int i = 0; i < 20; i++) { // 20 tentativas durante o bloqueio: recusadas sem verificar, sem contar
            assertEquals(Failure.RATE_LIMITED, assertThrows(LoginException.class, () -> a.auth.login("alice", "whatever-1".toCharArray())).failure);
        }
        assertEquals(before.toSeconds(), l.blockedFor("alice").orElseThrow().toSeconds(), "no extension while blocked");
    }

    @Test
    void unknownAccountsBehaveExactlyLikeKnownOnes() {
        Database d = db();
        TestAuth a = new TestAuth(d, limiter(d));
        a.seed("alice", "correct-horse-1");
        List<String> shapes = new ArrayList<>();
        for (String who : List.of("alice", "ghost-nobody")) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                try {
                    a.auth.login(who, "bad-password-9".toCharArray());
                } catch (LoginException e) {
                    s.append(e.failure).append(e.retryAfter == null ? "" : ":" + (e.retryAfter.toSeconds() > 0)).append(' ');
                }
            }
            shapes.add(s.toString());
        }
        assertEquals(shapes.get(0), shapes.get(1), "same sequence of outcomes whether or not the account exists");
        assertTrue(shapes.get(0).contains("RATE_LIMITED"));
    }

    @Test
    void otherIdentifiersDoNotInheritTheCounter() {
        PersistentRateLimiter l = limiter(db());
        fail(l, "alice", 6);
        assertTrue(l.blockedFor("alice").isPresent());
        for (String other : List.of("bob", "alice2", "alice@example.com", "ALICE ".trim() + "x", "")) {
            assertTrue(l.blockedFor(other).isEmpty(), other);
        }
        assertTrue(l.blockedFor("  ALICE ").isPresent(), "case and surrounding spaces are the same subject");
    }

    @Test
    void successClearsAndOldFailuresDecay() {
        PersistentRateLimiter l = limiter(db());
        fail(l, "alice", 5);
        l.recordSuccess("alice");
        assertTrue(l.blockedFor("alice").isEmpty());
        fail(l, "alice", 4);
        clock.advance(Duration.ofMinutes(31)); // decaimento: a janela de falhas expirou
        l.recordFailure("alice");
        assertTrue(l.blockedFor("alice").isEmpty(), "4 old + 1 new is one failure, not five");
        fail(l, "bob", 5);
        clock.advance(Duration.ofMinutes(31));
        assertTrue(l.blockedFor("bob").isEmpty());
    }

    @Test
    void storesOnlyAFingerprintNeverTheTypedText() throws Exception {
        Database d = db();
        PersistentRateLimiter l = limiter(d);
        String typed = "Sup3r-S3cret-Typed-As-Username";
        fail(l, typed, 5);
        String dump = d.with(c -> {
            StringBuilder sb = new StringBuilder();
            for (String t : List.of("rate_limits", "meta")) {
                try (var rs = c.createStatement().executeQuery("SELECT * FROM " + t)) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        for (int i = 1; i <= cols; i++) {
                            sb.append(rs.getString(i)).append('|');
                        }
                    }
                }
            }
            return sb.toString();
        });
        assertFalse(dump.toLowerCase().contains(typed.toLowerCase()), "no raw text in the tables");
        assertFalse(java.util.Arrays.toString(Files.readAllBytes(file)).isEmpty());
        assertFalse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase().contains(typed.toLowerCase()), "nor anywhere in the database file");
    }

    @Test
    void hostileInputIsInertAndNeverReachesLogsOrSql() {
        Database d = db();
        TestAuth a = new TestAuth(d, limiter(d));
        a.seed("alice", "correct-horse-1");
        List<String> hostile = List.of("'; DROP TABLE users;--", "\" OR 1=1 --", "x\u0000y", "a".repeat(10_000), "‮\u0001\u0007😀", "%s%n{0}${jndi:ldap://x}");
        for (String h : hostile) {
            for (int i = 0; i < 6; i++) {
                assertThrows(LoginException.class, () -> a.auth.login(h, "nope-nope-1".toCharArray()));
            }
        }
        assertEquals(1L, (long) d.<Long>with(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
                rs.next();
                return rs.getLong(1);
            }
        }), "users table intact");
        assertTrue(rows(d) <= hostile.size());
        String audit = a.audit.recent(200).toString();
        for (String h : hostile) {
            assertFalse(audit.contains(h.substring(0, Math.min(h.length(), 12))) && h.length() > 3, "the typed text never goes to the audit log: " + h.length());
        }
        // e o usuário legítimo continua entrando (nenhum efeito colateral nas outras chaves)
        assertEquals("alice", a.auth.login("alice", "correct-horse-1".toCharArray()).username());
    }

    @Test
    void clockGoingBackwardsNeverExtendsABlockBeyondTheCap() {
        PersistentRateLimiter l = limiter(db());
        fail(l, "alice", 12);
        assertTrue(l.blockedFor("alice").orElseThrow().compareTo(Duration.ofMinutes(5)) <= 0);
        clock.advance(Duration.ofDays(-30)); // o relógio recuou um mês
        Duration left = l.blockedFor("alice").orElse(Duration.ZERO);
        assertTrue(left.compareTo(Duration.ofMinutes(5)) <= 0, "still bounded by the cap: " + left);
        clock.advance(Duration.ofMinutes(6));
        assertTrue(l.blockedFor("alice").isEmpty(), "and it ends");
    }

    @Test
    void corruptedStoredValuesAreSanitizedNotTrusted() {
        Database d = db();
        PersistentRateLimiter l = limiter(d);
        l.recordFailure("alice"); // cria o sal e uma linha
        d.with(c -> {
            c.createStatement().executeUpdate("UPDATE rate_limits SET failures=-5, last_at=-1, next_allowed_at=9223372036854775000");
            return null;
        });
        Duration left = limiter(d).blockedFor("alice").orElse(Duration.ZERO);
        assertTrue(left.compareTo(Duration.ofMinutes(5)) <= 0, "a far-future stored time is clamped to the cap: " + left);
        d.with(c -> {
            c.createStatement().executeUpdate("UPDATE rate_limits SET failures=2000000000, last_at=9223372036854775000, next_allowed_at=-9");
            return null;
        });
        assertTrue(limiter(d).blockedFor("alice").isEmpty(), "negative next-allowed is not a block");
    }

    @Test
    void storageFailureStillEnforcesFromMemory() {
        Database d = db();
        PersistentRateLimiter l = limiter(d);
        d.close(); // disco/banco indisponível
        fail(l, "alice", 5);
        assertTrue(l.blockedFor("alice").isPresent(), "fail-safe: the in-memory mirror keeps enforcing");
        assertTrue(l.blockedFor("bob").isEmpty());
    }

    @Test
    void storageIsBoundedAndBlockedRowsAreNeverEvicted() {
        Database d = db();
        PersistentRateLimiter l = new PersistentRateLimiter(d, clock, new PersistentRateLimiter.Policy(4, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30), 20), "login");
        fail(l, "victim", 6);
        for (int i = 0; i < 200; i++) { // inundação de nomes aleatórios
            l.recordFailure("random-" + i);
        }
        assertTrue(rows(d) <= 20, "rows: " + rows(d));
        assertTrue(l.blockedFor("victim").isPresent(), "a blocked row is never pushed out by a flood");
    }

    @Test
    void scopesDoNotShareCounters() {
        Database d = db();
        PersistentRateLimiter login = limiter(d);
        PersistentRateLimiter other = new PersistentRateLimiter(d, clock, PersistentRateLimiter.Policy.login(), "contact");
        fail(login, "alice", 6);
        assertTrue(other.blockedFor("alice").isEmpty());
    }

    // ---- apoio -------------------------------------------------------------------------------------------------------------------

    private final class TestAuth {
        final AuthService auth;
        final SqliteUserRepository users;
        final SecurityAuditService audit;
        final PasswordHasher hasher = new PasswordHasher(1024, 1, 1);

        TestAuth(Database d, RateLimiter l) {
            users = new SqliteUserRepository(d);
            audit = new SecurityAuditService(d, clock);
            auth = new AuthService(users, hasher, new SessionManager(), l, audit, clock);
        }

        void seed(String name, String pw) {
            users.insert(new User(0, name, name + "@example.com", hasher.hash(pw.toCharArray()), Role.USER, UserStatus.ACTIVE, null, false, false, false, clock.instant(), clock.instant(), null));
        }
    }
}
