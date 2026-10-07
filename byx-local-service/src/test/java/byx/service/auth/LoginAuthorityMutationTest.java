package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.auth.AuthService.Code;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prova do que um LOGIN_OK realmente escreve na autoridade (instrumentação de teste, dados sintéticos): SOMENTE lastLoginAtMs da conta, a versão do snapshot
 * (+1) e o material criptográfico da nova versão (nonce novo, MAC/âncora). Nada de segurança muda: verificador, credentialVersion, papel, habilitação, contatos,
 * provedores, dispositivos confiáveis, trava de migração. Falha de login, logout e consulta de sessão não escrevem na autoridade.
 */
class LoginAuthorityMutationTest {
    private static final long PEER = 4242;
    private AuthFixture f;

    @BeforeEach
    void up() throws Exception {
        f = new AuthFixture();
        f.admin.setFreeze(true); // o estado real: PREPARED com trava ligada
    }

    @AfterEach
    void down() throws Exception {
        f.close();
    }

    @Test
    void aSuccessfulLoginChangesOnlyLastLoginVersionAndTheSealedBytes() throws Exception {
        AuthorityState before = f.store.current();
        byte[] fileBefore = Files.readAllBytes(f.file);
        long anchorBefore = f.store.current().version();
        assertTrue(before.migrationFreeze());

        assertEquals(Code.OK, f.loginUser(PEER).code());

        AuthorityState after = f.store.current();
        byte[] fileAfter = Files.readAllBytes(f.file);
        assertEquals(before.version() + 1, after.version(), "exactly one version step");
        assertEquals(anchorBefore + 1, after.version());
        assertEquals(before.accounts().size(), after.accounts().size());
        int changed = 0;
        for (Account b : before.accounts()) {
            Account a = after.byId(b.id()).orElseThrow();
            if (a.lastLoginAtMs() != b.lastLoginAtMs()) {
                changed++;
                assertEquals(f.userId, b.id());
                assertTrue(a.lastLoginAtMs() > b.lastLoginAtMs());
            }
            assertEquals(b, a.withLastLogin(b.lastLoginAtMs()), "every other field of every account is identical");
            assertEquals(b.passwordHash(), a.passwordHash());
            assertEquals(b.credentialVersion(), a.credentialVersion());
            assertEquals(b.role(), a.role());
            assertEquals(b.enabled(), a.enabled());
            assertEquals(b.email(), a.email());
            assertEquals(b.phone(), a.phone());
        }
        assertEquals(1, changed, "only the account that logged in");
        assertEquals(before.providers(), after.providers());
        assertEquals(before.devices(), after.devices());
        assertTrue(after.devices().isEmpty(), "no trusted device is created by a login");
        assertEquals(before.migrationFreeze(), after.migrationFreeze());
        assertTrue(after.migrationFreeze());

        assertFalse(Arrays.equals(fileBefore, fileAfter), "sealed file is rewritten");
        int h = AuthorityCodec.HEADER, n = AuthorityCodec.NONCE;
        assertNotEquals(Arrays.toString(Arrays.copyOfRange(fileBefore, h, h + n)), Arrays.toString(Arrays.copyOfRange(fileAfter, h, h + n)), "fresh nonce on every write");
        AuthorityState reopened = AuthorityStore.open(f.file, f.anchor, f.vault).current(); // the anchor accepts the new version
        assertEquals(after.version(), reopened.version());
        assertEquals(new java.util.HashSet<>(after.accounts()), new java.util.HashSet<>(reopened.accounts()), "same accounts (the sealed form is order-normalized)");
        assertEquals(after.providers(), reopened.providers());
        assertEquals(after.migrationFreeze(), reopened.migrationFreeze());
    }

    @Test
    void failedLoginLogoutAndSessionChecksNeverWriteTheAuthority() throws Exception {
        byte[] before = Files.readAllBytes(f.file);
        assertEquals(Code.INVALID_CREDENTIALS, f.auth.login(PEER, "normal_user", "definitely-wrong-password".toCharArray()).code());
        assertArrayEquals(before, Files.readAllBytes(f.file));
        var ok = f.loginUser(PEER);
        byte[] afterLogin = Files.readAllBytes(f.file);
        String token = AuthFixture.token(ok);
        assertEquals(Code.OK, f.auth.sessionStatus(PEER, token).code());
        assertEquals(Code.OK, f.auth.logout(PEER, token).code());
        assertArrayEquals(afterLogin, Files.readAllBytes(f.file), "status and logout do not touch the authority");
    }
}
