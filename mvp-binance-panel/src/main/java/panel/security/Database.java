package panel.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Banco SQLite local do painel (usuários e auditoria). Uma conexão serializada. */
public class Database implements AutoCloseable {
    private final Connection connection;

    private Database(Connection c) {
        this.connection = c;
    }

    public static Database open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return init(DriverManager.getConnection("jdbc:sqlite:" + file));
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
            s.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                      email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                      password_hash TEXT NOT NULL,
                      role TEXT NOT NULL,
                      status TEXT NOT NULL,
                      phone TEXT,
                      email_verified INTEGER NOT NULL DEFAULT 0,
                      phone_verified INTEGER NOT NULL DEFAULT 0,
                      must_change_password INTEGER NOT NULL DEFAULT 0,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      last_login_at TEXT)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts TEXT NOT NULL,
                      event TEXT NOT NULL,
                      actor TEXT,
                      detail TEXT)""");
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
