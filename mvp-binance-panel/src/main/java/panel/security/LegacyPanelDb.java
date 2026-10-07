package panel.security;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conexão DEDICADA, SOMENTE LEITURA e IMUTÁVEL ao {@code panel.db} legado (fonte de rollback). {@code mode=ro&immutable=1}: o SQLite não escreve, não cria arquivo
 * auxiliar e não pega trava; {@code query_only=ON} é a segunda camada. Não há {@code Database.init}, DDL, CREATE TABLE nem fallback para leitura/escrita: arquivo ausente,
 * symlink, não regular, corrompido ou sem a tabela vira "indisponível" e o produto NUNCA tenta recriá-lo. API fechada (sem SQL livre).
 */
public final class LegacyPanelDb implements LegacyAuditHistory, AutoCloseable {
    private final Connection connection;

    private LegacyPanelDb(Connection connection) {
        this.connection = connection;
    }

    /** Abre o banco legado só para leitura; vazio se não puder (nunca cria, nunca escreve). */
    public static Optional<LegacyPanelDb> openReadOnly(Path file) {
        try {
            Path abs = file.toAbsolutePath();
            if (!Files.isRegularFile(abs, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + readOnlyUri(abs));
            try (var s = c.createStatement()) {
                s.execute("PRAGMA query_only=ON");
            }
            return Optional.of(new LegacyPanelDb(c));
        } catch (SQLException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** URI de arquivo SQLite imutável/somente leitura (caracteres especiais do caminho são codificados). */
    static String readOnlyUri(Path abs) {
        return "file:" + abs.toUri().getRawPath() + "?mode=ro&immutable=1";
    }

    @Override
    public synchronized List<String[]> recent(int limit) {
        return query("SELECT ts,event,actor,detail FROM audit_log ORDER BY id DESC LIMIT ?", null, limit);
    }

    @Override
    public synchronized List<String[]> recentFor(String actor, int limit) {
        return query("SELECT ts,event,actor,detail FROM audit_log WHERE actor=? COLLATE NOCASE ORDER BY id DESC LIMIT ?", actor, limit);
    }

    private List<String[]> query(String sql, String actor, int limit) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            if (actor != null) {
                ps.setString(i++, actor);
            }
            ps.setInt(i, limit);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
                }
            }
        } catch (SQLException e) {
            return List.of(); // tabela ausente/banco ilegível: indisponível, sem criar nada
        }
        return out;
    }

    @Override
    public synchronized boolean legacyUsernameExists(String username) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE username=? COLLATE NOCASE")) {
            ps.setString(1, username);
            try (ResultSet r = ps.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // encerrando
        }
    }
}
