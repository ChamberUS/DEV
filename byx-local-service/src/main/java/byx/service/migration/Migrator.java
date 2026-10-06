package byx.service.migration;

import byx.service.Log;
import byx.service.auth.Account;
import byx.service.auth.AuthProfile;
import byx.service.auth.AuthorityAdmin;
import byx.service.auth.AuthorityException;
import byx.service.auth.AuthorityState;
import byx.service.auth.AuthorityStore;
import byx.service.auth.EncryptionKeyVault;
import byx.service.auth.PasswordVerifier;
import byx.service.auth.ProviderSettings;
import byx.service.auth.Role;
import byx.service.auth.SecretStoreAnchor;
import byx.service.auth.SecretStoreKeyVault;
import byx.service.identity.AppIdentity;
import byx.service.migration.LegacySecretSource.Access;
import byx.service.migration.LegacySecretSource.Item;
import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Migração da autoridade de autenticação: banco legado (SOMENTE LEITURA) → autoridade do serviço (snapshot AEAD + âncora de rollback + chaves de produção
 * no cofre do serviço), mais a cópia dos segredos de provedor (Resend, Twilio) para o cofre do serviço. Estágios com estado em arquivo (sem segredos):
 * PLANNED → IMPORTED → VERIFIED → PREPARED → CUTOVER_VERIFIED. Qualquer divergência PARA (nada é corrigido automaticamente). Dispositivos confiáveis legados
 * NÃO são migrados (fronteira de autoridade nova): a confiança é reemitida pelo serviço depois de um 2º fator real; o item legado é só preservado (rollback).
 * Nunca imprime hash, segredo, senha, OTP ou token: relatórios só têm contagens, nomes de campos e códigos fixos. Os segredos legados são lidos UMA vez,
 * em memória, e vão direto ao cofre (nunca arquivo, env, área de transferência, argv ou log). Nada legado é apagado.
 */
public final class Migrator {
    public static final int MANIFEST_VERSION = 1;
    public static final String PREPARE_PHRASE = "PREPARE-REAL-MIGRATION";
    public static final String FINALIZE_PHRASE = "FINALIZE-CUTOVER";
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Status { NEW, PLANNED, IMPORTED, VERIFIED, PREPARED, CUTOVER_VERIFIED }

    private final AuthProfile profile;
    private final Path legacyDb;
    private final Path providersFile;
    private final Path securityFile;
    private final LegacySecretSource legacy;
    private final SecretStore secrets;
    private final Clock clock;
    private final String serviceRequirement;

    public Migrator(AuthProfile profile, Path legacyDb, Path providersFile, Path securityFile, LegacySecretSource legacy, SecretStore secrets, Clock clock, String serviceRequirement) {
        this.profile = profile;
        this.legacyDb = legacyDb;
        this.providersFile = providersFile;
        this.securityFile = securityFile;
        this.legacy = legacy;
        this.secrets = secrets;
        this.clock = clock;
        this.serviceRequirement = serviceRequirement;
    }

    private Path manifestFile() {
        return profile.dir().resolve("migration-manifest.json");
    }

    // ---- 1. AUDITORIA (somente leitura; nada é escrito) ------------------------------------------------------------------------------------

    public Map<String, Object> audit() throws MigrationException {
        LegacySnapshot s = LegacyDb.read(legacyDb);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source.sha256", s.dbSha256());
        r.put("source.bytes", s.dbBytes());
        r.put("source.schemaFingerprint", s.schemaFingerprint());
        r.put("source.userVersion", s.userVersion());
        r.put("users.total", s.users().size());
        r.putAll(counts(s));
        r.put("trustedDevices", s.trustedDeviceStates());
        r.put("auditRows", s.auditRows());
        r.put("metaNames", s.metaNames());
        Map<String, String> items = new LinkedHashMap<>();
        for (Item i : Item.values()) {
            items.put(legacy.serviceOf(i), legacy.describe(i).name());
        }
        r.put("legacyKeychainItems", items);
        r.put("legacyKeychainAccount", legacy.account());
        List<String> problems = new ArrayList<>();
        try {
            convertAll(s);
        } catch (MigrationException e) {
            problems.add(e.code + (e.detail.isEmpty() ? "" : " " + e.detail));
        }
        r.put("importProblems", problems);
        return r;
    }

    private static Map<String, Object> counts(LegacySnapshot s) {
        Map<String, Long> roles = new TreeMap<>();
        Map<String, Long> algos = new TreeMap<>();
        long enabled = 0;
        long disabled = 0;
        long verifiedEmail = 0;
        long lowercased = 0;
        long phones = 0;
        for (LegacyUser u : s.users()) {
            roles.merge(String.valueOf(u.role()), 1L, Long::sum);
            algos.merge(PasswordVerifier.algorithmOf(String.valueOf(u.passwordHash())), 1L, Long::sum);
            if ("ACTIVE".equals(u.status())) {
                enabled++;
            } else {
                disabled++;
            }
            verifiedEmail += u.emailVerified() ? 1 : 0;
            lowercased += u.username() != null && !u.username().equals(u.username().toLowerCase(Locale.ROOT)) ? 1 : 0;
            phones += u.phone() != null ? 1 : 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users.byRole", roles);
        m.put("users.enabled", enabled);
        m.put("users.disabled", disabled);
        m.put("users.hashAlgorithms", algos);
        m.put("users.withPhone", phones);
        m.put("users.emailVerified", verifiedEmail);
        m.put("users.usernamesLowercased", lowercased); // normalização VISÍVEL (a autoridade guarda minúsculas; o login é sem diferença de caixa); colisão ⇒ PARA
        return m;
    }

    // ---- conversão e validação (PARA diante de qualquer problema) -------------------------------------------------------------------------

    private List<Account> convertAll(LegacySnapshot s) throws MigrationException {
        List<Account> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> mails = new java.util.HashSet<>();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (LegacyUser u : s.users()) {
            String where = "legacyId=" + u.id();
            if (u.id() <= 0 || !ids.add(u.id())) {
                throw new MigrationException("user_invalid", where + " field=id");
            }
            String username = u.username();
            if (username == null || !username.equals(username.trim()) || !username.toLowerCase(Locale.ROOT).matches("[a-z0-9._-]{1,64}")) {
                throw new MigrationException("user_invalid", where + " field=username"); // nunca normaliza em silêncio
            }
            String lower = username.toLowerCase(Locale.ROOT);
            if (!names.add(lower)) {
                throw new MigrationException("normalization_collision", where + " field=username");
            }
            String email = u.email();
            if (email == null || email.length() > 254 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new MigrationException("user_invalid", where + " field=email");
            }
            if (!mails.add(email.toLowerCase(Locale.ROOT))) {
                throw new MigrationException("normalization_collision", where + " field=email");
            }
            String phone = u.phone();
            if (phone != null && !phone.matches("\\+[1-9][0-9]{7,14}")) {
                throw new MigrationException("user_invalid", where + " field=phone");
            }
            Role role;
            try {
                role = Role.valueOf(String.valueOf(u.role()));
            } catch (IllegalArgumentException e) {
                throw new MigrationException("user_invalid", where + " field=role");
            }
            boolean enabled;
            if ("ACTIVE".equals(u.status())) {
                enabled = true;
            } else if ("DISABLED".equals(u.status())) {
                enabled = false;
            } else {
                throw new MigrationException("user_invalid", where + " field=status");
            }
            if (u.passwordHash() == null || !PasswordVerifier.compatible(u.passwordHash())) {
                throw new MigrationException("hash_incompatible", where + " field=password_hash");
            }
            long created;
            long lastLogin = 0;
            try {
                created = Instant.parse(u.createdAt()).toEpochMilli();
                if (u.lastLoginAt() != null) {
                    lastLogin = Instant.parse(u.lastLoginAt()).toEpochMilli();
                }
            } catch (RuntimeException e) {
                throw new MigrationException("user_invalid", where + " field=timestamps");
            }
            byte[] id = new byte[16];
            rnd.nextBytes(id);
            out.add(new Account(java.util.HexFormat.of().formatHex(id), lower, role, enabled, 1, u.passwordHash(), created, u.id(), email, phone, u.emailVerified(), u.phoneVerified(),
                    u.mustChangePassword(), lastLogin));
        }
        return out;
    }

    private ProviderSettings readProviders() throws MigrationException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(providersFile)) {
            p.load(in);
        } catch (IOException e) {
            throw new MigrationException("providers_unreadable");
        }
        int minutes = 30; // padrão do painel legado quando a chave não existe
        Properties sec = new Properties();
        if (securityFile != null && Files.exists(securityFile)) {
            try (var in = Files.newInputStream(securityFile)) {
                sec.load(in);
            } catch (IOException e) {
                throw new MigrationException("security_config_unreadable");
            }
            try {
                minutes = Integer.parseInt(sec.getProperty("security.admin.sessionTimeoutMinutes", "30").trim());
            } catch (NumberFormatException e) {
                throw new MigrationException("security_config_invalid", "key=security.admin.sessionTimeoutMinutes");
            }
        }
        minutes = Math.max(1, Math.min(ProviderSettings.MAX_ELEVATION_MINUTES, minutes)); // o legado impunha mínimo 1; o serviço limita a 60
        ProviderSettings ps = new ProviderSettings(p.getProperty("resend.fromAddress"), p.getProperty("twilio.accountSid"), p.getProperty("twilio.apiKeySid"), p.getProperty("twilio.verifyServiceSid"), minutes);
        List<String> bad = new ArrayList<>();
        if (!ps.emailConfigured() || ps.resendFromAddress().length() > 254 || ps.resendFromAddress().chars().anyMatch(c -> c < 32 || c == 127)) {
            bad.add("resend.fromAddress");
        }
        if (!ps.smsConfigured()) {
            bad.add("twilio.sids");
        }
        if (!bad.isEmpty()) {
            throw new MigrationException("providers_incomplete", String.join(",", bad));
        }
        return ps;
    }

    // ---- 2. MANIFESTO (sem segredos) --------------------------------------------------------------------------------------------------------

    public Map<String, Object> plan() throws MigrationException {
        LegacySnapshot s = LegacyDb.read(legacyDb);
        convertAll(s); // a migração só é planejada se for importável
        readProviders();
        MigrationState st = loadState();
        if (st != null && st.status.compareTo(Status.IMPORTED) >= 0) {
            throw new MigrationException("already_imported");
        }
        ObjectNode m = JSON.createObjectNode();
        m.put("manifestVersion", MANIFEST_VERSION);
        m.put("createdAt", Instant.now(clock).toString());
        m.put("profile", profile.kind().name());
        ObjectNode src = m.putObject("source");
        src.put("schemaFingerprint", s.schemaFingerprint());
        src.put("userVersion", s.userVersion());
        src.put("dbSha256", s.dbSha256());
        src.put("dbBytes", s.dbBytes());
        src.put("usersDigest", s.usersDigest());
        ObjectNode users = m.putObject("users");
        Map<String, Object> c = counts(s);
        users.put("total", s.users().size());
        users.set("byRole", JSON.valueToTree(c.get("users.byRole")));
        users.put("enabled", (Long) c.get("users.enabled"));
        users.put("disabled", (Long) c.get("users.disabled"));
        users.set("hashAlgorithms", JSON.valueToTree(c.get("users.hashAlgorithms")));
        users.put("usernamesLowercased", (Long) c.get("users.usernamesLowercased"));
        ObjectNode kc = m.putObject("sourceKeychain");
        kc.put("account", legacy.account());
        ObjectNode items = kc.putObject("items");
        for (Item i : Item.values()) {
            ObjectNode n = items.putObject(i.name());
            n.put("service", legacy.serviceOf(i));
            n.put("presence", legacy.describe(i).name());
            n.put("plan", i == Item.TRUSTED_DEVICE ? "NOT_MIGRATED_INVALIDATED_AT_CUTOVER_LEGACY_PRESERVED" : "COPY_TO_SERVICE_VAULT_LEGACY_PRESERVED");
        }
        ObjectNode target = m.putObject("target");
        target.put("authorityFormatVersion", AuthorityStore.FORMAT_VERSION);
        target.put("aead", "AES-256-GCM");
        target.put("encryptionKeyId", profile.encryptionKeyId().name());
        target.put("rollbackAnchorId", profile.anchorId().name());
        target.put("appId", AppIdentity.APP_ID);
        target.put("serviceId", AppIdentity.SERVICE_ID);
        target.put("serviceCodeRequirement", serviceRequirement);
        m.put("status", Status.PLANNED.name());
        try {
            ensureDir();
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(m);
            writeFile(manifestFile(), bytes);
            MigrationState ns = new MigrationState();
            ns.status = Status.PLANNED;
            ns.manifestSha256 = LegacyDb.digest("manifest", new String(bytes, StandardCharsets.UTF_8));
            ns.usersDigest = s.usersDigest();
            saveState(ns);
        } catch (IOException e) {
            throw new MigrationException("manifest_write");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", Status.PLANNED.name());
        r.put("users", s.users().size());
        r.put("source.sha256", s.dbSha256());
        return r;
    }

    // ---- 3. PREPARAR: importar → verificar → copiar segredos ----------------------------------------------------------------------------------

    public Map<String, Object> prepare(String typedPhrase) throws MigrationException {
        if (!PREPARE_PHRASE.equals(typedPhrase)) {
            throw new MigrationException("confirmation_required");
        }
        MigrationState st = loadState();
        if (st == null || st.status == Status.NEW) {
            throw new MigrationException("no_manifest");
        }
        if (st.status == Status.CUTOVER_VERIFIED) {
            throw new MigrationException("already_cutover");
        }
        byte[] mb;
        try {
            mb = Files.readAllBytes(manifestFile());
        } catch (IOException e) {
            throw new MigrationException("no_manifest");
        }
        if (!st.manifestSha256.equals(LegacyDb.digest("manifest", new String(mb, StandardCharsets.UTF_8)))) {
            throw new MigrationException("manifest_changed");
        }
        LegacySnapshot s = LegacyDb.read(legacyDb);
        if (!st.usersDigest.equals(s.usersDigest())) {
            throw new MigrationException("source_users_changed"); // o legado mudou depois do plano: refaça o plano (nada é aplicado)
        }
        ProviderSettings providers = readProviders();
        if (st.status == Status.PLANNED) {
            importAccounts(s, providers);
            st.status = Status.IMPORTED;
            st.sourceDbSha256AtImport = s.dbSha256();
            st.importedAtMs = clock.millis();
            saveStateQuiet(st);
            verifyTarget(s, providers);
            st.status = Status.VERIFIED;
            saveStateQuiet(st);
        } else {
            verifyTarget(s, providers); // retomada: o alvo já existe e é reverificado antes de continuar
            if (st.status == Status.IMPORTED) {
                st.status = Status.VERIFIED;
            }
        }
        boolean allSecrets = true;
        for (Item item : new Item[] {Item.RESEND, Item.TWILIO}) {
            String prev = st.secrets.getOrDefault(item.name(), "PENDING");
            if (!"PREPARED".equals(prev)) {
                st.secrets.put(item.name(), copySecret(item));
            }
            allSecrets &= "PREPARED".equals(st.secrets.get(item.name()));
        }
        st.trustedDevice = "NOT_MIGRATED_LEGACY_PRESERVED";
        if (allSecrets) {
            st.status = Status.PREPARED;
            st.freeze = true;
        }
        saveStateQuiet(st);
        return report(st);
    }

    private void importAccounts(LegacySnapshot s, ProviderSettings providers) throws MigrationException {
        List<Account> accounts = convertAll(s);
        AuthorityStore store = openStore();
        if (store.status() != AuthorityStore.Status.UNINITIALIZED) {
            throw new MigrationException("authority_exists", "status=" + store.status().name().toLowerCase(Locale.ROOT));
        }
        try {
            store.initialize();
            AuthorityAdmin admin = new AuthorityAdmin(store, new PasswordVerifier(), clock);
            for (Account a : accounts) {
                admin.importAccount(a);
            }
            admin.setProviders(providers);
            admin.setFreeze(true); // janela de segurança do cutover desde o primeiro momento: nenhuma mutação que complique o rollback
        } catch (AuthorityException e) {
            throw new MigrationException("authority_write", e.code);
        }
    }

    private AuthorityStore openStore() {
        EncryptionKeyVault vault = new SecretStoreKeyVault(secrets, profile.encryptionKeyId());
        return AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(secrets, profile.anchorId()), vault);
    }

    /** Compara FONTE × ALVO sem expor valores: contagens, campos e impressões digitais internas. Qualquer diferença PARA. */
    private void verifyTarget(LegacySnapshot s, ProviderSettings providers) throws MigrationException {
        AuthorityStore store = openStore();
        if (store.status() != AuthorityStore.Status.TRUSTED) {
            throw new MigrationException("target_untrusted", store.reason());
        }
        AuthorityState t;
        try {
            t = store.current();
        } catch (AuthorityException e) {
            throw new MigrationException("target_untrusted", e.code);
        }
        List<String> diffs = new ArrayList<>();
        if (t.accounts().size() != s.users().size()) {
            diffs.add("count");
        }
        for (LegacyUser u : s.users()) {
            Optional<Account> match = t.accounts().stream().filter(a -> a.legacyUserId() == u.id()).findFirst();
            if (match.isEmpty()) {
                diffs.add("missing legacyId=" + u.id());
                continue;
            }
            Account a = match.get();
            String w = " legacyId=" + u.id();
            if (!a.username().equals(u.username().toLowerCase(Locale.ROOT))) {
                diffs.add("username" + w);
            }
            if (!a.role().name().equals(u.role())) {
                diffs.add("role" + w);
            }
            if (a.enabled() != "ACTIVE".equals(u.status())) {
                diffs.add("enabled" + w);
            }
            if (!LegacyDb.digest("h", a.passwordHash()).equals(LegacyDb.digest("h", u.passwordHash()))) {
                diffs.add("verifier" + w); // comparado por impressão digital interna; o valor nunca é impresso
            }
            if (!PasswordVerifier.algorithmOf(a.passwordHash()).equals(PasswordVerifier.algorithmOf(u.passwordHash()))) {
                diffs.add("algorithm" + w);
            }
            if (!u.email().equals(a.email()) || !java.util.Objects.equals(u.phone(), a.phone())) {
                diffs.add("contact" + w);
            }
            if (a.emailVerified() != u.emailVerified() || a.phoneVerified() != u.phoneVerified() || a.mustChangePassword() != u.mustChangePassword()) {
                diffs.add("flags" + w);
            }
            if (a.credentialVersion() != 1) {
                diffs.add("credentialVersion" + w);
            }
        }
        if (!t.providers().equals(providers)) {
            diffs.add("providers");
        }
        if (!t.devices().isEmpty()) {
            diffs.add("devices_not_empty"); // nenhum dispositivo confiável é migrado
        }
        if (!diffs.isEmpty()) {
            throw new MigrationException("verification_failed", String.join(";", diffs));
        }
    }

    /** LÊ O LEGADO UMA VEZ → escreve o item tipado de produção → relê internamente → compara em tempo constante. Nada é apagado. */
    private String copySecret(Item item) {
        SecretId target = item == Item.RESEND ? profile.resendId() : profile.twilioId();
        LegacySecretSource.Result r = legacy.readOnce(item);
        try {
            switch (r.access()) {
                case ABSENT -> {
                    return "ABSENT_LEGACY";
                }
                case DENIED -> {
                    return "BLOCKED_ACCESS";
                }
                case ERROR -> {
                    return "ERROR";
                }
                default -> {
                }
            }
            byte[] v = r.value();
            if (v == null || v.length == 0) {
                return "ERROR";
            }
            try (SecretBytes incoming = SecretBytes.copyOf(v)) {
                Optional<SecretBytes> existing = secrets.read(target);
                if (existing.isPresent()) {
                    try (SecretBytes e = existing.get()) {
                        byte[] ec = e.copyBytes();
                        boolean same = MessageDigest.isEqual(ec, v);
                        LegacyKeychain.zero(ec);
                        return same ? "PREPARED" : "TARGET_EXISTS_DIFFERENT"; // nunca sobrescreve um segredo de produção diferente
                    }
                }
                secrets.write(target, incoming);
                try (SecretBytes back = secrets.read(target).orElse(null)) {
                    if (back == null) {
                        return "ERROR";
                    }
                    byte[] bc = back.copyBytes();
                    boolean ok = MessageDigest.isEqual(bc, v);
                    LegacyKeychain.zero(bc);
                    return ok ? "PREPARED" : "ERROR";
                }
            }
        } catch (SecretStoreException | RuntimeException e) {
            Log.event("migration_secret", "error");
            return "ERROR";
        } finally {
            LegacyKeychain.zero(r.value());
        }
    }

    // ---- 4. VERIFICAÇÃO (somente leitura) e FINALIZAÇÃO -------------------------------------------------------------------------------------

    public Map<String, Object> verify() throws MigrationException {
        MigrationState st = loadState();
        if (st == null || st.status.compareTo(Status.IMPORTED) < 0) {
            throw new MigrationException("not_imported");
        }
        LegacySnapshot s = LegacyDb.read(legacyDb);
        if (!st.usersDigest.equals(s.usersDigest())) {
            throw new MigrationException("source_users_changed");
        }
        verifyTarget(s, readProviders());
        Map<String, Object> r = report(st);
        r.put("verification", "PASS");
        // segredos de produção presentes (sem reler o legado)
        Map<String, String> present = new LinkedHashMap<>();
        for (SecretId id : new SecretId[] {profile.resendId(), profile.twilioId()}) {
            try (SecretBytes v = secrets.read(id).orElse(null)) {
                present.put(id.name(), v != null ? "PRESENT" : "ABSENT");
            } catch (SecretStoreException e) {
                present.put(id.name(), "UNAVAILABLE");
            }
        }
        r.put("vaultSecrets", present);
        return r;
    }

    /** Depois da validação manual explícita: remove a trava, fecha o estado e marca o legado como ROLLBACK_ONLY. Não apaga nada. */
    public Map<String, Object> finalizeCutover(String typedPhrase) throws MigrationException {
        if (!FINALIZE_PHRASE.equals(typedPhrase)) {
            throw new MigrationException("confirmation_required");
        }
        MigrationState st = loadState();
        if (st == null || st.status != Status.PREPARED) {
            throw new MigrationException("not_prepared");
        }
        AuthorityStore store = openStore();
        try {
            new AuthorityAdmin(store, new PasswordVerifier(), clock).setFreeze(false);
        } catch (AuthorityException e) {
            throw new MigrationException("authority_write", e.code);
        }
        st.status = Status.CUTOVER_VERIFIED;
        st.freeze = false;
        st.legacy = "ROLLBACK_ONLY";
        saveStateQuiet(st);
        return report(st);
    }

    public Map<String, Object> status() throws MigrationException {
        MigrationState st = loadState();
        if (st == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", Status.NEW.name());
            return r;
        }
        return report(st);
    }

    private Map<String, Object> report(MigrationState st) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", st.status.name());
        r.put("secrets", new LinkedHashMap<>(st.secrets));
        r.put("trustedDevice", st.trustedDevice);
        r.put("freeze", st.freeze);
        r.put("legacy", st.legacy);
        return r;
    }

    // ---- estado em arquivo (sem segredos) -----------------------------------------------------------------------------------------------------

    static final class MigrationState {
        Status status = Status.NEW;
        String manifestSha256 = "";
        String usersDigest = "";
        String sourceDbSha256AtImport = "";
        long importedAtMs;
        Map<String, String> secrets = new LinkedHashMap<>();
        String trustedDevice = "NOT_EVALUATED";
        boolean freeze;
        String legacy = "ACTIVE";
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(profile.dir(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void writeFile(Path f, byte[] bytes) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(tmp, bytes);
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private MigrationState loadState() throws MigrationException {
        Path f = profile.migrationState();
        if (!Files.exists(f)) {
            return null;
        }
        try {
            var n = JSON.readTree(Files.readAllBytes(f));
            MigrationState st = new MigrationState();
            st.status = Status.valueOf(n.path("status").asText());
            st.manifestSha256 = n.path("manifestSha256").asText();
            st.usersDigest = n.path("usersDigest").asText();
            st.sourceDbSha256AtImport = n.path("sourceDbSha256AtImport").asText();
            st.importedAtMs = n.path("importedAtMs").asLong();
            n.path("secrets").fields().forEachRemaining(e -> st.secrets.put(e.getKey(), e.getValue().asText()));
            st.trustedDevice = n.path("trustedDevice").asText();
            st.freeze = n.path("freeze").asBoolean();
            st.legacy = n.path("legacy").asText("ACTIVE");
            return st;
        } catch (IOException | RuntimeException e) {
            throw new MigrationException("state_unreadable");
        }
    }

    private void saveState(MigrationState st) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("stateVersion", 1);
        n.put("status", st.status.name());
        n.put("manifestSha256", st.manifestSha256);
        n.put("usersDigest", st.usersDigest);
        n.put("sourceDbSha256AtImport", st.sourceDbSha256AtImport);
        n.put("importedAtMs", st.importedAtMs);
        n.set("secrets", JSON.valueToTree(st.secrets));
        n.put("trustedDevice", st.trustedDevice);
        n.put("freeze", st.freeze);
        n.put("legacy", st.legacy);
        ensureDir();
        writeFile(profile.migrationState(), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n));
    }

    private void saveStateQuiet(MigrationState st) throws MigrationException {
        try {
            saveState(st);
        } catch (IOException e) {
            throw new MigrationException("state_write");
        }
    }
}
