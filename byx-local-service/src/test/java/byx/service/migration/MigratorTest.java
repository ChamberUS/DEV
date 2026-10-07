package byx.service.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.auth.Account;
import byx.service.auth.AuthAudit;
import byx.service.auth.AuthPolicy;
import byx.service.auth.AuthProfile;
import byx.service.auth.AuthRateLimiter;
import byx.service.auth.AuthService;
import byx.service.auth.AuthorityAdmin;
import byx.service.auth.AuthorityStore;
import byx.service.auth.NotConfiguredSecondFactor;
import byx.service.auth.PasswordVerifier;
import byx.service.auth.Role;
import byx.service.auth.SecretStoreAnchor;
import byx.service.auth.SecretStoreKeyVault;
import byx.service.migration.LegacySecretSource.Access;
import byx.service.migration.LegacySecretSource.Item;
import byx.service.secrets.ScopedSecretStore;
import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStatus;
import byx.service.secrets.SecretStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Migração com dados SINTÉTICOS: banco legado somente-leitura, importação fiel, verificação sem expor valores, segredos lidos uma vez, paradas e retomada. */
class MigratorTest {
    private static final PasswordVerifier PV = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));
    private Path dir;
    private Path db;
    private Path providers;
    private Path home;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final MapStore backend = new MapStore();
    private FakeLegacy legacy;
    private Migrator m;
    private boolean profileValid = true;
    private AuthProfile profile;
    private String adminPw = "synthetic-admin-pass-1";
    private String userPw = "synthetic-user-pass-22";
    private String adminHash;
    private String userHash;

    static final class MapStore implements SecretStore {
        final Map<SecretId, byte[]> items = new HashMap<>();

        public SecretStatus status() {
            return SecretStatus.SECURE_STORAGE_AVAILABLE;
        }

        public void write(SecretId id, SecretBytes v) {
            items.put(id, v.copyBytes());
        }

        public boolean update(SecretId id, SecretBytes v) {
            return items.replace(id, v.copyBytes()) != null;
        }

        public Optional<SecretBytes> read(SecretId id) {
            return Optional.ofNullable(items.get(id)).map(SecretBytes::copyOf);
        }

        public boolean delete(SecretId id) {
            return items.remove(id) != null;
        }
    }

    static final class FakeLegacy implements LegacySecretSource {
        final Map<Item, byte[]> values = new EnumMap<>(Item.class);
        final Map<Item, Access> forced = new EnumMap<>(Item.class);
        final Map<Item, Integer> reads = new EnumMap<>(Item.class);

        public Access describe(Item i) {
            return forced.containsKey(i) && forced.get(i) == Access.ABSENT ? Access.ABSENT : values.containsKey(i) ? Access.PRESENT : Access.ABSENT;
        }

        public Result readOnce(Item i) {
            reads.merge(i, 1, Integer::sum);
            if (forced.containsKey(i)) {
                return Result.none(forced.get(i));
            }
            return values.containsKey(i) ? new Result(Access.PRESENT, values.get(i).clone()) : Result.none(Access.ABSENT);
        }

        public String serviceOf(Item i) {
            return "legacy-test/" + i.name().toLowerCase();
        }

        public String account() {
            return "legacy-test";
        }
    }

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        dir = Files.createTempDirectory(Path.of("/tmp"), "mg");
        db = dir.resolve("panel.db");
        providers = dir.resolve("providers.properties");
        home = dir.resolve("svc-home");
        adminHash = LegacyPanelHash.hash(adminPw.toCharArray(), 1024, 1, 1); // hashes no formato EXATO do painel legado
        userHash = LegacyPanelHash.hash(userPw.toCharArray(), 1024, 1, 1);
        createLegacy(true);
        Files.writeString(providers, "resend.fromAddress=BYX <noreply@example.test>\ntwilio.accountSid=AC" + "1".repeat(32) + "\ntwilio.apiKeySid=SK" + "2".repeat(32) + "\ntwilio.verifyServiceSid=VA" + "3".repeat(32) + "\n");
        Files.writeString(dir.resolve("security.properties"), "security.admin.sessionTimeoutMinutes=30\nsecurity.dev.mode=ignored\n");
        legacy = new FakeLegacy();
        legacy.values.put(Item.RESEND, "re_FAKE_LEGACY_KEY".getBytes(StandardCharsets.UTF_8));
        legacy.values.put(Item.TWILIO, "FAKE-LEGACY-TWILIO".getBytes(StandardCharsets.UTF_8));
        legacy.values.put(Item.TRUSTED_DEVICE, "FAKE-LEGACY-DEVICE".getBytes(StandardCharsets.UTF_8));
        profile = AuthProfile.production(home); // perfil de produção sobre um HOME TEMPORÁRIO e um cofre de MENTIRA (nada real é tocado)
        m = new Migrator(profile, db, providers, dir.resolve("security.properties"), legacy, new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION), Clock.systemUTC(), "identifier \"x\" and anchor apple generic and certificate leaf[subject.OU] = \"AAAAAAAAAA\"", () -> profileValid);
    }

    @AfterEach
    void down() throws Exception {
        Log.redirect(null);
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void createLegacy(boolean nocase) throws Exception {
        Files.deleteIfExists(db);
        String collate = nocase ? " COLLATE NOCASE" : "";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE" + collate + ", email TEXT NOT NULL UNIQUE" + collate
                    + ", password_hash TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, phone TEXT, email_verified INTEGER NOT NULL DEFAULT 0, phone_verified INTEGER NOT NULL DEFAULT 0, "
                    + "must_change_password INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_login_at TEXT)");
            st.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, event TEXT NOT NULL, actor TEXT, detail TEXT)");
            st.execute("CREATE TABLE trusted_devices (device_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, display_name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, "
                    + "last_used_at TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT)");
            st.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            insert(c, "Syn_Admin", "Syn.Admin@Example.test", adminHash, "ADMIN", "ACTIVE", "+5511911110001", 1, 1, 0);
            insert(c, "syn_user", "syn.user@example.test", userHash, "USER", "ACTIVE", "+5511911110002", 1, 0, 0);
            insert(c, "syn_off", "syn.off@example.test", userHash, "USER", "DISABLED", null, 0, 0, 1);
            st.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES('2026-10-02T04:00:00Z','LOGIN_SUCCESS','Syn_Admin','role=ADMIN'),('2026-10-02T04:01:00Z','LOGOUT','Syn_Admin','')");
            st.execute("INSERT INTO trusted_devices VALUES('d1',1,'This Mac','" + "e".repeat(64) + "','2026-10-03T00:00:00Z','2026-10-03T00:00:00Z','2099-01-01T00:00:00Z',NULL)");
            st.execute("INSERT INTO meta VALUES('rl_pepper','synthetic-pepper')");
        }
    }

    private static void insert(Connection c, String u, String e, String h, String role, String status, String phone, int ev, int pv, int must) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO users(username,email,password_hash,role,status,phone,email_verified,phone_verified,must_change_password,created_at,updated_at,last_login_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?, '2026-10-02T04:00:12.751772Z','2026-10-02T04:00:12.751772Z','2026-10-05T12:00:00Z')")) {
            p.setString(1, u);
            p.setString(2, e);
            p.setString(3, h);
            p.setString(4, role);
            p.setString(5, status);
            p.setString(6, phone);
            p.setInt(7, ev);
            p.setInt(8, pv);
            p.setInt(9, must);
            p.executeUpdate();
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String all(Object o) {
        return String.valueOf(o);
    }

    // ---- auditoria somente-leitura ------------------------------------------------------------------------------------------------------

    @Test
    void auditIsReadOnlyAndNeverPrintsSensitiveValues() throws Exception {
        String before = LegacyDb.sha256(db);
        FileTime mtime = Files.getLastModifiedTime(db);
        long entries = Files.list(dir).count();
        Map<String, Object> r = m.audit();
        String text = all(r);
        assertEquals(3, r.get("users.total"));
        assertEquals(Map.of("ADMIN", 1L, "USER", 2L), r.get("users.byRole"));
        assertEquals(2L, r.get("users.enabled"));
        assertEquals(1L, r.get("users.disabled"));
        assertEquals(1L, r.get("users.usernamesLowercased"), "normalization is visible, not silent");
        assertEquals(Map.of("argon2id v=19 m=1024,t=1,p=1", 3L), r.get("users.hashAlgorithms"));
        assertEquals(Map.of("active", 1L), r.get("trustedDevices"));
        assertEquals(2L, r.get("auditRows"));
        assertEquals(List.of("rl_pepper"), r.get("metaNames"), "only the NAME of meta rows");
        for (String secret : new String[] {adminHash, userHash, "Syn.Admin", "syn.user", "+5511911110001", "synthetic-pepper", "e".repeat(64), "re_FAKE", "FAKE-LEGACY"}) {
            assertFalse(text.contains(secret), "audit report leaks a sensitive value: " + secret.substring(0, 4));
        }
        assertEquals(before, LegacyDb.sha256(db), "source untouched");
        assertEquals(mtime, Files.getLastModifiedTime(db), "mtime untouched");
        assertEquals(entries, Files.list(dir).count(), "no journal/aux file was created next to the source");
        assertFalse(Files.exists(home), "audit writes nothing at all");
        assertEquals(0, legacy.reads.size(), "audit never READS a legacy secret (attributes only)");
    }

    // ---- manifesto ----------------------------------------------------------------------------------------------------------------------------

    @Test
    void theManifestProvesTheSourceStateAndHoldsNoSecret() throws Exception {
        m.plan();
        String mf = Files.readString(profile.dir().resolve("migration-manifest.json"));
        for (String field : new String[] {"manifestVersion", "createdAt", "schemaFingerprint", "dbSha256", "usersDigest", "\"total\" : 3", "byRole", "\"enabled\" : 2", "\"disabled\" : 1", "hashAlgorithms",
            "legacy-test/resend", "authorityFormatVersion", "com.buynnex.byx.service", "com.buynnex.byx", "serviceCodeRequirement", "PLANNED", "NOT_MIGRATED_INVALIDATED_AT_CUTOVER_LEGACY_PRESERVED"}) {
            assertTrue(mf.contains(field), "manifest lacks " + field);
        }
        for (String secret : new String[] {adminHash, userHash, "Syn.Admin", "+5511911110001", "re_FAKE", "FAKE-LEGACY", "syn_user", userPw, adminPw, "e".repeat(64)}) {
            assertFalse(mf.contains(secret), "manifest holds a sensitive value: " + secret.substring(0, 4));
        }
        assertTrue(mf.contains(LegacyDb.sha256(db)), "the manifest identifies the exact source DB");
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(profile.dir().resolve("migration-manifest.json"))));
        assertEquals(0, legacy.reads.size());
    }

    // ---- preparar: importação fiel, banco intacto, login com a senha ANTIGA ---------------------------------------------------------------

    @Test
    void prepareImportsFaithfullyWithoutTouchingTheSourceAndLegacyPasswordsStillWork() throws Exception {
        String before = LegacyDb.sha256(db);
        FileTime mtime = Files.getLastModifiedTime(db);
        long entries = Files.list(dir).count();
        m.plan();
        Map<String, Object> r = m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("PREPARED", r.get("status"));
        assertEquals(before, LegacyDb.sha256(db), "panel.db is a READ-ONLY source: byte-identical");
        assertEquals(mtime, Files.getLastModifiedTime(db));
        assertEquals(entries + 1, Files.list(dir).count(), "only svc-home was added next to the source");
        AuthorityStore store = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION), SecretId.AUTHORITY_ROLLBACK_ANCHOR),
                new SecretStoreKeyVault(new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION), SecretId.AUTHORITY_ENCRYPTION_KEY));
        var st = store.current();
        assertEquals(3, st.accounts().size());
        Account admin = st.byUsername("syn_admin").orElseThrow();
        assertEquals(Role.ADMIN, admin.role());
        assertEquals(1, admin.legacyUserId(), "stable identity preserved");
        assertEquals(adminHash, admin.passwordHash(), "the existing Argon2 verifier is preserved as is");
        assertEquals("Syn.Admin@Example.test", admin.email());
        assertEquals(1, admin.credentialVersion());
        assertFalse(st.byUsername("syn_off").orElseThrow().enabled(), "a disabled account is never reactivated");
        assertTrue(st.byUsername("syn_off").orElseThrow().mustChangePassword());
        assertEquals(1, st.accounts().stream().filter(a -> a.role() == Role.ADMIN).count(), "nobody was promoted; no default admin appeared");
        assertTrue(st.migrationFreeze(), "the safety window is on from the start");
        assertTrue(st.devices().isEmpty(), "no trusted device was migrated");
        assertEquals("BYX <noreply@example.test>", st.providers().resendFromAddress());
        assertEquals(5, st.providers().adminElevationMinutes(), "AuthLimits overrides the legacy 30-minute configuration");
        // o snapshot em disco é cifrado
        String raw = new String(Files.readAllBytes(profile.snapshot()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("syn_admin") || raw.contains("argon2id") || raw.contains("example.test"));
        // login com a senha ANTIGA (nunca pediu a senha em texto para migrar), por usuário e por e-mail, sem diferença de caixa
        var rl = new AuthRateLimiter(null, store.derivedKey("a"), store.derivedKey("b"), Clock.systemUTC());
        AuthService svc = new AuthService(store, new AuthorityAdmin(store, PV, Clock.systemUTC()), PV, rl, new NotConfiguredSecondFactor(), AuthPolicy.standard(), new AuthAudit(Clock.systemUTC()), Clock.systemUTC());
        assertEquals(AuthService.Code.OK, svc.login(7, "SYN_ADMIN", adminPw.toCharArray()).code());
        assertEquals(AuthService.Code.OK, svc.login(8, "syn.user@example.test", userPw.toCharArray()).code());
        assertEquals(AuthService.Code.INVALID_CREDENTIALS, svc.login(9, "syn_off", userPw.toCharArray()).code(), "disabled stays disabled even with the right password");
        // durante a janela de segurança nenhuma mutação que complique o rollback
        String tok = (String) svc.login(10, "syn_admin", adminPw.toCharArray()).data().get("session");
        assertEquals(AuthService.Code.FROZEN, svc.changePassword(10, tok, adminPw.toCharArray(), "another-synthetic-pass-3".toCharArray()).code());
    }

    // ---- verificação fonte × alvo --------------------------------------------------------------------------------------------------------

    @Test
    void verificationComparesWithoutExposingValuesAndStopsOnAnyDifference() throws Exception {
        m.plan();
        m.prepare(Migrator.PREPARE_PHRASE);
        Map<String, Object> v = m.verify();
        assertEquals("PASS", v.get("verification"));
        assertEquals(Map.of("RESEND_API_KEY", "PRESENT", "TWILIO_API_SECRET", "PRESENT"), v.get("vaultSecrets"));
        // deriva o alvo (alguém muda o papel de um usuário na autoridade nova): a verificação PARA e diz QUAL campo/identidade, sem valores
        ScopedSecretStore ss = new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION);
        AuthorityStore store = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(ss, SecretId.AUTHORITY_ROLLBACK_ANCHOR), new SecretStoreKeyVault(ss, SecretId.AUTHORITY_ENCRYPTION_KEY));
        AuthorityAdmin admin = new AuthorityAdmin(store, PV, Clock.systemUTC());
        admin.setFreeze(false);
        admin.setRole(store.current().byUsername("syn_user").orElseThrow().id(), Role.ADMIN);
        var e = assertThrows(MigrationException.class, () -> m.verify());
        assertEquals("verification_failed", e.code);
        assertTrue(e.detail.contains("role legacyId=2"), e.detail);
        assertFalse(e.detail.contains("argon2") || e.detail.contains("syn_user"), "no value in the stop report");
        assertTrue(e.detail.contains("flags") || e.detail.contains("credentialVersion legacyId=2"), "a security change also bumps the credential version, which is reported too");
    }

    // ---- segredos: lidos UMA vez, dispositivo confiável não migrado ---------------------------------------------------------------------------

    @Test
    void secretsAreReadOnceFromTheLegacyCopiedToTheVaultAndTheLegacyIsPreserved() throws Exception {
        m.plan();
        Map<String, Object> r = m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals(Map.of("RESEND", "PREPARED", "TWILIO", "PREPARED"), r.get("secrets"));
        assertEquals("NOT_MIGRATED_LEGACY_PRESERVED", r.get("trustedDevice"));
        assertEquals(1, legacy.reads.get(Item.RESEND), "READ LEGACY ONCE");
        assertEquals(1, legacy.reads.get(Item.TWILIO));
        assertFalse(legacy.reads.containsKey(Item.TRUSTED_DEVICE), "the trusted-device token is never read");
        assertArrayEquals("re_FAKE_LEGACY_KEY".getBytes(StandardCharsets.UTF_8), backend.items.get(SecretId.RESEND_API_KEY));
        assertArrayEquals("FAKE-LEGACY-TWILIO".getBytes(StandardCharsets.UTF_8), backend.items.get(SecretId.TWILIO_API_SECRET));
        assertEquals(3, legacy.values.size(), "nothing legacy was deleted");
        assertFalse(backend.items.containsKey(SecretId.AUTHORITY_TEST_ENCRYPTION_KEY) || backend.items.containsKey(SecretId.AUTHORITY_TEST_ANCHOR), "no test id was used for the real authority");
        String text = String.join("\n", logs) + all(r);
        assertFalse(text.contains("re_FAKE") || text.contains("FAKE-LEGACY"), "no secret value in logs or reports");
        assertEquals("PREPARED", m.prepare(Migrator.PREPARE_PHRASE).get("status"), "re-running is idempotent");
        assertEquals(1, legacy.reads.get(Item.RESEND), "and it did not read the legacy again");
    }

    @Test
    void anItemTheSignedServiceCannotReadBlocksOnlyThatItemAndNeverFallsBack() throws Exception {
        legacy.forced.put(Item.TWILIO, Access.DENIED);
        m.plan();
        Map<String, Object> r = m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("VERIFIED", r.get("status"), "users are imported and verified, but the cutover is not prepared");
        assertEquals(Map.of("RESEND", "PREPARED", "TWILIO", "BLOCKED_ACCESS"), r.get("secrets"));
        assertFalse(backend.items.containsKey(SecretId.TWILIO_API_SECRET), "no workaround copy of the blocked item");
        legacy.forced.clear(); // o usuário autoriza o acesso (manual): retoma sem refazer o que já foi feito
        Map<String, Object> r2 = m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("PREPARED", r2.get("status"));
        assertEquals(1, legacy.reads.get(Item.RESEND), "the item already prepared was not read again");
        assertEquals(2, legacy.reads.get(Item.TWILIO));
    }

    @Test
    void aDifferentExistingProductionSecretIsNeverOverwritten() throws Exception {
        backend.items.put(SecretId.RESEND_API_KEY, "already-there".getBytes(StandardCharsets.UTF_8));
        m.plan();
        Map<String, Object> r = m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("TARGET_EXISTS_DIFFERENT", ((Map<?, ?>) r.get("secrets")).get("RESEND"));
        assertEquals("VERIFIED", r.get("status"));
        assertArrayEquals("already-there".getBytes(StandardCharsets.UTF_8), backend.items.get(SecretId.RESEND_API_KEY));
    }

    // ---- paradas -----------------------------------------------------------------------------------------------------------------------------

    @Test
    void prepareNeedsTheExactConfirmationAndAManifest() throws Exception {
        assertEquals("no_manifest", assertThrows(MigrationException.class, () -> m.prepare(Migrator.PREPARE_PHRASE)).code);
        m.plan();
        assertEquals("confirmation_required", assertThrows(MigrationException.class, () -> m.prepare("yes")).code);
        assertEquals("confirmation_required", assertThrows(MigrationException.class, () -> m.prepare(null)).code);
        assertFalse(Files.exists(profile.snapshot()), "nothing was written without the confirmation");
        assertTrue(backend.items.isEmpty());
    }

    @Test
    void aSourceThatChangedAfterThePlanStopsEverythingAndWritesNothing() throws Exception {
        m.plan();
        exec("UPDATE users SET role='ADMIN' WHERE username='syn_user'");
        assertEquals("source_users_changed", assertThrows(MigrationException.class, () -> m.prepare(Migrator.PREPARE_PHRASE)).code);
        assertFalse(Files.exists(profile.snapshot()));
        assertTrue(backend.items.isEmpty());
        assertEquals(0, legacy.reads.size());
        // só mexer em auditoria / último login (o app em uso) NÃO invalida o plano
        createLegacy(true);
        m.plan();
        exec("INSERT INTO audit_log(ts,event,actor,detail) VALUES('2026-10-06T00:00:00Z','LOGIN_SUCCESS','syn_admin','')");
        exec("UPDATE users SET last_login_at='2026-10-06T00:00:00Z', updated_at='2026-10-06T00:00:00Z' WHERE id=1");
        assertEquals("PREPARED", m.prepare(Migrator.PREPARE_PHRASE).get("status"));
    }

    @Test
    void importStopsOnProblemsInsteadOfFixingThem() throws Exception {
        String[][] bad = {
            {"UPDATE users SET username='bad name' WHERE id=2", "field=username"},
            {"UPDATE users SET role='SUPERUSER' WHERE id=2", "field=role"},
            {"UPDATE users SET status='SUSPENDED' WHERE id=2", "field=status"},
            {"UPDATE users SET phone='12345' WHERE id=2", "field=phone"},
            {"UPDATE users SET email='not-an-email' WHERE id=2", "field=email"},
            {"UPDATE users SET password_hash='$2b$12$bcryptlookingvaluebcryptlookingvaluebcryptlookingvalue' WHERE id=2", "field=password_hash"},
            {"UPDATE users SET password_hash='$argon2id$v=19$m=999999999,t=2,p=1$c2FsdHNhbHRzYWx0$" + "A".repeat(43) + "' WHERE id=2", "field=password_hash"},
            {"UPDATE users SET created_at='yesterday' WHERE id=2", "field=timestamps"},
        };
        for (String[] b : bad) {
            createLegacy(true);
            exec(b[0]);
            var e = assertThrows(MigrationException.class, () -> m.plan(), b[0]);
            assertTrue(e.detail.contains(b[1]) && e.detail.contains("legacyId=2"), e.code + " " + e.detail);
            assertFalse(e.detail.contains("bcrypt") || e.detail.contains("argon2"), "no value in the report");
            assertFalse(Files.exists(profile.dir().resolve("migration-manifest.json")), "no manifest for a non-importable source");
        }
    }

    @Test
    void aNormalizationCollisionStopsTheMigration() throws Exception {
        createLegacy(false); // esquema sem NOCASE só neste teste: "Dup" e "dup" coexistiriam no legado
        exec("INSERT INTO users(username,email,password_hash,role,status,phone,created_at,updated_at) VALUES('SYN_USER','other@example.test','" + userHash + "','USER','ACTIVE',NULL,'2026-10-02T04:00:12Z','2026-10-02T04:00:12Z')");
        var e = assertThrows(MigrationException.class, () -> m.plan());
        assertEquals("normalization_collision", e.code);
        assertTrue(e.detail.contains("field=username"));
    }

    @Test
    void anExistingAuthorityIsNeverOverwrittenByAMigration() throws Exception {
        m.plan();
        m.prepare(Migrator.PREPARE_PHRASE);
        // outro plano + outro prepare sobre uma autoridade existente: recusado
        var e = assertThrows(MigrationException.class, () -> m.plan());
        assertEquals("already_imported", e.code);
    }

    // ---- cutover: finalizar ---------------------------------------------------------------------------------------------------------------

    @Test
    void finalizeLiftsTheFreezeOnlyAfterPreparedAndMarksTheLegacyRollbackOnly() throws Exception {
        assertEquals("not_prepared", assertThrows(MigrationException.class, () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        m.plan();
        m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("confirmation_required", assertThrows(MigrationException.class, () -> m.finalizeCutover("ok")).code);
        Map<String, Object> r = m.finalizeCutover(Migrator.FINALIZE_PHRASE);
        assertEquals("CUTOVER_VERIFIED", r.get("status"));
        assertEquals("ROLLBACK_ONLY", r.get("legacy"));
        assertEquals(false, r.get("freeze"));
        ScopedSecretStore ss = new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION);
        AuthorityStore store = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(ss, SecretId.AUTHORITY_ROLLBACK_ANCHOR), new SecretStoreKeyVault(ss, SecretId.AUTHORITY_ENCRYPTION_KEY));
        assertFalse(store.current().migrationFreeze());
        assertEquals(3, legacy.values.size(), "legacy items are still there: deletion is a separate, later step");
        assertTrue(Files.exists(db), "panel.db is still there");
        assertEquals("already_cutover", assertThrows(MigrationException.class, () -> m.prepare(Migrator.PREPARE_PHRASE)).code);
    }

    @Test void finalizeExpiredProfileKeepsSafetyWindowAndPreparedState() throws Exception {
        m.plan(); m.prepare(Migrator.PREPARE_PHRASE); profileValid = false;
        assertEquals("provisioning_profile_expired_or_unavailable", assertThrows(MigrationException.class,
                () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        assertEquals("PREPARED", m.status().get("status")); assertEquals(true, m.status().get("freeze"));
    }

    @Test void finalizeRepeatsVerifyAndNeverRepairsChangedSource() throws Exception {
        m.plan(); m.prepare(Migrator.PREPARE_PHRASE);
        exec("UPDATE users SET email='different@example.test' WHERE id=2");
        assertEquals("source_users_changed", assertThrows(MigrationException.class,
                () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        assertEquals(true, m.status().get("freeze"));
    }

    @Test void finalizeMissingProviderSecretDeniesWithoutLiftingFreeze() throws Exception {
        m.plan(); m.prepare(Migrator.PREPARE_PHRASE); backend.items.remove(profile.twilioId());
        assertEquals("second_factor_unavailable", assertThrows(MigrationException.class,
                () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        assertEquals(true, m.status().get("freeze"));
    }

    @Test void finalizeInactiveSafetyWindowIsDenied() throws Exception {
        m.plan(); m.prepare(Migrator.PREPARE_PHRASE);
        var secrets = new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION);
        var store = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(secrets, profile.anchorId()),
                new SecretStoreKeyVault(secrets, profile.encryptionKeyId()));
        new AuthorityAdmin(store, PV, Clock.systemUTC()).setFreeze(false);
        assertEquals("safety_window_inactive", assertThrows(MigrationException.class,
                () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        assertEquals("PREPARED", m.status().get("status"));
    }

    @Test void finalizeNeedsAnEnabledAdminEvenWhenSourceAndTargetMatch() throws Exception {
        exec("UPDATE users SET status='DISABLED' WHERE role='ADMIN'");
        m.plan(); m.prepare(Migrator.PREPARE_PHRASE);
        assertEquals("enabled_admin_required", assertThrows(MigrationException.class,
                () -> m.finalizeCutover(Migrator.FINALIZE_PHRASE)).code);
        assertEquals(true, m.status().get("freeze"));
    }

    @Test
    void theStateFileHoldsNoSecret() throws Exception {
        m.plan();
        m.prepare(Migrator.PREPARE_PHRASE);
        String state = Files.readString(profile.migrationState());
        for (String secret : new String[] {adminHash, userHash, "re_FAKE", "FAKE-LEGACY", "example.test", "syn_"}) {
            assertFalse(state.contains(secret), "state file leaks: " + secret.substring(0, 4));
        }
        assertNotEquals("", state);
    }
}
