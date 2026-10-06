package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A autoridade detecta (ou falha fechada diante de) edição, inserção, troca de hash, rollback, remoção e perda de âncora — sem confiar em permissão de arquivo. */
class AuthorityStoreTest {
    private Path dir;
    private Path file;
    private MemoryAnchor anchor;
    private AuthorityStore store;
    private AuthorityAdmin admin;
    private final PasswordVerifier pw = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));
    private String adminId;
    private String userId;

    @BeforeEach
    void up() throws Exception {
        dir = Files.createTempDirectory(Path.of("/tmp"), "au");
        file = dir.resolve("authority").resolve("authority.json");
        anchor = new MemoryAnchor();
        store = AuthorityStore.open(file, anchor);
        assertEquals(AuthorityStore.Status.UNINITIALIZED, store.status());
        store.initialize();
        admin = new AuthorityAdmin(store, pw, Clock.systemUTC());
        adminId = admin.createAccount("ADMIN_USER", "admin-test-password-1".toCharArray(), Role.ADMIN).id();
        userId = admin.createAccount("normal_user", "normal-test-password-1".toCharArray(), Role.USER).id();
    }

    @AfterEach
    void down() throws Exception {
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private String read() throws Exception {
        return Files.readString(file);
    }

    private void write(String s) throws Exception {
        Files.writeString(file, s);
    }

    private AuthorityStore reopen() {
        return AuthorityStore.open(file, anchor);
    }

    private void assertUntrusted(AuthorityStore s, String reason) {
        assertEquals(AuthorityStore.Status.UNTRUSTED, s.status());
        assertEquals(reason, s.reason());
        assertEquals(reason, assertThrows(AuthorityException.class, s::current).code, "an untrusted authority answers nothing");
        assertEquals(reason, assertThrows(AuthorityException.class, () -> s.mutate(x -> x)).code, "nor accepts changes");
    }

    @Test
    void legitimateStateSurvivesRestartAndIsFilePrivate() throws Exception {
        AuthorityStore again = reopen();
        assertEquals(AuthorityStore.Status.TRUSTED, again.status());
        var st = again.current();
        assertEquals(3, st.version(), "init=1, two accounts created");
        assertEquals(Role.ADMIN, st.byUsername("admin_user").orElseThrow().role());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        assertFalse(st.accounts().get(0).toString().contains("argon2"), "toString never prints the verifier");
    }

    @Test
    void roleEditIsDetected() throws Exception {
        write(read().replace("\"role\":\"USER\"", "\"role\":\"ADMIN\""));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void enablingADisabledAccountOrFlippingEnabledIsDetected() throws Exception {
        admin.setEnabled(userId, false);
        assertTrue(read().contains("\"enabled\":false"));
        write(read().replace("\"enabled\":false", "\"enabled\":true"));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void credentialVersionEditIsDetected() throws Exception {
        write(read().replaceFirst("\"credentialVersion\":1", "\"credentialVersion\":9"));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void insertionOfAFakeAdminIsDetected() throws Exception {
        String fake = "{\"id\":\"" + "a".repeat(32) + "\",\"username\":\"evil\",\"role\":\"ADMIN\",\"enabled\":true,\"credentialVersion\":1,\"passwordHash\":\"$argon2id$v=19$m=8,t=1,p=1$c2FsdHNhbHRzYWx0$"
                + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]) + "\",\"createdAtMs\":1}";
        write(read().replace("\"accounts\":[", "\"accounts\":[" + fake + ","));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void passwordHashReplacementIsDetected() throws Exception {
        String attackerHash = pw.hash("attacker-chosen-password".toCharArray());
        String content = read();
        int i = content.indexOf("$argon2id$");
        int j = content.indexOf('"', i);
        write(content.substring(0, i) + attackerHash + content.substring(j));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void anAttackerWhoRecomputesTheMacWithTheirOwnKeyIsStillRejected() throws Exception {
        // o atacante edita o papel E calcula um MAC válido com uma chave que ELE escolheu: a chave de verdade só existe na âncora
        String edited = read().replace("\"role\":\"USER\"", "\"role\":\"ADMIN\"");
        var parsed = AuthorityCodec.parse(edited.getBytes(StandardCharsets.UTF_8));
        write(new String(AuthorityCodec.encodeFile(parsed.state(), new byte[32]), StandardCharsets.UTF_8));
        assertUntrusted(reopen(), "mac_invalid");
    }

    @Test
    void rollbackToAnOldButValidSnapshotIsDetected() throws Exception {
        String oldAdminSnapshot = read(); // versão 3: o usuário ainda é USER e o admin existe
        admin.setRole(adminId, Role.USER); // rebaixado: v4
        admin.setEnabled(userId, false); // desabilitado: v5
        write(oldAdminSnapshot); // o atacante restaura o instantâneo antigo (MAC válido, mas versão < âncora)
        assertUntrusted(reopen(), "rollback");
        // e com o serviço já em execução (sem reabrir): a próxima leitura reverifica
        write(oldAdminSnapshot);
        AuthorityStore running = AuthorityStore.open(file, anchor);
        assertEquals("rollback", running.reason());
    }

    @Test
    void rollbackWhileTheServiceIsRunningIsCaughtOnTheNextRead() throws Exception {
        AuthorityStore live = reopen();
        String old = read();
        new AuthorityAdmin(live, pw, Clock.systemUTC()).setEnabled(userId, false);
        assertFalse(live.current().byId(userId).orElseThrow().enabled());
        write(old);
        assertEquals("rollback", assertThrows(AuthorityException.class, live::current).code);
        assertEquals(AuthorityStore.Status.UNTRUSTED, live.status());
    }

    @Test
    void anEditWhileRunningIsCaughtOnTheNextRead() throws Exception {
        AuthorityStore live = reopen();
        assertEquals(Role.USER, live.current().byId(userId).orElseThrow().role());
        write(read().replace("\"role\":\"USER\"", "\"role\":\"ADMIN\""));
        assertEquals("mac_invalid", assertThrows(AuthorityException.class, live::current).code);
    }

    @Test
    void deletingTheFileDoesNotResetTheAuthority() throws Exception {
        Files.delete(file);
        assertUntrusted(reopen(), "file_missing");
        assertEquals("already_initialized", assertThrows(AuthorityException.class, () -> reopen().initialize()).code,
                "re-initialising over an existing anchor is refused: no silent reset, no default admin");
    }

    @Test
    void aMissingAnchorWithAnExistingFileIsNotTrusted() throws Exception {
        anchor.wipeForTest(); // sem âncora (ex.: reinício sem cofre): o arquivo existente não vale nada
        assertUntrusted(reopen(), "anchor_missing");
    }

    @Test
    void garbageTruncatedAndSymlinkedFilesAreNotTrusted() throws Exception {
        String ok = read();
        write("not json");
        assertUntrusted(reopen(), "format_invalid");
        write(ok.substring(0, ok.length() / 2));
        assertUntrusted(reopen(), "format_invalid");
        write(ok.replace("\"format\":1", "\"format\":2"));
        assertUntrusted(reopen(), "format_invalid");
        write(ok.replace("\"mac\"", "\"extra\":1,\"mac\""));
        assertUntrusted(reopen(), "format_invalid");
        Files.delete(file);
        Path elsewhere = dir.resolve("elsewhere.json");
        Files.writeString(elsewhere, ok);
        Files.createSymbolicLink(file, elsewhere);
        assertUntrusted(reopen(), "file_not_regular");
    }

    @Test
    void aVersionFarAheadOfTheAnchorIsNotTrusted() throws Exception {
        // arquivo legítimo mais novo (v+3) do que a âncora conhece: só ocorre se a âncora foi revertida/perdida ⇒ falha fechada
        String current = read();
        MemoryAnchor behind = new MemoryAnchor();
        var a = anchor.read().orElseThrow();
        behind.write(new AnchorData(a.key(), a.version() - 2, a.headMac()));
        assertUntrusted(AuthorityStore.open(file, behind), "version_ahead");
        assertEquals(current, read());
    }

    @Test
    void aCrashBetweenTheFileAndTheAnchorIsRecoveredNotExploited() throws Exception {
        anchor.failWrites(true);
        assertEquals("anchor_unavailable", assertThrows(AuthorityException.class, () -> admin.setEnabled(userId, false)).code);
        assertEquals(AuthorityStore.Status.UNTRUSTED, store.status(), "the running store stops trusting itself");
        anchor.failWrites(false);
        AuthorityStore recovered = reopen(); // arquivo em v+1, âncora em v: aceito e reparado
        assertEquals(AuthorityStore.Status.TRUSTED, recovered.status());
        assertFalse(recovered.current().byId(userId).orElseThrow().enabled(), "the committed change is kept");
        assertEquals(AuthorityStore.Status.TRUSTED, reopen().status(), "and the anchor was repaired");
    }

    @Test
    void initializationIsExplicitAndNeverImplicit() throws Exception {
        Path other = dir.resolve("other").resolve("authority.json");
        AuthorityStore fresh = AuthorityStore.open(other, new MemoryAnchor());
        assertEquals(AuthorityStore.Status.UNINITIALIZED, fresh.status());
        assertEquals("uninitialized", assertThrows(AuthorityException.class, fresh::current).code, "no default admin appears by itself");
        assertEquals("already_initialized", assertThrows(AuthorityException.class, store::initialize).code);
    }

    @Test
    void invalidChangesWriteNothing() throws Exception {
        String before = read();
        assertEquals("invalid_change", assertThrows(AuthorityException.class, () -> admin.createAccount("admin_user", "another-long-password-1".toCharArray(), Role.USER)).code);
        assertEquals("invalid_account", assertThrows(AuthorityException.class, () -> admin.createAccount("bad name!", "another-long-password-1".toCharArray(), Role.USER)).code);
        assertEquals("invalid_account", assertThrows(AuthorityException.class, () -> admin.createAccount("short_pw", "short".toCharArray(), Role.USER)).code);
        assertEquals(before, read());
    }

    @Test
    void everySecurityChangeRaisesTheCredentialVersion() throws Exception {
        long v0 = store.current().byId(userId).orElseThrow().credentialVersion();
        admin.setRole(userId, Role.ADMIN);
        long v1 = store.current().byId(userId).orElseThrow().credentialVersion();
        admin.setEnabled(userId, false);
        long v2 = store.current().byId(userId).orElseThrow().credentialVersion();
        admin.changePassword(userId, "a-brand-new-password-1".toCharArray());
        long v3 = store.current().byId(userId).orElseThrow().credentialVersion();
        assertTrue(v0 < v1 && v1 < v2 && v2 < v3);
    }
}
