package panel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.security.Database;

/** Migração SINTÉTICA dos dados não-auth: fonte imutável, lista fechada, FK para users removida, relatório redigido. Nunca usa o panel.db real. */
class RuntimeMigrationTest {
    @TempDir Path dir;

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    private Path source() throws Exception {
        return LegacyPanelFixture.create(dir.resolve("panel.db"));
    }

    private static Set<String> tables(Path db) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath().toUri().getRawPath() + "?mode=ro&immutable=1");
                var r = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            Set<String> out = new TreeSet<>();
            while (r.next()) out.add(r.getString(1));
            return out;
        }
    }

    @Test
    void planIsRedactedAndDecidesEveryTable() throws Exception {
        Path src = source();
        String before = sha(src);
        var plan = RuntimeMigrator.plan(src);
        String json = RuntimeMigrator.toJson(plan);
        for (String canary : List.of(LegacyPanelFixture.CANARY_PASSWORD_HASH, LegacyPanelFixture.CANARY_EMAIL, LegacyPanelFixture.CANARY_ADDRESS, LegacyPanelFixture.CANARY_TX,
                LegacyPanelFixture.CANARY_DEVICE_TOKEN, "CANARY-PEPPER", "CANARY-OLD-typed-secret", "CANARYPUBKEY")) {
            assertFalse(json.contains(canary), "no value in the manifest: " + canary);
        }
        assertEquals(before, plan.get("sourceSha256").asText());
        assertEquals(Database.RUNTIME_SCHEMA_VERSION, plan.get("targetSchemaVersion").asInt());
        var decisions = new ObjectMapper().readTree(json).get("tables");
        java.util.Map<String, String> byTable = new java.util.TreeMap<>();
        decisions.forEach(t -> byTable.put(t.get("table").asText(), t.get("decision").asText()));
        assertEquals("COPY_TRANSFORM", byTable.get("verified_wallets"));
        assertEquals("COPY_TRANSFORM", byTable.get("byx_gas_grants"));
        assertEquals("DROP_AUTH", byTable.get("users"));
        assertEquals("DROP_AUTH", byTable.get("trusted_devices"));
        assertEquals("DROP_AUTH", byTable.get("rate_limits"));
        assertEquals("DROP_AUTH", byTable.get("meta"));
        assertEquals("DROP_HISTORY_STAYS_LEGACY", byTable.get("audit_log"));
        assertEquals(before, sha(src), "planning is read-only");
        assertEquals(1, Files.list(dir).count(), "no auxiliary file");
    }

    @Test
    void prepareCopiesOnlyTheAllowlistRemovesTheUsersForeignKeyAndLeavesTheSourceUntouched() throws Exception {
        Path src = source();
        Path target = dir.resolve("out/runtime.db");
        String before = sha(src);
        var result = RuntimeMigrator.prepare(src, target, RuntimeMigrator.PREPARE_PHRASE);
        assertEquals(before, sha(src), "source SHA unchanged");
        assertTrue(result.get("sourceUnchanged").asBoolean());
        assertEquals(1, result.get("rowsCopied").get("verified_wallets").asInt());
        assertEquals(1, result.get("rowsCopied").get("byx_payment_intents").asInt());
        assertEquals(1, result.get("rowsCopied").get("byx_payment_receipts").asInt());
        assertEquals(1, result.get("rowsCopied").get("byx_gas_grants").asInt());
        Set<String> t = tables(target);
        assertEquals(Set.of("verified_wallets", "byx_payment_intents", "byx_payment_receipts", "byx_gas_grants", "runtime_migration"), t, "only approved tables (+ the migration marker)");
        for (String forbidden : List.of("users", "trusted_devices", "rate_limits", "meta", "audit_log")) {
            assertFalse(t.contains(forbidden), "auth/security/history table never copied: " + forbidden);
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + target.toAbsolutePath().toUri().getRawPath() + "?mode=ro&immutable=1")) {
            for (String table : List.of("verified_wallets", "byx_payment_intents")) {
                try (var r = c.createStatement().executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
                    assertFalse(r.next(), "no physical FK to users in " + table);
                }
            }
            try (var r = c.createStatement().executeQuery("SELECT user_id FROM verified_wallets")) {
                assertTrue(r.next());
                assertEquals(1, r.getLong(1), "the stable legacy user id is preserved without a users table");
            }
            try (var r = c.createStatement().executeQuery("PRAGMA user_version")) {
                assertEquals(Database.RUNTIME_SCHEMA_VERSION, r.getInt(1));
            }
        }
        String all = new String(Files.readAllBytes(target), java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String secret : List.of(LegacyPanelFixture.CANARY_PASSWORD_HASH, LegacyPanelFixture.CANARY_DEVICE_TOKEN, "CANARY-PEPPER", "CANARY-OLD-typed-secret")) {
            assertFalse(all.contains(secret), "no auth/security value reached the runtime database: " + secret);
        }
        var verified = RuntimeMigrator.verify(src, target);
        assertEquals("PASS", verified.get("verification").asText());
        assertEquals(before, sha(src));
    }

    @Test
    void theMigratedRuntimeDatabaseWorksWithTheProductRepositories() throws Exception {
        Path src = source();
        Path target = dir.resolve("runtime.db");
        RuntimeMigrator.prepare(src, target, RuntimeMigrator.PREPARE_PHRASE);
        try (Database db = Database.openRuntime(target)) {
            var wallets = new panel.repository.ByxWalletRepository(db).list(1);
            assertEquals(1, wallets.size());
            assertEquals(LegacyPanelFixture.CANARY_ADDRESS, wallets.get(0).address());
            assertEquals(1, new panel.repository.ByxPaymentRepository(db).receipts(1).size());
            assertEquals(1, new panel.repository.GasGrantRepository(db).all().size());
        }
    }

    @Test
    void prepareRefusesWithoutThePhraseWhenTheTargetExistsOrTheSourceHasSidecars() throws Exception {
        Path src = source();
        assertEquals("confirmation_required", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.prepare(src, dir.resolve("a.db"), "yes")).code);
        assertFalse(Files.exists(dir.resolve("a.db")));
        Files.writeString(dir.resolve("exists.db"), "x");
        assertEquals("target_exists", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.prepare(src, dir.resolve("exists.db"), RuntimeMigrator.PREPARE_PHRASE)).code);
        assertEquals("x", Files.readString(dir.resolve("exists.db")), "an existing target is never overwritten");
        assertEquals("source_equals_target", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.prepare(src, src, RuntimeMigrator.PREPARE_PHRASE)).code);
        Files.writeString(dir.resolve("panel.db-wal"), "wal");
        assertEquals("source_has_journal_or_wal", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.prepare(src, dir.resolve("b.db"), RuntimeMigrator.PREPARE_PHRASE)).code);
        assertFalse(Files.exists(dir.resolve("b.db")));
        assertEquals("legacy_database_refused", assertThrows(IllegalArgumentException.class, () -> Database.openRuntime(src)).getMessage(), "the legacy file can never be a runtime target");
    }

    @Test
    void verifyDetectsTamperingUnapprovedTablesAndForeignKeysToUsers() throws Exception {
        Path src = source();
        Path target = dir.resolve("runtime.db");
        RuntimeMigrator.prepare(src, target, RuntimeMigrator.PREPARE_PHRASE);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + target); var s = c.createStatement()) {
            s.execute("UPDATE verified_wallets SET public_key='TAMPERED'");
        }
        assertEquals("content_mismatch_verified_wallets", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.verify(src, target)).code);
        Path target2 = dir.resolve("runtime2.db");
        RuntimeMigrator.prepare(src, target2, RuntimeMigrator.PREPARE_PHRASE);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + target2); var s = c.createStatement()) {
            s.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
        }
        assertEquals("target_has_unapproved_table", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.verify(src, target2)).code);
        Path target3 = dir.resolve("runtime3.db");
        RuntimeMigrator.prepare(src, target3, RuntimeMigrator.PREPARE_PHRASE);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + src); var s = c.createStatement()) { // a fonte mudou depois do prepare
            s.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('x','y','z','w')");
        }
        assertEquals("source_changed_since_prepare", assertThrows(RuntimeMigrator.MigrationException.class, () -> RuntimeMigrator.verify(src, target3)).code);
    }

    @Test
    void theAuthSecurityTablesAreNeverInTheAllowlist() {
        for (String table : List.of("users", "trusted_devices", "rate_limits", "meta", "audit_log")) {
            assertFalse(RuntimeMigrator.ALLOWLIST.contains(table), table);
        }
        assertEquals(List.of("verified_wallets", "byx_payment_intents", "byx_payment_receipts", "byx_gas_grants"), RuntimeMigrator.ALLOWLIST);
    }

    @Test
    void anEmptyRealisticSourceMigratesToAnEmptyRuntimeDatabase() throws Exception {
        Path src = source();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + src); var s = c.createStatement()) { // como o panel.db real: tabelas BYX vazias
            s.execute("DELETE FROM byx_payment_receipts");
            s.execute("DELETE FROM byx_payment_intents");
            s.execute("DELETE FROM byx_gas_grants");
            s.execute("DELETE FROM verified_wallets");
        }
        Path target = dir.resolve("runtime.db");
        var r = RuntimeMigrator.prepare(src, target, RuntimeMigrator.PREPARE_PHRASE);
        for (String t : RuntimeMigrator.ALLOWLIST) assertEquals(0, r.get("rowsCopied").get(t).asInt());
        assertEquals("PASS", RuntimeMigrator.verify(src, target).get("verification").asText());
    }

    @Test
    void aSourceWithoutSomeAllowlistedTableMigratesAndVerifies() throws Exception {
        Path src = source();
        for (String t : List.of("byx_gas_grants", "byx_payment_receipts", "byx_payment_intents")) LegacyPanelFixture.drop(src, t);
        Path target = dir.resolve("runtime.db");
        var r = RuntimeMigrator.prepare(src, target, RuntimeMigrator.PREPARE_PHRASE);
        assertEquals(0, r.get("rowsCopied").get("byx_gas_grants").asInt());
        assertEquals(1, r.get("rowsCopied").get("verified_wallets").asInt());
        assertEquals("PASS", RuntimeMigrator.verify(src, target).get("verification").asText(), "an absent source table is an empty one");
    }
}
