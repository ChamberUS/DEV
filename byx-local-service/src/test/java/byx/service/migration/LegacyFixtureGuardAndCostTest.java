package byx.service.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.auth.Account;
import byx.service.auth.AuthAudit;
import byx.service.auth.AuthPolicy;
import byx.service.auth.AuthRateLimiter;
import byx.service.auth.AuthService;
import byx.service.auth.AuthorityAdmin;
import byx.service.auth.AuthorityStore;
import byx.service.auth.MemoryAnchor;
import byx.service.auth.MemoryKeyVault;
import byx.service.auth.NotConfiguredSecondFactor;
import byx.service.auth.PasswordVerifier;
import byx.service.auth.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** O QA da migração SIMULA o banco legado com o algoritmo legado EXATO (nunca com o hasher do serviço) e as tentativas de login custam o mesmo, haja ou não conta/formato. */
class LegacyFixtureGuardAndCostTest {
    private static final PasswordVerifier PV = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));

    @Test
    void syntheticLegacySourcesAreNeverBuiltWithTheServiceHasher() throws IOException {
        String qa = Files.readString(Path.of("src/test/java/byx/service/migration/MigrateQaMain.java"));
        assertTrue(qa.contains("LegacyPanelHash.hash("), "the packaged migration QA builds source hashes with the legacy reference");
        assertFalse(qa.contains(".hash(") && qa.replace("LegacyPanelHash.hash(", "").contains(".hash("), "...and never with PasswordVerifier.hash");
        assertFalse(qa.contains("PasswordVerifier"), "the QA main does not even import the service verifier");
        String test = Files.readString(Path.of("src/test/java/byx/service/migration/MigratorTest.java"));
        int at = test.indexOf("adminHash = ");
        assertTrue(test.substring(at, at + 400).contains("LegacyPanelHash.hash(") && !test.substring(at, at + 400).contains("PV.hash("), "the migrator unit fixture too");
        Set<String> users = new TreeSet<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("LegacyPanelHash")) {
                    users.add(f.getFileName().toString());
                }
            }
        }
        assertEquals(Set.of(), users, "the synthetic legacy oracle and QA main are absent from all production sources");
        assertTrue(Files.exists(Path.of("src/test/java/byx/service/migration/LegacyPanelHash.java")));
        assertTrue(qa.contains("LegacyPanelHash"), "QA still uses the exact legacy algorithm rather than the service hasher");
    }

    @Test
    void everyKindOfAttemptCostsTheSameNumberOfDerivationsAndLegacyIsAlwaysChecked() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "dc");
        try {
            AuthorityStore store = AuthorityStore.open(dir.resolve("a").resolve("authority.bin"), new MemoryAnchor(), new MemoryKeyVault());
            store.initialize();
            AuthorityAdmin admin = new AuthorityAdmin(store, PV, Clock.systemUTC());
            String pw = "correct-horse-1"; // 15 chars: exact and legacy bytes differ
            admin.importAccount(new Account("a".repeat(32), "legacy_user", Role.USER, true, 1, LegacyPanelHash.hash(pw.toCharArray(), 1024, 1, 1), 1, 1, "l@example.test", null, false, false, false, 0));
            admin.importAccount(new Account("b".repeat(32), "exact_user", Role.USER, true, 1, PV.hash(pw.toCharArray()), 1, 2, "e@example.test", null, false, false, false, 0));
            admin.importAccount(new Account("c".repeat(32), "off_user", Role.USER, false, 1, LegacyPanelHash.hash(pw.toCharArray(), 1024, 1, 1), 1, 3, "o@example.test", null, false, false, false, 0));
            var limiter = new AuthRateLimiter(null, store.derivedKey("a"), store.derivedKey("b"), Clock.systemUTC());
            AuthService svc = new AuthService(store, admin, PV, limiter, new NotConfiguredSecondFactor(), AuthPolicy.standard(), new AuthAudit(Clock.systemUTC()), Clock.systemUTC());
            String[][] attempts = {{"legacy_user", pw, "OK"}, {"exact_user", pw, "OK"}, {"legacy_user", "wrong-password-xx", "INVALID_CREDENTIALS"}, {"exact_user", "wrong-password-xx", "INVALID_CREDENTIALS"},
                {"ghost_user", pw, "INVALID_CREDENTIALS"}, {"off_user", pw, "INVALID_CREDENTIALS"}};
            long expected = -1;
            for (String[] a : attempts) {
                long before = PasswordVerifier.derivations();
                assertEquals(AuthService.Code.valueOf(a[2]), svc.login(1, a[0], a[1].toCharArray()).code(), a[0]);
                long cost = PasswordVerifier.derivations() - before;
                assertEquals(2, cost, "exact AND legacy-padded are both derived for " + a[0] + "/" + a[2] + " (no early return depends on the match)");
                expected = expected < 0 ? cost : expected;
                assertEquals(expected, cost, "externally equivalent cost");
            }
        } finally {
            try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void theVerifierTreatsThePasswordLiterallyAndHasNoEarlyReturnOnTheMatch() throws IOException {
        String src = Files.readString(Path.of("src/main/java/byx/service/auth/PasswordVerifier.java"));
        String verify = src.substring(src.indexOf("public boolean verify("), src.indexOf("public boolean verifyDummy"));
        for (String forbidden : new String[] {"trim(", "strip(", "toLowerCase", "toUpperCase", "Normalizer", "replace("}) {
            assertFalse(verify.contains(forbidden), "the password is never altered: " + forbidden);
        }
        assertTrue(verify.contains("MessageDigest.isEqual(expected, gotExact) | MessageDigest.isEqual(expected, gotLegacy)"), "non-short-circuit combination of both candidates");
        assertTrue(verify.indexOf("derive(exact") < verify.indexOf("MessageDigest.isEqual"), "both derivations happen before any comparison");
        assertFalse(verify.contains("return ok ? ") || verify.contains("if (MessageDigest.isEqual(expected, gotExact)) {"), "no early exit on the first match");
    }
}
