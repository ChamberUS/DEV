package panel.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O banco legado (panel.db, rollback-only) só é lido: modo do motor SQLite somente leitura/imutável, sem criar nada, sem recriar tabela, sem fallback. */
class LegacyPanelDbTest {
    @TempDir(factory = panel.SecureTempDirFactory.class) Path dir;

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    private Path legacy() throws Exception {
        // esquema legado mínimo, criado só pelo teste (o produto nunca o cria)
        Path f = dir.resolve("panel.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + f); var s = c.createStatement()) {
            s.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT)");
            s.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, event TEXT NOT NULL, actor TEXT, detail TEXT)");
            s.execute("INSERT INTO users(username) VALUES ('boss')");
            s.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('t1','LOGIN_SUCCESS','boss','ok'),('t2','LOGIN_FAILED','boss','bad')");
        }
        return f;
    }

    @Test
    void theEngineItselfRefusesEveryWriteOnTheLegacyUri() throws Exception {
        Path f = legacy();
        String before = sha(f);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + LegacyPanelDb.readOnlyUri(f.toAbsolutePath())); var s = c.createStatement()) {
            for (String sql : List.of("INSERT INTO audit_log(ts,event) VALUES('x','y')", "UPDATE audit_log SET event='z'", "DELETE FROM audit_log",
                    "CREATE TABLE intruder(x)", "DROP TABLE users", "CREATE INDEX i ON audit_log(ts)", "PRAGMA user_version=9")) {
                try {
                    s.execute(sql);
                    fail("write accepted by the legacy connection: " + sql);
                } catch (SQLException expected) {
                    // readonly database
                }
            }
        }
        assertEquals(before, sha(f), "the legacy file is byte-identical");
        try (Stream<Path> l = Files.list(dir)) {
            assertEquals(List.of("panel.db"), l.map(p -> p.getFileName().toString()).toList(), "no journal/wal/shm created");
        }
    }

    @Test
    void readsHistoryThroughTheClosedApiWithoutChangingAnything() throws Exception {
        Path f = legacy();
        String before = sha(f);
        try (var db = LegacyPanelDb.openReadOnly(f).orElseThrow()) {
            assertEquals(2, db.recent(10).size());
            assertEquals("LOGIN_FAILED", db.recent(10).get(0)[1], "most recent first");
            assertEquals(2, db.recentFor("BOSS", 10).size(), "actor match is case-insensitive");
            assertTrue(db.legacyUsernameExists("BOSS"));
            assertFalse(db.legacyUsernameExists("ghost"));
        }
        assertEquals(before, sha(f));
        assertEquals(1, Files.list(dir).count());
    }

    @Test
    void aMissingLegacyTableIsNeverRecreatedAndTheHistoryIsJustUnavailable() throws Exception {
        Path f = legacy();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + f); var s = c.createStatement()) {
            s.execute("DROP TABLE audit_log");
            s.execute("DROP TABLE users");
        }
        String before = sha(f);
        try (var db = LegacyPanelDb.openReadOnly(f).orElseThrow()) {
            assertEquals(List.of(), db.recent(10));
            assertEquals(List.of(), db.recentFor("boss", 10));
            assertFalse(db.legacyUsernameExists("boss"));
        }
        assertEquals(before, sha(f), "no CREATE TABLE IF NOT EXISTS anywhere on the legacy path");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + LegacyPanelDb.readOnlyUri(f.toAbsolutePath()));
                var r = c.createStatement().executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('audit_log','users')")) {
            assertEquals(0, r.getInt(1), "the dropped tables are still absent");
        }
    }

    @Test
    void anAbsentSymlinkedOrCorruptedLegacyFileIsUnavailableAndNeverCreated() throws Exception {
        assertTrue(LegacyPanelDb.openReadOnly(dir.resolve("panel.db")).isEmpty());
        assertFalse(Files.exists(dir.resolve("panel.db")), "an absent legacy database is not created");
        Path junk = dir.resolve("junk.db");
        Files.writeString(junk, "this is not a sqlite database at all, just text padding".repeat(20));
        var db = LegacyPanelDb.openReadOnly(junk);
        assertTrue(db.isEmpty() || db.get().recent(5).isEmpty());
        db.ifPresent(LegacyPanelDb::close);
        assertEquals("this is not a sqlite database at all, just text padding".repeat(20), Files.readString(junk), "corrupted file untouched");
        assertTrue(LegacyPanelDb.openReadOnly(dir).isEmpty(), "a directory is not a database");
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aSymlinkedLegacyFileIsUnavailable() throws Exception {
        Path real = legacy(); Path link = dir.resolve("link.db");
        Files.createSymbolicLink(link, real);
        assertTrue(LegacyPanelDb.openReadOnly(link).isEmpty(), "symlinks are refused");
    }

    @Test
    void theRuntimeDatabaseRefusesTheLegacyFileNameAndNewerSchemas() throws Exception {
        var e = assertThrows(IllegalArgumentException.class, () -> Database.openRuntime(dir.resolve("panel.db")));
        assertEquals("legacy_database_refused", e.getMessage());
        assertFalse(Files.exists(dir.resolve("panel.db")));
        Path newer = dir.resolve("runtime.db");
        try (Database d = Database.openRuntime(newer)) {
            d.with(c -> { c.createStatement().execute("PRAGMA user_version=" + (Database.RUNTIME_SCHEMA_VERSION + 1)); return null; });
        }
        assertEquals("runtime_schema_too_new", assertThrows(IllegalStateException.class, () -> Database.openRuntime(newer)).getMessage());
    }
}
