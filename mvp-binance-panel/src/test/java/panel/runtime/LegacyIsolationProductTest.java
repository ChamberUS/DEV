package panel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.FakeAuthority;
import panel.app.AppContext;
import panel.security.Role;

/**
 * O PRODUTO (composição real, AppContext) nunca escreve no panel.db legado: nada de DDL/INSERT/UPDATE/DELETE, nenhuma tabela recriada, nenhum arquivo auxiliar. O banco de
 * runtime é independente e o login não precisa de nenhuma tabela de auth do legado. Home TEMPORÁRIO e dados sintéticos; o panel.db real nunca é aberto.
 */
class LegacyIsolationProductTest {
    @TempDir(factory = panel.SecureTempDirFactory.class) Path home;
    private String oldHome;
    private Path appHome;
    private AppContext ctx;

    @BeforeEach
    void sandbox() throws Exception {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        appHome = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        panel.FxSupportAccess.start();
    }

    @AfterEach
    void restore() {
        if (ctx != null) {
            try { ctx.market.stop(); ctx.localService.stop(); ctx.research.close(); ctx.captureMonitor.close(); ctx.byx.close(); ctx.byxBenefits.close(); ctx.closeRuntimeStorage(); } catch (RuntimeException ignored) { }
        }
        System.setProperty("user.home", oldHome);
    }

    private AppContext compose(FakeAuthority authority) {
        ctx = AppContext.create(null, new AppContext.Providers(authority, "FAKE AUTHORITY (test only)"));
        return ctx;
    }

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    private static Set<String> tables(Path db) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath().toUri().getRawPath() + "?mode=ro&immutable=1");
                var r = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            Set<String> out = new TreeSet<>();
            while (r.next()) out.add(r.getString(1));
            return out;
        }
    }

    private static List<String> files(Path dir) throws Exception {
        try (Stream<Path> l = Files.list(dir)) {
            return l.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void productStartupCannotWriteOrRecreateAnythingInTheLegacyPanelDb() throws Exception {
        Path legacy = LegacyPanelFixture.create(appHome.resolve("panel.db"));
        // tabelas legadas AUSENTES: o produto não pode recriá-las (era o CREATE TABLE IF NOT EXISTS do Database.init antigo e dos repositórios)
        for (String missing : List.of("verified_wallets", "byx_payment_receipts", "byx_payment_intents", "byx_gas_grants", "rate_limits", "meta", "trusted_devices")) {
            LegacyPanelFixture.drop(legacy, missing);
        }
        String shaBefore = sha(legacy);
        String schemaBefore = LegacyPanelFixture.schemaOf(legacy);
        Set<String> tablesBefore = tables(legacy);
        long modified = Files.getLastModifiedTime(legacy).toMillis();

        compose(new FakeAuthority(Clock.systemUTC()));

        assertEquals(shaBefore, sha(legacy), "panel.db is byte-identical after the product composed");
        assertEquals(schemaBefore, LegacyPanelFixture.schemaOf(legacy));
        assertEquals(tablesBefore, tables(legacy), "no table was (re)created in the legacy database");
        assertEquals(modified, Files.getLastModifiedTime(legacy).toMillis());
        assertEquals(List.of("panel.db", "runtime.db"), files(appHome), "no -journal/-wal/-shm next to the legacy file; the runtime database sits beside it");
        assertEquals(Set.of("verified_wallets", "byx_payment_intents", "byx_payment_receipts", "byx_gas_grants"), tables(appHome.resolve("runtime.db")),
                "the runtime database is independent: BYX domain tables only, no auth tables, no users");
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + appHome.resolve("runtime.db").toUri().getRawPath() + "?mode=ro&immutable=1");
                var r = c.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(panel.security.Database.RUNTIME_SCHEMA_VERSION, r.getInt(1));
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            assertEquals(List.of(), panel.security.PrivateFiles.audit(appHome, appHome.resolve("runtime.db")));
        } else {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(appHome.resolve("runtime.db"))));
        }
    }

    @Test
    void legacyAuditHistoryIsShownReadOnlyAndMaskedWhileTheFileStaysIdentical() throws Exception {
        Path legacy = LegacyPanelFixture.create(appHome.resolve("panel.db"));
        String before = sha(legacy);
        compose(new FakeAuthority(Clock.systemUTC()));
        var shown = ctx.audit.recent(10);
        assertEquals(3, shown.size());
        assertTrue(shown.stream().noneMatch(e -> e.actor() != null && e.actor().contains("CANARY")), "legacy typed identifiers are masked on read");
        assertTrue(shown.stream().anyMatch(e -> "legacy-attempt".equals(e.actor())));
        assertEquals(2, ctx.audit.recentFor("boss", 10).size());
        var denied = assertThrows(panel.security.AccessDeniedException.class, () -> ctx.audit.record(panel.security.AuditEvent.values()[0], "a", "d"));
        assertTrue(denied.getMessage().startsWith(panel.security.ServerAuthorization.REQUIRED));
        assertEquals(before, sha(legacy));
        assertEquals(List.of("panel.db", "runtime.db"), files(appHome));
    }

    @Test
    void normalLoginNeedsNoLegacyDatabaseAtAllAndNeverCreatesIt() throws Exception {
        FakeAuthority authority = new FakeAuthority(Clock.systemUTC());
        authority.add("iso-admin", "iso@example.invalid", "+5511999990000", "iso-qa-pass-1", Role.ADMIN, false);
        compose(authority);
        var user = ctx.auth.login("iso-admin", "iso-qa-pass-1".toCharArray());
        assertEquals("iso-admin", user.username());
        assertTrue(ctx.sessions.user().isPresent());
        assertEquals(List.of("runtime.db"), files(appHome), "login worked with no panel.db; none was created");
        assertEquals(List.of(), ctx.audit.recent(5), "no legacy history is simply unavailable");
    }

    @Test
    void aCorruptedOrSymlinkedLegacyFileDoesNotStopTheProductAndIsNeverTouched() throws Exception {
        Path junk = appHome.resolve("panel.db");
        Files.writeString(junk, "not a database ".repeat(40));
        String before = sha(junk);
        compose(new FakeAuthority(Clock.systemUTC()));
        assertEquals(List.of(), ctx.audit.recent(5));
        assertEquals(before, sha(junk));
        assertEquals(List.of("panel.db", "runtime.db"), files(appHome));
    }

    // ---- guardas de código -------------------------------------------------------------------------------------------------------------

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> mainSources() throws Exception {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String rel(Path p) {
        return MAIN.resolve("panel").relativize(p).toString().replace('\\', '/');
    }

    @Test
    void noProductionPathOpensTheLegacyFileReadWriteOrIssuesDdlAgainstIt() throws Exception {
        Set<String> connectionSites = new TreeSet<>();
        Set<String> legacyNameSites = new TreeSet<>();
        Set<String> runtimeOpenSites = new TreeSet<>();
        Set<String> legacyDdl = new TreeSet<>();
        var ddl = java.util.regex.Pattern.compile("(?is)CREATE\\s+(TABLE|INDEX|VIEW|TRIGGER)[^\"]*?\\b(users|trusted_devices|rate_limits|audit_log|meta)\\b");
        for (Path p : mainSources()) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.contains("DriverManager.getConnection(")) connectionSites.add(rel(p));
            if (text.contains("\"panel.db\"")) legacyNameSites.add(rel(p));
            if (text.contains("Database.openRuntime(")) runtimeOpenSites.add(rel(p));
            if (ddl.matcher(text).find()) legacyDdl.add(rel(p));
        }
        assertEquals(Set.of("security/Database.java", "security/LegacyPanelDb.java", "runtime/RuntimeMigrator.java"), connectionSites, "only the three audited SQLite entry points");
        assertEquals(Set.of("app/AppContext.java", "security/Database.java"), legacyNameSites, "panel.db is named only where it is opened read-only and where it is REFUSED as a runtime file");
        assertEquals(Set.of("app/AppContext.java", "runtime/RuntimeMigrator.java"), runtimeOpenSites);
        assertEquals(Set.of(), legacyDdl, "no DDL for the legacy auth/security/history tables exists in the product");
        String appContext = Files.readString(MAIN.resolve("panel/app/AppContext.java"));
        assertTrue(appContext.contains("LegacyPanelDb.openReadOnly("), "the legacy file is opened only through the read-only reader");
        assertFalse(appContext.contains("Database.open("), "no read-write open of any file but the runtime database");
        String legacyReader = Files.readString(MAIN.resolve("panel/security/LegacyPanelDb.java"));
        assertTrue(legacyReader.contains("mode=ro&immutable=1") && legacyReader.contains("query_only=ON"));
        var literal = java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(legacyReader);
        while (literal.find()) { // só literais (SQL de verdade); comentários podem citar os comandos que NÃO existem
            assertFalse(literal.group().matches("(?is).*\\b(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|REPLACE|PRAGMA\\s+(?!query_only))\\b.*"), "write SQL in the legacy reader: " + literal.group());
        }
    }

    @Test
    void theRuntimeMigratorOpensTheSourceOnlyReadOnlyAndNeverWritesWithSourceSql() throws Exception {
        String migrator = Files.readString(MAIN.resolve("panel/runtime/RuntimeMigrator.java"));
        assertTrue(migrator.contains("mode=ro&immutable=1") && migrator.contains("openReadOnly(source)"));
        assertFalse(migrator.contains("DriverManager.getConnection(\"jdbc:sqlite:\" +"), "no read-write JDBC URL in the migrator");
    }

    @Test
    void theRuntimeSchemaCarriesNoAuthDataAndNoForeignKeyToUsers() throws Exception {
        try (var db = panel.security.Database.inMemory()) {
            new panel.repository.ByxWalletRepository(db);
            new panel.repository.ByxPaymentRepository(db);
            new panel.repository.GasGrantRepository(db);
            db.with(c -> {
                try (var r = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                    Set<String> names = new TreeSet<>();
                    while (r.next()) names.add(r.getString(1));
                    assertEquals(Set.of("verified_wallets", "byx_payment_intents", "byx_payment_receipts", "byx_gas_grants"), names);
                    for (String n : names) {
                        try (var fk = c.createStatement().executeQuery("PRAGMA foreign_key_list(" + n + ")")) {
                            while (fk.next()) assertNotEquals("users", fk.getString("table"), n);
                        }
                        try (var cols = c.createStatement().executeQuery("PRAGMA table_info(" + n + ")")) {
                            while (cols.next()) assertFalse(cols.getString("name").matches("(?i).*(password|verifier|secret|otp|token|session|credential).*"), n);
                        }
                    }
                }
                return null;
            });
        }
    }
}
