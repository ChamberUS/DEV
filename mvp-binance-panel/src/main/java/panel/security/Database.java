package panel.security;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Banco de RUNTIME do painel (dados BYX não-auth). NÃO é o banco legado: {@code panel.db} é a fonte de rollback e o produto nunca o abre por aqui (nome recusado,
 * sem DDL/INSERT/UPDATE/DELETE contra ele; o histórico legado só é lido por {@link LegacyPanelDb}, somente leitura e imutável). Auth, sessão, OTP, segredos e
 * dispositivos confiáveis vivem no serviço local e NUNCA neste banco. A versão do esquema é explícita ({@code PRAGMA user_version}); uma versão mais nova que a
 * conhecida falha fechado. Os repositórios criam as próprias tabelas (sem chave estrangeira para tabelas de auth: {@code user_id} é a identidade estável legada
 * que o serviço apresenta, sem vínculo físico).
 */
public class Database implements AutoCloseable {
    public static final int RUNTIME_SCHEMA_VERSION = 1;
    public static final String RUNTIME_FILE_NAME = "runtime.db";
    /** Nome do banco legado: recusado aqui, sempre. */
    static final String LEGACY_FILE_NAME = "panel.db";

    private final Connection connection;

    private Database(Connection c) {
        this.connection = c;
    }

    /** Abre (ou cria, 0700/0600, sem seguir symlink) o banco de runtime. Recusa o nome do banco legado. */
    public static Database openRuntime(Path file) {
        Path abs = file.toAbsolutePath();
        if (LEGACY_FILE_NAME.equals(abs.getFileName().toString())) {
            throw new IllegalArgumentException("legacy_database_refused");
        }
        try {
            PrivateFiles.prepareDirectory(abs.getParent()); // 0700 novo; falha fechada se fora da política
            PrivateFiles.prepareFile(abs); // 0600 novo (antes do SQLite); existente não é alterado
            return init(DriverManager.getConnection("jdbc:sqlite:" + abs));
        } catch (IllegalStateException e) {
            throw e; // política de arquivo (InsecureStorageException) ou esquema novo demais: não continua em silêncio e não vira "banco indisponível"
        } catch (Exception e) {
            throw new IllegalStateException("Could not open local database", e);
        }
    }

    public static Database inMemory() {
        try {
            return init(DriverManager.getConnection("jdbc:sqlite::memory:"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Database init(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            int version;
            try (var r = s.executeQuery("PRAGMA user_version")) {
                version = r.next() ? r.getInt(1) : 0;
            }
            if (version > RUNTIME_SCHEMA_VERSION) {
                c.close();
                throw new IllegalStateException("runtime_schema_too_new");
            }
            if (version < RUNTIME_SCHEMA_VERSION) {
                s.execute("PRAGMA user_version=" + RUNTIME_SCHEMA_VERSION);
            }
        }
        return new Database(c);
    }

    public synchronized <T> T with(SqlFunction<Connection, T> f) {
        try {
            return f.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface SqlFunction<A, R> {
        R apply(A a) throws SQLException;
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
