package byx.service.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * REGRESSÃO do bug do primeiro login real: o painel legado derivava o Argon2id sobre o array de apoio INTEIRO do ByteBuffer (com NUL de preenchimento quando a senha tem 10+
 * caracteres) e o verificador do serviço usava só os bytes UTF-8 exatos, então NENHUMA senha migrada conferia. Dados 100% SINTÉTICOS (nada do verificador real).
 */
class LegacyPasswordCompatibilityTest {
    private static final int M = 1024;
    private static final PasswordVerifier PV = new PasswordVerifier(new PasswordVerifier.Params(M, 1, 1));

    @Test
    void thePaddingClassOfBugExistsExactlyFromTenCharacters() {
        for (int n = 1; n <= 40; n++) {
            char[] pw = "a".repeat(n).toCharArray();
            byte[] exact = PasswordVerifier.exactBytes(pw);
            byte[] legacy = PasswordVerifier.legacyPaddedBytes(pw);
            assertEquals(n, exact.length);
            assertEquals((int) (n * 1.1f), legacy.length, "legacy used the whole backing array: capacity (int)(n*1.1) at n=" + n);
            if (n < 10) {
                assertArrayEquals(exact, legacy, "short passwords were identical in both formats");
            } else {
                assertNotEquals(exact.length, legacy.length, "from 10 characters the legacy form carries NUL padding (the old policy required >= 10)");
                for (int i = n; i < legacy.length; i++) {
                    assertEquals(0, legacy[i]);
                }
            }
        }
    }

    @Test
    void everyLegacyFormatHashVerifiesWithTheServiceForAnyLengthAndAlphabet() {
        String[] passwords = {"x", "short-9ch", "exactly10c", "correct-horse-1", "twenty-char-password!", "a-much-longer-passphrase-with-many-characters-1234", "señha-çom-acentuação-é", "密码密码密码密码密码密码", "emoji-😀-password-1",
            "with space inside and end ", " leading-space-pass", "UPPER-lower-MiXeD-99"};
        for (String p : passwords) {
            String legacyHash = LegacyPanelHash.hash(p.toCharArray(), M, 1, 1);
            assertTrue(PV.verify(p.toCharArray(), legacyHash), "legacy hash for: " + p.length() + " chars");
            assertFalse(PV.verify((p + "x").toCharArray(), legacyHash), "a different password never matches");
            assertFalse(PV.verify(p.trim().equals(p) ? (p + " ").toCharArray() : p.trim().toCharArray(), legacyHash), "whitespace is literal: no trimming");
            String folded = p.toUpperCase().equals(p) ? p.toLowerCase() : p.toUpperCase();
            if (!folded.equals(p)) {
                assertFalse(PV.verify(folded.toCharArray(), legacyHash), "no case folding of the password");
            }
            String serviceHash = PV.hash(p.toCharArray());
            assertTrue(PV.verify(p.toCharArray(), serviceHash), "hashes created by the service still verify");
        }
    }

    @Test
    void theMetadataIsParsedAsTheLegacyWroteIt() {
        String h = LegacyPanelHash.hash("correct-horse-1".toCharArray(), 19_456, 2, 1);
        assertTrue(PasswordVerifier.compatible(h));
        assertEquals("argon2id v=19 m=19456,t=2,p=1", PasswordVerifier.algorithmOf(h));
        assertEquals(97, h.length(), "same shape as the real legacy hashes (16-byte salt, 32-byte hash, unpadded base64)");
    }

    @Test
    void aPasswordWithNulIsRefusedSoThePaddedCandidateNeverCollides() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("/tmp"), "lp");
        try {
            AuthorityStore store = AuthorityStore.open(dir.resolve("a").resolve("authority.bin"), new MemoryAnchor(), new MemoryKeyVault());
            store.initialize();
            AuthorityAdmin admin = new AuthorityAdmin(store, PV, Clock.systemUTC());
            String pw = "correct-horse-1";
            admin.importAccount(new Account("a".repeat(32), "legacy_user", Role.ADMIN, true, 1, LegacyPanelHash.hash(pw.toCharArray(), M, 1, 1), 1, 1, "Legacy.User@Example.test", "+5511999990000", false, false, false, 0));
            var limiter = new AuthRateLimiter(null, store.derivedKey("a"), store.derivedKey("b"), Clock.systemUTC());
            AuthService svc = new AuthService(store, admin, PV, limiter, new NotConfiguredSecondFactor(), AuthPolicy.standard(), new AuthAudit(Clock.systemUTC()), Clock.systemUTC());
            assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(1, "legacy_user", (pw + "\0").toCharArray()).code(), "the padded form typed literally is not accepted");
            assertEquals(AuthService.Code.OK, svc.login(1, "legacy_user", pw.toCharArray()).code(), "the real password of a MIGRATED legacy verifier logs in");
            // caminho do identificador: usuário, e-mail, caixa mista; espaços ao redor do identificador são ignorados, os da senha NÃO
            for (String id : new String[] {"legacy_user", "LEGACY_USER", "legacy.user@example.test", "Legacy.User@Example.TEST", "  legacy_user  ", "\tlegacy.user@example.test "}) {
                assertEquals(AuthService.Code.OK, svc.login(2, id, pw.toCharArray()).code(), "identifier [" + id.strip() + "]");
            }
            assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(3, "legacy_user", (pw + " ").toCharArray()).code(), "a trailing space in the PASSWORD is literal");
            assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(3, "legacy_user", (" " + pw).toCharArray()).code());
            assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(3, "legacy_user", pw.toUpperCase().toCharArray()).code());
            assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(3, "legacy_user", pw.getBytes(StandardCharsets.UTF_8).length > 0 ? "wrong-password-xx".toCharArray() : new char[0]).code());
        } finally {
            try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
