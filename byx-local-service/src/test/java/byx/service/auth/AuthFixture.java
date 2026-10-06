package byx.service.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Montagem de teste da autoridade: usuários FICTÍCIOS com senhas aleatórias, relógio controlável, âncora em memória e segundo fator falso. */
final class AuthFixture implements AutoCloseable {
    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-06T12:00:00Z");

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

    /** Dublê do provedor (só teste): guarda o último código por conta; nunca vai a IPC. */
    static final class FakeSecondFactor implements SecondFactorProvider {
        final Map<String, String> last = new HashMap<>();
        final List<String> history = new ArrayList<>();
        volatile boolean configured = true;
        volatile boolean failDelivery;

        public boolean configured() {
            return configured;
        }

        public void deliver(String accountId, char[] code) throws DeliveryException {
            if (failDelivery) {
                throw new DeliveryException();
            }
            last.put(accountId, new String(code));
            history.add(new String(code));
        }
    }

    static final PasswordVerifier PW = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));
    final Path dir;
    final TestClock clock = new TestClock();
    final MemoryAnchor anchor = new MemoryAnchor();
    final Path file;
    final AuthorityStore store;
    final AuthorityAdmin admin;
    final FakeSecondFactor second = new FakeSecondFactor();
    final AuthAudit audit = new AuthAudit(clock);
    AuthService auth;
    final String adminPw = "admin-" + java.util.UUID.randomUUID();
    final String userPw = "user-" + java.util.UUID.randomUUID();
    final String disabledPw = "disabled-" + java.util.UUID.randomUUID();
    String adminId;
    String userId;
    String disabledId;

    AuthFixture() throws Exception {
        dir = Files.createTempDirectory(Path.of("/tmp"), "af");
        file = dir.resolve("authority").resolve("authority.json");
        store = AuthorityStore.open(file, anchor);
        store.initialize();
        admin = new AuthorityAdmin(store, PW, clock);
        adminId = admin.createAccount("ADMIN_USER", adminPw.toCharArray(), Role.ADMIN).id();
        userId = admin.createAccount("NORMAL_USER", userPw.toCharArray(), Role.USER).id();
        disabledId = admin.createAccount("DISABLED_USER", disabledPw.toCharArray(), Role.USER).id();
        admin.setEnabled(disabledId, false);
        auth = newService(store);
    }

    AuthService newService(AuthorityStore s) throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(null, s.derivedKey("ratelimit"), s.derivedKey("ratelimit-subject"), clock);
        return new AuthService(s, new AuthorityAdmin(s, PW, clock), PW, limiter, second, AuthPolicy.standard(), audit, clock);
    }

    AuthService.Result loginAdmin(long peer) {
        return auth.login(peer, "admin_user", adminPw.toCharArray());
    }

    AuthService.Result loginUser(long peer) {
        return auth.login(peer, "normal_user", userPw.toCharArray());
    }

    static String token(AuthService.Result r) {
        return (String) r.data().get("session");
    }

    /** Conclui o segundo fator da conta/sessão com o código "entregue" ao provedor falso. */
    String mfa(long peer, String token, String accountId) {
        AuthService.Result begin = auth.beginSecondFactor(peer, token);
        if (!begin.ok()) {
            throw new AssertionError("begin: " + begin.code());
        }
        AuthService.Result v = auth.verifySecondFactor(peer, token, (String) begin.data().get("challenge"), second.last.get(accountId));
        if (!v.ok()) {
            throw new AssertionError("verify: " + v.code());
        }
        return (String) begin.data().get("challenge");
    }

    @Override
    public void close() throws Exception {
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
