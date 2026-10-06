package byx.service.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor do banco do painel legado como FONTE SOMENTE-LEITURA: abre com {@code mode=ro&immutable=1} (nenhum arquivo auxiliar é criado, nada é escrito,
 * permissões não são alteradas), confere o SHA-256 do arquivo ANTES e DEPOIS da leitura (mudou no meio ⇒ para) e nunca seleciona token de dispositivo
 * confiável nem detalhe de auditoria. Não migra no lugar, não apaga, não reescreve.
 */
final class LegacyDb {
    private LegacyDb() {
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String digest(String label, String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(label.getBytes(StandardCharsets.UTF_8));
            for (String p : parts) {
                byte[] b = (p == null ? "\u0000null" : p).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (b.length >>> 24));
                md.update((byte) (b.length >>> 16));
                md.update((byte) (b.length >>> 8));
                md.update((byte) b.length);
                md.update(b);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static LegacySnapshot read(Path db) throws MigrationException {
        try {
            if (!Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("source_not_regular");
            }
            String before = sha256(db);
            long bytes = Files.size(db);
            List<LegacyUser> users = new ArrayList<>();
            Map<String, Long> devices = new LinkedHashMap<>();
            long audit;
            long userVersion;
            List<String> meta = new ArrayList<>();
            StringBuilder schema = new StringBuilder();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro&immutable=1"); Statement st = c.createStatement()) {
                try (ResultSet r = st.executeQuery("PRAGMA user_version")) {
                    userVersion = r.next() ? r.getLong(1) : 0;
                }
                try (ResultSet r = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND name IN ('users','trusted_devices','audit_log','meta') ORDER BY name")) {
                    int tables = 0;
                    while (r.next()) {
                        schema.append(r.getString(1)).append('=').append(r.getString(2)).append(';');
                        tables++;
                    }
                    if (tables != 4) {
                        throw new MigrationException("source_schema_unexpected", "tables=" + tables);
                    }
                }
                try (ResultSet r = st.executeQuery("SELECT id, username, email, password_hash, role, status, phone, email_verified, phone_verified, must_change_password, created_at, updated_at, "
                        + "last_login_at FROM users ORDER BY id")) {
                    while (r.next()) {
                        users.add(new LegacyUser(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getInt(8) != 0, r.getInt(9) != 0,
                                r.getInt(10) != 0, r.getString(11), r.getString(12), r.getString(13)));
                    }
                }
                // dispositivos confiáveis: só CONTAGEM por estado (nunca token_hash)
                try (ResultSet r = st.executeQuery("SELECT CASE WHEN revoked_at IS NOT NULL THEN 'revoked' WHEN expires_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now') THEN 'expired' ELSE 'active' END, "
                        + "count(*) FROM trusted_devices GROUP BY 1 ORDER BY 1")) {
                    while (r.next()) {
                        devices.put(r.getString(1), r.getLong(2));
                    }
                }
                try (ResultSet r = st.executeQuery("SELECT count(*) FROM audit_log")) {
                    audit = r.next() ? r.getLong(1) : 0;
                }
                try (ResultSet r = st.executeQuery("SELECT name FROM meta ORDER BY name")) {
                    while (r.next()) {
                        meta.add(r.getString(1)); // só o NOME (o valor pode ser segredo local)
                    }
                }
            }
            String after = sha256(db);
            if (!before.equals(after)) {
                throw new MigrationException("source_changed_during_read");
            }
            List<String> parts = new ArrayList<>();
            for (LegacyUser u : users) { // identidade dos USUÁRIOS (sem last_login/updated_at, que mudam a cada uso do app): é o que a importação precisa
                parts.add(u.id() + "|" + u.username() + "|" + u.email() + "|" + u.passwordHash() + "|" + u.role() + "|" + u.status() + "|" + u.phone() + "|" + u.emailVerified() + "|"
                        + u.phoneVerified() + "|" + u.mustChangePassword() + "|" + u.createdAt());
            }
            return new LegacySnapshot(List.copyOf(users), before, digest("schema", schema.toString()), userVersion, digest("users", parts.toArray(new String[0])), Map.copyOf(devices), audit,
                    List.copyOf(meta), bytes);
        } catch (SQLException e) {
            throw new MigrationException("source_unreadable");
        } catch (IOException e) {
            throw new MigrationException("source_io");
        }
    }
}
