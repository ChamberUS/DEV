package byx.service.migration;

import byx.service.auth.AuthProfile;
import byx.service.auth.PasswordVerifier;
import byx.service.identity.AppIdentity;
import byx.service.identity.PeerIdentity;
import byx.service.secrets.SecretId;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;

/**
 * QA da migração com dados SINTÉTICOS (só no bundle de TESTE). Tudo vive num home temporário: banco legado SINTÉTICO, arquivo de provedores sintético, itens legados
 * SINTÉTICOS no keychain (namespace {@code invalid.byx-legacy-test}) e alvo de TESTE (ids de escopo TEST). Recusa rodar sobre o home real do serviço, sobre o
 * diretório do painel real ou com caminhos que os contenham. Subcomandos: {@code seed | tamper-source | status | audit | plan | prepare | verify | finalize | cleanup}.
 */
public final class MigrateQaMain {
    private MigrateQaMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: seed|tamper-source|status|audit|plan|prepare|verify|finalize|cleanup");
            System.exit(2);
        }
        String env = System.getenv("BYX_LOCAL_SERVICE_HOME");
        if (env == null || env.isBlank()) {
            System.err.println("qa requires BYX_LOCAL_SERVICE_HOME (a temporary directory)");
            System.exit(2);
        }
        Path home = Path.of(env).toAbsolutePath().normalize();
        Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        for (Path real : new Path[] {AuthProfile.defaultServiceHome().toAbsolutePath().normalize(), userHome.resolve(".mvp-binance-panel")}) {
            if (home.equals(real) || home.startsWith(real) || real.startsWith(home)) {
                System.err.println("qa refuses to run on real data");
                System.exit(4);
            }
        }
        Path legacyDir = home.resolve("legacy");
        Path db = legacyDir.resolve("panel.db");
        Path providers = legacyDir.resolve("providers.properties");
        AuthProfile profile = AuthProfile.qa(home.resolve("qa-authority"));
        LegacyKeychain legacy = new LegacyKeychain(LegacyKeychain.Names.TEST, false);
        String team = PeerIdentity.selfTeamId();
        Migrator m = new Migrator(profile, db, providers, legacy, profile.secrets(), Clock.systemUTC(), team == null ? "unsigned" : AppIdentity.requirement(AppIdentity.SERVICE_ID, team));
        int rc = 0;
        switch (args[0]) {
            case "seed" -> seed(legacyDir, db, providers, legacy);
            case "tamper-source" -> tamper(db);
            case "cleanup" -> {
                for (LegacySecretSource.Item i : LegacySecretSource.Item.values()) {
                    legacy.deleteForTest(i);
                }
                for (SecretId id : new SecretId[] {profile.anchorId(), profile.encryptionKeyId(), profile.resendId(), profile.twilioId()}) {
                    profile.secrets().delete(id);
                }
                System.out.println("RESULT OK cleanup");
            }
            default -> rc = MigrateMain.run(m, args[0], new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }
        System.exit(rc);
    }

    /** Banco legado SINTÉTICO com o MESMO esquema do painel (e hashes Argon2id do formato legado, de senhas aleatórias descartadas) + itens legados sintéticos. */
    private static void seed(Path legacyDir, Path db, Path providers, LegacyKeychain legacy) throws Exception {
        Files.createDirectories(legacyDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.deleteIfExists(db);
        PasswordVerifier pv = new PasswordVerifier();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE, email TEXT NOT NULL UNIQUE COLLATE NOCASE, password_hash TEXT NOT NULL, "
                    + "role TEXT NOT NULL, status TEXT NOT NULL, phone TEXT, email_verified INTEGER NOT NULL DEFAULT 0, phone_verified INTEGER NOT NULL DEFAULT 0, "
                    + "must_change_password INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_login_at TEXT)");
            st.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, event TEXT NOT NULL, actor TEXT, detail TEXT)");
            st.execute("CREATE TABLE trusted_devices (device_id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, display_name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, "
                    + "last_used_at TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT, FOREIGN KEY(user_id) REFERENCES users(id))");
            st.execute("CREATE TABLE meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            String[][] users = {{"Qa_Admin", "qa.admin@example.test", "ADMIN", "ACTIVE", "+5511900000001"}, {"qa_user", "qa.user@example.test", "USER", "ACTIVE", "+5511900000002"},
                {"qa_off", "qa.off@example.test", "USER", "DISABLED", null}};
            for (String[] u : users) {
                byte[] r = new byte[18];
                new java.security.SecureRandom().nextBytes(r);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO users(username,email,password_hash,role,status,phone,email_verified,phone_verified,must_change_password,created_at,updated_at,last_login_at) "
                        + "VALUES(?,?,?,?,?,?,1,0,0,'2026-10-02T04:00:12.751772Z','2026-10-02T04:00:12.751772Z',NULL)")) {
                    p.setString(1, u[0]);
                    p.setString(2, u[1]);
                    p.setString(3, pv.hash(java.util.Base64.getEncoder().encodeToString(r).toCharArray())); // senha aleatória e descartada
                    p.setString(4, u[2]);
                    p.setString(5, u[3]);
                    p.setString(6, u[4]);
                    p.executeUpdate();
                }
            }
            st.execute("INSERT INTO audit_log(ts,event,actor,detail) VALUES('2026-10-02T04:00:12Z','INITIAL_ADMIN_CREATED','qa','')");
            st.execute("INSERT INTO trusted_devices VALUES('dev-1',1,'This Mac','" + "f".repeat(64) + "','2026-10-03T00:00:00Z','2026-10-03T00:00:00Z','2099-01-01T00:00:00Z',NULL)");
            st.execute("INSERT INTO meta VALUES('rl_pepper','synthetic')");
        }
        Files.writeString(providers, "resend.fromAddress=QA <qa@example.test>\ntwilio.accountSid=AC" + "1".repeat(32) + "\ntwilio.apiKeySid=SK" + "2".repeat(32) + "\ntwilio.verifyServiceSid=VA" + "3".repeat(32) + "\n");
        legacy.addForTest(LegacySecretSource.Item.RESEND, "re_SYNTHETIC_LEGACY_RESEND_KEY".getBytes(StandardCharsets.UTF_8));
        legacy.addForTest(LegacySecretSource.Item.TWILIO, "SYNTHETIC-LEGACY-TWILIO-SECRET".getBytes(StandardCharsets.UTF_8));
        legacy.addForTest(LegacySecretSource.Item.TRUSTED_DEVICE, "SYNTHETIC-LEGACY-DEVICE-TOKEN".getBytes(StandardCharsets.UTF_8));
        System.out.println("RESULT OK seed");
    }

    /** Mexe no banco SINTÉTICO (muda um papel) para provar que prepare recusa um legado que mudou depois do plano. */
    private static void tamper(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("UPDATE users SET role='ADMIN' WHERE username='qa_user'");
        }
        System.out.println("RESULT OK tamper-source");
    }

}
