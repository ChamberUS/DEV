package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A autoridade é CIFRADA (AES-256-GCM, chave própria no cofre do serviço) e detecta, ou falha fechada diante de, qualquer alteração do
 * snapshot, rollback, remoção e perda de âncora/chave — sem confiar em permissão de arquivo nem em segredo do mesmo usuário.
 */
class AuthorityStoreTest {
    private static final int NONCE_AT = AuthorityCodec.HEADER;
    private Path dir;
    private Path file;
    private MemoryAnchor anchor;
    private MemoryKeyVault vault;
    private AuthorityStore store;
    private AuthorityAdmin admin;
    private final PasswordVerifier pw = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));
    private String adminId;
    private String userId;

    @BeforeEach
    void up() throws Exception {
        dir = Files.createTempDirectory(Path.of("/tmp"), "au");
        file = dir.resolve("authority").resolve("authority.bin");
        anchor = new MemoryAnchor();
        vault = new MemoryKeyVault();
        store = AuthorityStore.open(file, anchor, vault);
        assertEquals(AuthorityStore.Status.UNINITIALIZED, store.status());
        store.initialize();
        admin = new AuthorityAdmin(store, pw, Clock.systemUTC());
        adminId = admin.createAccount("admin_user", "admin-test-password-1".toCharArray(), Role.ADMIN).id();
        userId = admin.createAccount("normal_user", "normal-test-password-1".toCharArray(), Role.USER).id();
    }

    @AfterEach
    void down() throws Exception {
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private byte[] read() throws Exception {
        return Files.readAllBytes(file);
    }

    private void write(byte[] b) throws Exception {
        Files.write(file, b);
    }

    private AuthorityStore reopen() {
        return AuthorityStore.open(file, anchor, vault);
    }

    private void assertUntrusted(AuthorityStore s, String reason) {
        assertEquals(AuthorityStore.Status.UNTRUSTED, s.status());
        assertEquals(reason, s.reason());
        assertEquals(reason, assertThrows(AuthorityException.class, s::current).code, "an untrusted authority answers nothing");
        assertEquals(reason, assertThrows(AuthorityException.class, () -> s.mutate(x -> x)).code, "nor accepts changes");
    }

    private byte[] flipped(int at) throws Exception {
        byte[] b = read();
        b[at] ^= 0x01;
        return b;
    }

    // ---- operação legítima -----------------------------------------------------------------------------------------------------

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

    // ---- confidencialidade (mesmo usuário lê o arquivo) -----------------------------------------------------------------------

    @Test
    void aSameUserReaderOfTheFindsNoStructuralPlaintext() throws Exception {
        admin.createAccount("confidential-user-canary", "canary-password-value-1".toCharArray(), Role.ADMIN);
        String verifier = store.current().byUsername("confidential-user-canary").orElseThrow().passwordHash();
        String salt = verifier.split("\\$")[4];
        byte[] raw = read(); // o que um processo do mesmo usuário vê ao copiar, hex-dump ou rodar strings
        String asText = new String(raw, StandardCharsets.ISO_8859_1);
        String asHex = HexFormat.of().formatHex(raw);
        for (String canary : new String[] {"confidential-user-canary", "admin_user", "normal_user", "argon2", verifier, salt, "ADMIN", "USER", "role", "username", "passwordHash", "credentialVersion",
            "enabled", "accounts", "{\"", "canary-password-value-1"}) {
            assertFalse(asText.contains(canary), "plaintext '" + canary + "' is visible in the snapshot");
            assertFalse(asHex.contains(HexFormat.of().formatHex(canary.getBytes(StandardCharsets.UTF_8))), "hex of '" + canary + "'");
        }
        assertEquals("BYXA", new String(raw, 0, 4, StandardCharsets.US_ASCII), "only the fixed magic is readable");
        assertTrue(raw.length >= AuthorityCodec.MIN_FILE);
    }

    @Test
    void theEncryptionKeyIsIndependentOfTheMacKeyAndNeverInTheFile() throws Exception {
        byte[] enc = vault.read().orElseThrow();
        byte[] mac = anchor.read().orElseThrow().key();
        assertEquals(32, enc.length);
        assertFalse(Arrays.equals(enc, mac), "separate keys");
        assertFalse(Arrays.equals(enc, store.derivedKey("anything")), "and not a derivation of the MAC key");
        String hex = HexFormat.of().formatHex(read());
        assertFalse(hex.contains(HexFormat.of().formatHex(enc)) || hex.contains(HexFormat.of().formatHex(mac)), "no key material in the snapshot");
    }

    @Test
    void everyWriteUsesAFreshNonceAndProducesDifferentCiphertext() throws Exception {
        Set<String> nonces = new HashSet<>();
        Set<String> bodies = new HashSet<>();
        for (int i = 0; i < 24; i++) {
            admin.bumpCredentialVersion(userId);
            byte[] b = read();
            assertTrue(nonces.add(HexFormat.of().formatHex(b, NONCE_AT, NONCE_AT + AuthorityCodec.NONCE)), "nonce reused at write " + i);
            assertTrue(bodies.add(HexFormat.of().formatHex(b, NONCE_AT + AuthorityCodec.NONCE, b.length)), "ciphertext repeated at write " + i);
        }
        // mesmo conteúdo lógico cifrado duas vezes: nonce e texto cifrado diferem
        AuthorityState s = store.current();
        byte[] k = vault.read().orElseThrow();
        byte[] a = AuthorityCodec.seal(s, k, new java.security.SecureRandom());
        byte[] b = AuthorityCodec.seal(s, k, new java.security.SecureRandom());
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, NONCE_AT, NONCE_AT + 12), Arrays.copyOfRange(b, NONCE_AT, NONCE_AT + 12)));
        assertFalse(Arrays.equals(a, b));
    }

    // ---- negativos criptográficos: tudo falha fechado, sem revelar nada ---------------------------------------------------------

    @Test
    void anyModificationOfTheSnapshotFailsClosed() throws Exception {
        int len = read().length;
        write(flipped(AuthorityCodec.HEADER + AuthorityCodec.NONCE + 3));
        assertUntrusted(reopen(), "decrypt_failed"); // bit do texto cifrado
        write(flipped(len - 1));
        assertUntrusted(reopen(), "decrypt_failed"); // etiqueta
        write(flipped(len - 17));
        assertUntrusted(reopen(), "decrypt_failed"); // borda texto/etiqueta
        write(flipped(NONCE_AT));
        assertUntrusted(reopen(), "decrypt_failed"); // nonce
        write(flipped(13));
        assertUntrusted(reopen(), "decrypt_failed"); // versão da autoridade no cabeçalho (AAD)
        write(flipped(6));
        assertUntrusted(reopen(), "decrypt_failed"); // idem, byte alto
        write(flipped(5));
        assertUntrusted(reopen(), "format_invalid"); // versão de formato
        write(flipped(0));
        assertUntrusted(reopen(), "format_invalid"); // magic
    }

    @Test
    void truncatedAndOversizedSnapshotsAreRefusedBeforeDecrypting() throws Exception {
        byte[] ok = read();
        write(Arrays.copyOf(ok, ok.length - 10));
        assertUntrusted(reopen(), "decrypt_failed");
        write(Arrays.copyOf(ok, AuthorityCodec.MIN_FILE - 1));
        assertUntrusted(reopen(), "format_invalid");
        write(new byte[0]);
        assertUntrusted(reopen(), "format_invalid");
        write(Arrays.copyOf(ok, AuthorityCodec.MAX_FILE + 1));
        assertUntrusted(reopen(), "file_not_regular"); // limite aplicado antes de ler/alocar/decifrar
        assertEquals("format_invalid", assertThrows(AuthorityCodec.FormatException.class, () -> AuthorityCodec.open(new byte[AuthorityCodec.MAX_FILE + 1], new byte[32], new byte[32])).code);
    }

    @Test
    void aWrongOrMissingOrUnavailableEncryptionKeyFailsClosed() throws Exception {
        byte[] good = vault.read().orElseThrow();
        byte[] other = good.clone();
        other[0] ^= 1;
        vault.replaceForTest(other);
        assertUntrusted(reopen(), "decrypt_failed");
        vault.wipeForTest();
        assertUntrusted(reopen(), "enckey_missing");
        vault.write(good);
        vault.failReads(true);
        assertUntrusted(reopen(), "enckey_unavailable");
        vault.failReads(false);
        assertEquals(AuthorityStore.Status.TRUSTED, reopen().status(), "the right key opens it again");
    }

    @Test
    void failuresRevealNeitherPlaintextNorKeys() throws Exception {
        String canary = "confidential-user-canary";
        admin.createAccount(canary, "canary-password-value-1".toCharArray(), Role.ADMIN);
        String keyHex = HexFormat.of().formatHex(vault.read().orElseThrow());
        write(flipped(AuthorityCodec.HEADER + AuthorityCodec.NONCE + 5));
        AuthorityStore bad = reopen();
        AuthorityException e = assertThrows(AuthorityException.class, bad::current);
        for (String text : new String[] {e.getMessage(), e.code, bad.reason(), String.valueOf(e.getCause()), Arrays.toString(e.getStackTrace())}) {
            assertFalse(text.contains(canary) || text.contains(keyHex) || text.contains("argon2"), "leak in failure: " + text);
        }
        assertEquals(0, e.getStackTrace().length, "no stack, no cause");
    }

    // ---- rollback continua indispensável (AEAD não o resolve) -------------------------------------------------------------------

    @Test
    void rollbackToAnOldButValidlyEncryptedSnapshotIsDetected() throws Exception {
        byte[] old = read(); // v3, autêntico, cifrado com a chave certa
        admin.setRole(adminId, Role.USER);
        admin.setEnabled(userId, false);
        write(old); // o AEAD autentica este arquivo; só a âncora sabe que é velho
        assertUntrusted(reopen(), "rollback");
    }

    @Test
    void rollbackWhileTheServiceIsRunningIsCaughtOnTheNextRead() throws Exception {
        AuthorityStore live = reopen();
        byte[] old = read();
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
        write(flipped(AuthorityCodec.HEADER + AuthorityCodec.NONCE + 9));
        assertEquals("decrypt_failed", assertThrows(AuthorityException.class, live::current).code);
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
        anchor.wipeForTest();
        assertUntrusted(reopen(), "anchor_missing");
    }

    @Test
    void garbageAndSymlinkedFilesAreNotTrusted() throws Exception {
        byte[] ok = read();
        write("not a snapshot at all, just some text of reasonable length".getBytes(StandardCharsets.UTF_8));
        assertUntrusted(reopen(), "format_invalid");
        write("{\"format\":1,\"state\":{},\"mac\":\"x\"}".getBytes(StandardCharsets.UTF_8)); // o formato antigo, em claro, não é aceito
        assertUntrusted(reopen(), "format_invalid");
        Files.delete(file);
        Path elsewhere = dir.resolve("elsewhere.bin");
        Files.write(elsewhere, ok);
        Files.createSymbolicLink(file, elsewhere);
        assertUntrusted(reopen(), "file_not_regular");
    }

    // ---- consistência de queda --------------------------------------------------------------------------------------------------

    @Test
    void aVersionFarAheadOfTheAnchorIsNotTrusted() throws Exception {
        byte[] current = read();
        MemoryAnchor behind = new MemoryAnchor();
        var a = anchor.read().orElseThrow();
        behind.write(new AnchorData(a.key(), a.version() - 2, a.headMac()));
        assertUntrusted(AuthorityStore.open(file, behind, vault), "version_ahead");
        assertArrayEquals(current, read());
    }

    @Test
    void aCrashBetweenTheFileAndTheAnchorIsRecoveredOnlyAfterAuthenticationPasses() throws Exception {
        anchor.failWrites(true);
        assertEquals("anchor_unavailable", assertThrows(AuthorityException.class, () -> admin.setEnabled(userId, false)).code);
        assertEquals(AuthorityStore.Status.UNTRUSTED, store.status(), "the running store stops trusting itself");
        anchor.failWrites(false);
        AuthorityStore recovered = reopen(); // arquivo em v+1 AUTENTICADO, âncora em v: aceito e reparado
        assertEquals(AuthorityStore.Status.TRUSTED, recovered.status());
        assertFalse(recovered.current().byId(userId).orElseThrow().enabled(), "the committed change is kept");
        assertEquals(AuthorityStore.Status.TRUSTED, reopen().status(), "and the anchor was repaired");
    }

    @Test
    void anArbitraryFutureFileIsNeverAcceptedAsACrashResidue() throws Exception {
        // "v+1" forjado: bem formado, versão certa no cabeçalho, mas cifrado com outra chave (o atacante não tem a do cofre)
        AuthorityState cur = store.current();
        AuthorityState forged = new AuthorityState(cur.version() + 1, cur.accounts());
        write(AuthorityCodec.seal(forged, new byte[32], new java.security.SecureRandom()));
        assertUntrusted(reopen(), "decrypt_failed");
        assertEquals(cur.version(), anchor.read().orElseThrow().version(), "and the anchor was NOT advanced by it");
    }

    @Test
    void anAuthenticFileWhoseHeaderVersionDisagreesWithItsPlaintextIsRefused() throws Exception {
        // um snapshot legítimo recifrado com cabeçalho de outra versão: impossível sem a chave; com a chave ainda é recusado por consistência
        byte[] k = vault.read().orElseThrow();
        AuthorityState s = store.current();
        byte[] sealed = AuthorityCodec.seal(s, k, new java.security.SecureRandom());
        sealed[13] = (byte) (sealed[13] + 1); // cabeçalho diz v+1: o AAD não confere ⇒ falha de autenticação
        write(sealed);
        assertUntrusted(reopen(), "decrypt_failed");
    }

    // ---- inicialização, mudanças inválidas, versão de credencial ---------------------------------------------------------------

    @Test
    void initializationIsExplicitAndNeverImplicit() throws Exception {
        Path other = dir.resolve("other").resolve("authority.bin");
        AuthorityStore fresh = AuthorityStore.open(other, new MemoryAnchor(), new MemoryKeyVault());
        assertEquals(AuthorityStore.Status.UNINITIALIZED, fresh.status());
        assertEquals("uninitialized", assertThrows(AuthorityException.class, fresh::current).code, "no default admin appears by itself");
        assertEquals("already_initialized", assertThrows(AuthorityException.class, store::initialize).code);
    }

    @Test
    void anOrphanKeyFromAnInterruptedInitializationIsReplaced() throws Exception {
        MemoryKeyVault v = new MemoryKeyVault();
        v.write(new byte[32]); // chave órfã (queda entre a chave e o arquivo)
        Path other = dir.resolve("orphan").resolve("authority.bin");
        AuthorityStore fresh = AuthorityStore.open(other, new MemoryAnchor(), v);
        assertEquals(AuthorityStore.Status.UNINITIALIZED, fresh.status());
        fresh.initialize();
        assertEquals(AuthorityStore.Status.TRUSTED, fresh.status());
        assertNotEquals(0, v.read().orElseThrow()[0] | v.read().orElseThrow()[1] | v.read().orElseThrow()[2] | v.read().orElseThrow()[3], "a fresh random key replaced it");
    }

    @Test
    void invalidChangesWriteNothing() throws Exception {
        byte[] before = read();
        assertEquals("invalid_change", assertThrows(AuthorityException.class, () -> admin.createAccount("admin_user", "another-long-password-1".toCharArray(), Role.USER)).code);
        assertEquals("invalid_account", assertThrows(AuthorityException.class, () -> admin.createAccount("bad name!", "another-long-password-1".toCharArray(), Role.USER)).code);
        assertEquals("invalid_account", assertThrows(AuthorityException.class, () -> admin.createAccount("short_pw", "short".toCharArray(), Role.USER)).code);
        assertArrayEquals(before, read());
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
