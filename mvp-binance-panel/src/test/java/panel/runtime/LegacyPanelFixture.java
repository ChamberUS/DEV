package panel.runtime;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Banco legado SINTÉTICO (esquema idêntico ao panel.db antigo, valores fictícios). Nunca é o arquivo real do usuário. */
final class LegacyPanelFixture {
    static final String CANARY_PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$CANARYSALT$CANARYHASH";
    static final String CANARY_EMAIL = "canary-legacy@example.invalid";
    static final String CANARY_ADDRESS = "byx1" + "c".repeat(38);
    static final String CANARY_TX = "CANARYTXHASH0123456789";
    static final String CANARY_DEVICE_TOKEN = "CANARY-DEVICE-TOKEN-HASH";

    private LegacyPanelFixture() { }

    /** Cria o banco com todas as tabelas legadas e dados sintéticos (inclui FKs para users, como o original). */
    static Path create(Path file) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE, email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                      password_hash TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, phone TEXT, email_verified INTEGER NOT NULL DEFAULT 0,
                      phone_verified INTEGER NOT NULL DEFAULT 0, must_change_password INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_login_at TEXT)""");
            s.execute("""
                    CREATE TABLE trusted_devices (device_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, display_name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                      created_at TEXT NOT NULL, last_used_at TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT, FOREIGN KEY(user_id) REFERENCES users(id))""");
            s.execute("CREATE TABLE rate_limits (subject TEXT PRIMARY KEY, failures INTEGER NOT NULL, last_at INTEGER NOT NULL, next_allowed_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, event TEXT NOT NULL, actor TEXT, detail TEXT)");
            s.execute("""
                    CREATE TABLE verified_wallets (user_id INTEGER NOT NULL, address TEXT NOT NULL, public_key TEXT NOT NULL, chain_id TEXT NOT NULL,
                      genesis_fingerprint TEXT NOT NULL, verified_at TEXT NOT NULL, last_verified_at TEXT NOT NULL, revoked_at TEXT,
                      PRIMARY KEY(user_id,address,chain_id,genesis_fingerprint), FOREIGN KEY(user_id) REFERENCES users(id))""");
            s.execute("""
                    CREATE TABLE byx_payment_intents(id TEXT PRIMARY KEY,user_id INTEGER NOT NULL,wallet TEXT NOT NULL,chain_id TEXT NOT NULL,genesis TEXT NOT NULL,
                      recipient TEXT NOT NULL,amount TEXT NOT NULL,purpose TEXT NOT NULL,created_at TEXT NOT NULL,expires_at TEXT NOT NULL,status TEXT NOT NULL,
                      FOREIGN KEY(user_id) REFERENCES users(id))""");
            s.execute("""
                    CREATE TABLE byx_payment_receipts(intent_id TEXT PRIMARY KEY,user_id INTEGER NOT NULL,wallet TEXT NOT NULL,chain_id TEXT NOT NULL,genesis TEXT NOT NULL,
                      tx_hash TEXT NOT NULL,height INTEGER NOT NULL,amount TEXT NOT NULL,confirmed_at TEXT NOT NULL,starts_at TEXT NOT NULL,expires_at TEXT NOT NULL,
                      FOREIGN KEY(intent_id) REFERENCES byx_payment_intents(id))""");
            s.execute("""
                    CREATE TABLE byx_gas_grants(user_id INTEGER NOT NULL,address TEXT NOT NULL,chain TEXT NOT NULL,genesis TEXT NOT NULL,granter TEXT NOT NULL,
                      spend_limit TEXT NOT NULL,expiration TEXT NOT NULL,state TEXT NOT NULL,tx_hash TEXT NOT NULL,remaining TEXT NOT NULL,PRIMARY KEY(user_id,chain,genesis))""");
            s.execute("INSERT INTO users(username,email,password_hash,role,status,phone,created_at,updated_at) VALUES ('boss','" + CANARY_EMAIL + "','" + CANARY_PASSWORD_HASH
                    + "','ADMIN','ACTIVE','+5511900000000','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            s.execute("INSERT INTO trusted_devices VALUES('dev1',1,'laptop','" + CANARY_DEVICE_TOKEN + "','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2027-01-01T00:00:00Z',NULL)");
            s.execute("INSERT INTO rate_limits VALUES('subject-hash',1,1,2)");
            s.execute("INSERT INTO meta VALUES('rl_pepper','CANARY-PEPPER')");
            s.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('2026-01-02T00:00:00Z','LOGIN_SUCCESS','boss','ok')");
            s.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('2026-01-03T00:00:00Z','LOGIN_FAILED','CANARY-OLD-typed-secret','invalid credentials')");
            s.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('2026-01-04T00:00:00Z','LOGIN_FAILED','boss','invalid credentials')");
            s.execute("INSERT INTO verified_wallets VALUES(1,'" + CANARY_ADDRESS + "','CANARYPUBKEY','byx-test','a1','2026-01-05T00:00:00Z','2026-01-05T00:00:00Z',NULL)");
            s.execute("INSERT INTO byx_payment_intents VALUES('intent1',1,'" + CANARY_ADDRESS + "','byx-test','a1','byx1recipient','10000','PREMIUM','2026-01-06T00:00:00Z','2026-01-06T00:05:00Z','CONSUMED')");
            s.execute("INSERT INTO byx_payment_receipts VALUES('intent1',1,'" + CANARY_ADDRESS + "','byx-test','a1','" + CANARY_TX + "',55,'10000','2026-01-06T00:01:00Z','2026-01-06T00:01:00Z','2026-02-06T00:01:00Z')");
            s.execute("INSERT INTO byx_gas_grants VALUES(1,'" + CANARY_ADDRESS + "','byx-test','a1','byx1granter','30000','2026-01-07T00:00:00Z','ACTIVE','abc','30000')");
        }
        return file;
    }

    static void drop(Path file, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            s.execute("DROP TABLE " + table);
        }
    }

    static String schemaOf(Path file) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + file.toAbsolutePath().toUri().getRawPath() + "?mode=ro&immutable=1");
                PreparedStatement ps = c.prepareStatement("SELECT group_concat(name||':'||IFNULL(sql,''),'~') FROM (SELECT name,sql FROM sqlite_master ORDER BY name)");
                var r = ps.executeQuery()) {
            return r.next() ? r.getString(1) : "";
        }
    }
}
