package panel.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import panel.repository.ByxPaymentRepository;
import panel.repository.ByxWalletRepository;
import panel.repository.GasGrantRepository;
import panel.security.Database;

/**
 * Migrador dos dados NÃO-auth do {@code panel.db} legado para o banco de runtime novo. A FONTE é aberta SEMPRE somente leitura e imutável (nunca recebe DDL, INSERT, UPDATE
 * nem DELETE; o SHA-256 é conferido antes/depois). Só as tabelas da lista fechada são copiadas, e só colunas idênticas às do alvo; tabelas de auth/segurança/histórico nunca
 * são copiadas. A tabela {@code users} não é copiada: {@code user_id} segue como a identidade estável legada, SEM chave estrangeira física. Relatórios são REDIGIDOS
 * (hashes, contagens, nomes de tabela/coluna; nenhum valor). Independente do {@code byx-migrate} (cutover de autenticação): não toca a autoridade nem o Keychain.
 */
public final class RuntimeMigrator {
    public enum Decision { COPY_TRANSFORM, DROP_AUTH, DROP_HISTORY_STAYS_LEGACY, DROP_INTERNAL, NOT_APPROVED }

    /** Tabelas BYX de domínio aprovadas: {@code user_id} passa a identidade legada sem FK (TRANSFORM). */
    public static final List<String> ALLOWLIST = List.of("verified_wallets", "byx_payment_intents", "byx_payment_receipts", "byx_gas_grants");
    private static final Map<String, Decision> KNOWN = Map.of(
            "users", Decision.DROP_AUTH, "trusted_devices", Decision.DROP_AUTH, "rate_limits", Decision.DROP_AUTH, "meta", Decision.DROP_AUTH,
            "audit_log", Decision.DROP_HISTORY_STAYS_LEGACY, "sqlite_sequence", Decision.DROP_INTERNAL);
    private static final java.util.regex.Pattern SENSITIVE_COLUMN = java.util.regex.Pattern.compile("(?i).*(password|passwd|verifier|secret|otp|session|credential|api_?key|private).*");
    public static final String PREPARE_PHRASE = "PREPARE-RUNTIME-DB";
    static final String STATE_TABLE = "runtime_migration";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final class MigrationException extends Exception {
        public final String code;

        public MigrationException(String code) {
            super(code);
            this.code = code;
        }
    }

    private RuntimeMigrator() { }

    // ---- plan (somente leitura) -----------------------------------------------------------------------------------------------------

    /** Manifesto REDIGIDO: hash da fonte, impressão do esquema, contagens, decisão por tabela. Não escreve em lugar nenhum. */
    public static ObjectNode plan(Path source) throws MigrationException {
        requireRegular(source);
        String sha = sha256(source);
        try (Connection c = openReadOnly(source)) {
            ObjectNode m = JSON.createObjectNode();
            m.put("kind", "runtime-db-migration-plan");
            m.put("sourceSha256", sha);
            m.put("sourceSidecars", String.join(",", sidecars(source)));
            m.put("sourceUserVersion", scalarInt(c, "PRAGMA user_version"));
            m.put("targetSchemaVersion", Database.RUNTIME_SCHEMA_VERSION);
            m.put("targetFile", Database.RUNTIME_FILE_NAME);
            StringBuilder fingerprint = new StringBuilder();
            ArrayNode tables = m.putArray("tables");
            Set<String> names = new TreeSet<>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT name,sql FROM sqlite_master WHERE type IN ('table','index') ORDER BY type,name")) {
                while (r.next()) {
                    fingerprint.append(r.getString(1)).append('\n').append(String.valueOf(r.getString(2))).append('\n');
                    names.add(r.getString(1));
                }
            }
            m.put("sourceSchemaFingerprint", sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
            for (String name : tableNames(c)) {
                ObjectNode t = tables.addObject();
                t.put("table", name);
                t.put("rows", count(c, name));
                t.put("decision", decisionOf(name).name());
                ArrayNode cols = t.putArray("columns");
                for (String col : columns(c, name)) cols.add(col);
                if (ALLOWLIST.contains(name)) {
                    t.put("transformation", "drop FOREIGN KEY to users; user_id kept as the stable legacy user id (no physical FK)");
                }
            }
            ArrayNode missing = m.putArray("allowlistedButAbsent");
            for (String name : ALLOWLIST) if (!names.contains(name)) missing.add(name);
            ArrayNode skipped = m.putArray("skippedTables");
            for (String name : tableNames(c)) if (!ALLOWLIST.contains(name)) skipped.add(name);
            return m;
        } catch (SQLException e) {
            throw new MigrationException("source_unreadable");
        }
    }

    // ---- prepare ---------------------------------------------------------------------------------------------------------------------

    /** Copia só as tabelas aprovadas para o runtime NOVO. Exige a frase; recusa alvo já existente e fonte com arquivos auxiliares. Fonte intocada (SHA conferido). */
    public static ObjectNode prepare(Path source, Path target, String phrase) throws MigrationException {
        if (!PREPARE_PHRASE.equals(phrase)) throw new MigrationException("confirmation_required");
        requireRegular(source);
        if (Database.RUNTIME_FILE_NAME.equals(source.getFileName().toString())) throw new MigrationException("source_is_runtime");
        if (source.toAbsolutePath().equals(target.toAbsolutePath())) throw new MigrationException("source_equals_target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new MigrationException("target_exists");
        if (!sidecars(source).isEmpty()) throw new MigrationException("source_has_journal_or_wal");
        String before = sha256(source);
        ObjectNode result = JSON.createObjectNode();
        try (Connection src = openReadOnly(source); Database db = Database.openRuntime(target)) {
            // as tabelas do alvo vêm dos próprios repositórios (fonte única do DDL)
            new ByxWalletRepository(db);
            new ByxPaymentRepository(db);
            new GasGrantRepository(db);
            Set<String> present = new TreeSet<>(tableNames(src));
            ObjectNode copied = result.putObject("rowsCopied");
            db.with(t -> {
                t.setAutoCommit(false);
                try {
                    for (String table : ALLOWLIST) {
                        if (!present.contains(table)) {
                            copied.put(table, 0);
                            continue;
                        }
                        copied.put(table, copyTable(src, t, table));
                    }
                    try (var s = t.createStatement()) {
                        s.execute("CREATE TABLE " + STATE_TABLE + "(source_sha256 TEXT NOT NULL, schema_version INTEGER NOT NULL, tables TEXT NOT NULL)");
                    }
                    try (PreparedStatement ps = t.prepareStatement("INSERT INTO " + STATE_TABLE + " VALUES(?,?,?)")) {
                        ps.setString(1, before);
                        ps.setInt(2, Database.RUNTIME_SCHEMA_VERSION);
                        ps.setString(3, String.join(",", ALLOWLIST));
                        ps.executeUpdate();
                    }
                    t.commit();
                } catch (SQLException | RuntimeException e) {
                    t.rollback();
                    throw e;
                } finally {
                    t.setAutoCommit(true);
                }
                return null;
            });
        } catch (SQLException | IllegalStateException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw new MigrationException("prepare_failed");
        } catch (Refused r) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw new MigrationException(r.code);
        }
        if (!before.equals(sha256(source))) throw new MigrationException("source_changed");
        result.put("sourceSha256", before);
        result.put("sourceUnchanged", true);
        return result;
    }

    /** Recusa de política dentro da transação (vira MigrationException em prepare, com rollback e alvo removido). */
    private static final class Refused extends RuntimeException {
        final String code;

        Refused(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private static int copyTable(Connection src, Connection dst, String table) throws SQLException {
        List<String> srcCols = columns(src, table);
        List<String> dstCols = columns(dst, table);
        if (!srcCols.equals(dstCols)) throw new Refused("column_mismatch_" + table);
        for (String col : srcCols) if (SENSITIVE_COLUMN.matcher(col).matches()) throw new Refused("sensitive_column_refused");
        String list = String.join(",", srcCols);
        String marks = "?,".repeat(srcCols.size());
        marks = marks.substring(0, marks.length() - 1);
        int n = 0;
        try (var q = src.createStatement(); ResultSet r = q.executeQuery("SELECT " + list + " FROM " + table + " ORDER BY rowid");
                PreparedStatement ps = dst.prepareStatement("INSERT INTO " + table + "(" + list + ") VALUES(" + marks + ")")) {
            while (r.next()) {
                for (int i = 1; i <= srcCols.size(); i++) ps.setObject(i, r.getObject(i));
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    // ---- verify (somente leitura nos dois lados) --------------------------------------------------------------------------------------

    public static ObjectNode verify(Path source, Path target) throws MigrationException {
        requireRegular(source);
        requireRegular(target);
        String sha = sha256(source);
        ObjectNode result = JSON.createObjectNode();
        try (Connection src = openReadOnly(source); Connection dst = openReadOnly(target)) {
            Set<String> present = new TreeSet<>(tableNames(dst));
            Set<String> allowed = new TreeSet<>(ALLOWLIST);
            allowed.add(STATE_TABLE);
            if (!allowed.containsAll(present)) throw new MigrationException("target_has_unapproved_table");
            if (scalarInt(dst, "PRAGMA user_version") != Database.RUNTIME_SCHEMA_VERSION) throw new MigrationException("target_schema_version");
            String recorded = null;
            try (var s = dst.createStatement(); var r = s.executeQuery("SELECT source_sha256 FROM " + STATE_TABLE)) {
                if (r.next()) recorded = r.getString(1);
            }
            if (!sha.equals(recorded)) throw new MigrationException("source_changed_since_prepare");
            Set<String> srcTables = new TreeSet<>(tableNames(src));
            ObjectNode tables = result.putObject("tables");
            for (String table : ALLOWLIST) {
                long a = srcTables.contains(table) ? count(src, table) : 0;
                long b = present.contains(table) ? count(dst, table) : 0;
                if (a != b) throw new MigrationException("count_mismatch_" + table);
                String da = srcTables.contains(table) ? digest(src, table) : "";
                String db = present.contains(table) ? digest(dst, table) : "";
                if (!da.equals(db)) throw new MigrationException("content_mismatch_" + table);
                tables.putObject(table).put("rows", a).put("contentDigest", da.isEmpty() ? "-" : da);
            }
            for (String t : present) {
                if (t.equals(STATE_TABLE)) continue;
                for (String col : columns(dst, t)) if (SENSITIVE_COLUMN.matcher(col).matches()) throw new MigrationException("sensitive_column_in_target");
                try (var s = dst.createStatement(); var r = s.executeQuery("PRAGMA foreign_key_list(" + t + ")")) {
                    while (r.next()) if ("users".equalsIgnoreCase(r.getString("table"))) throw new MigrationException("foreign_key_to_users");
                }
            }
        } catch (SQLException e) {
            throw new MigrationException("verify_unreadable");
        }
        if (!sha.equals(sha256(source))) throw new MigrationException("source_changed");
        result.put("verification", "PASS");
        result.put("sourceSha256", sha);
        return result;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------------------------

    private static Decision decisionOf(String table) {
        if (ALLOWLIST.contains(table)) return Decision.COPY_TRANSFORM;
        return KNOWN.getOrDefault(table, Decision.NOT_APPROVED);
    }

    static Connection openReadOnly(Path file) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + file.toAbsolutePath().toUri().getRawPath() + "?mode=ro&immutable=1");
        try (var s = c.createStatement()) {
            s.execute("PRAGMA query_only=ON");
        }
        return c;
    }

    private static void requireRegular(Path p) throws MigrationException {
        if (!Files.isRegularFile(p.toAbsolutePath(), LinkOption.NOFOLLOW_LINKS)) throw new MigrationException("file_not_regular");
    }

    private static List<String> sidecars(Path db) {
        List<String> out = new ArrayList<>();
        for (String suffix : new String[] {"-journal", "-wal", "-shm"}) {
            if (Files.exists(db.resolveSibling(db.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS)) out.add(suffix);
        }
        return out;
    }

    private static List<String> tableNames(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (r.next()) out.add(r.getString("name"));
        }
        return out;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private static int scalarInt(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    /** Digest de conteúdo: nunca sai valor, só o hash das linhas em ordem canônica. */
    private static String digest(Connection c, String table) throws SQLException {
        List<String> cols = columns(c, table);
        MessageDigest md = sha();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT " + String.join(",", cols) + " FROM " + table + " ORDER BY " + String.join(",", cols))) {
            while (r.next()) {
                for (int i = 1; i <= cols.size(); i++) md.update((String.valueOf(r.getObject(i)) + '\u0001').getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] data) {
        return HexFormat.of().formatHex(sha().digest(data));
    }

    static String sha256(Path file) throws MigrationException {
        try {
            MessageDigest md = sha();
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new MigrationException("file_unreadable");
        }
    }

    public static String toJson(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
