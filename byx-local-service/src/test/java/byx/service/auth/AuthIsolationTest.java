package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.secrets.ScopedSecretStore;
import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStatus;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guardas de fonte e de comportamento: separação PRODUÇÃO × QA (ids, namespaces, caminhos), nenhum provedor de OTP de desenvolvimento no produto,
 * nenhuma operação proibida, nenhuma flag que reabra o caminho antigo ou troque o perfil.
 */
class AuthIsolationTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static Set<String> filesContaining(String needle) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains(needle)) {
                    out.add(MAIN.relativize(f).toString().replace("byx/service/", ""));
                }
            }
        }
        return out;
    }

    private static final class MapStore implements SecretStore {
        final Map<SecretId, byte[]> items = new HashMap<>();

        public SecretStatus status() {
            return SecretStatus.SECURE_STORAGE_AVAILABLE;
        }

        public void write(SecretId id, SecretBytes v) {
            items.put(id, v.copyBytes());
        }

        public boolean update(SecretId id, SecretBytes v) {
            return items.replace(id, v.copyBytes()) != null;
        }

        public Optional<SecretBytes> read(SecretId id) {
            return Optional.ofNullable(items.get(id)).map(SecretBytes::copyOf);
        }

        public boolean delete(SecretId id) {
            return items.remove(id) != null;
        }
    }

    // ---- produção × QA ---------------------------------------------------------------------------------------------------------------

    @Test
    void aScopedStoreCannotTouchTheOtherScope() throws Exception {
        MapStore backend = new MapStore();
        SecretStore qa = new ScopedSecretStore(backend, SecretId.Scope.TEST);
        SecretStore prod = new ScopedSecretStore(backend, SecretId.Scope.PRODUCTION);
        try (SecretBytes v = SecretBytes.random(32)) {
            prod.write(SecretId.AUTHORITY_ENCRYPTION_KEY, v);
            qa.write(SecretId.AUTHORITY_TEST_ENCRYPTION_KEY, v);
            for (SecretId real : new SecretId[] {SecretId.AUTHORITY_ENCRYPTION_KEY, SecretId.AUTHORITY_ROLLBACK_ANCHOR, SecretId.RESEND_API_KEY, SecretId.TWILIO_API_SECRET}) {
                assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> qa.read(real)).status(), "QA cannot read " + real);
                assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> qa.write(real, v)).status(), "QA cannot overwrite " + real);
                assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> qa.update(real, v)).status());
                assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> qa.delete(real)).status(), "QA cannot clean " + real);
            }
            for (SecretId test : new SecretId[] {SecretId.TEST_CANARY, SecretId.AUTHORITY_TEST_ANCHOR, SecretId.AUTHORITY_TEST_ENCRYPTION_KEY, SecretId.MIGRATION_TEST_RESEND}) {
                assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> prod.read(test)).status(), "production never uses " + test);
            }
        }
        assertTrue(backend.items.containsKey(SecretId.AUTHORITY_ENCRYPTION_KEY), "and the real item was untouched by the failed QA attempts");
    }

    @Test
    void profilesNeverMixIdsOrPaths() throws Exception {
        AuthProfile prod = AuthProfile.production(Path.of("/Users/someone/.byx-local-service"));
        AuthProfile qa = AuthProfile.qa(Path.of("/tmp/qa/qa-authority"));
        for (SecretId id : new SecretId[] {prod.anchorId(), prod.encryptionKeyId(), prod.resendId(), prod.twilioId()}) {
            assertEquals(SecretId.Scope.PRODUCTION, id.scope());
        }
        for (SecretId id : new SecretId[] {qa.anchorId(), qa.encryptionKeyId(), qa.resendId(), qa.twilioId()}) {
            assertEquals(SecretId.Scope.TEST, id.scope());
        }
        assertTrue(prod.snapshot().startsWith("/Users/someone/.byx-local-service/authority"));
        assertFalse(qa.snapshot().startsWith("/Users/someone"), "QA paths are nowhere near the real service home");
        assertFalse(prod.migrationState().equals(qa.migrationState()));
    }

    @Test
    void productionIdsAreReferencedOnlyByTheProductionProfile() throws IOException {
        for (String prodId : new String[] {"AUTHORITY_ENCRYPTION_KEY", "AUTHORITY_ROLLBACK_ANCHOR", "RESEND_API_KEY", "TWILIO_API_SECRET"}) {
            Set<String> where = filesContaining("SecretId." + prodId);
            assertEquals(Set.of("auth/AuthProfile.java"), where, prodId + " appears only in the production profile (the id enum itself defines it)");
        }
        for (String testId : new String[] {"AUTHORITY_TEST_ANCHOR", "AUTHORITY_TEST_ENCRYPTION_KEY", "MIGRATION_TEST_RESEND", "MIGRATION_TEST_TWILIO"}) {
            assertEquals(Set.of("auth/AuthProfile.java"), filesContaining("SecretId." + testId), testId + " appears only in the QA profile");
        }
        assertEquals(Set.of("auth/AuthProfile.java"), filesContaining("SecretStores.production()"), "only the production profile asks for the production store");
        String qa = Files.readString(Path.of("src/test/java/byx/service/auth/AuthQaMain.java"));
        assertFalse(qa.contains("production(") || qa.contains("PRODUCTION"), "the QA main has no way to select the production profile");
        assertTrue(qa.contains("qa refuses to run on the real service home"), "and it refuses the real service home");
        String main = Files.readString(MAIN.resolve("byx/service/ServiceMain.java"));
        assertTrue(main.contains("AuthProfile.production(home)"), "the product composes the production profile");
        assertFalse(main.contains("AuthQaMain") || main.contains("AuthProfile.qa") || main.contains("FileSecondFactor"), "and never the QA one");
    }

    @Test
    void qaMainIsReferencedByNothingElse() throws IOException {
        assertEquals(Set.of(), filesContaining("AuthQaMain"));
        assertFalse(Files.exists(MAIN.resolve("byx/service/auth/AuthQaMain.java")), "QA entrypoint absent from production sources");
    }

    // ---- segundo fator, rede, flags ----------------------------------------------------------------------------------------------------

    @Test
    void secondFactorImplementationsAreExactlyTheRealOneTheNotConfiguredOneAndTheQaFileOne() throws IOException {
        Set<String> impl = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("implements SecondFactorProvider")) {
                    impl.add(MAIN.relativize(f).toString().replace("byx/service/", ""));
                }
            }
        }
        assertEquals(Set.of("auth/NotConfiguredSecondFactor.java", "auth/RealSecondFactor.java"), impl, "no development OTP provider in the product artifact");
        assertEquals(Set.of("auth/RealSecondFactor.java"), filesContaining("https://api.resend.com"), "the Resend endpoint is only in the real adapter");
        assertEquals(Set.of(), filesContaining("api.twilio.com"));
        assertEquals(Set.of("auth/HttpTransport.java", "auth/RealSecondFactor.java"), filesContaining("verify.twilio.com"), "the Twilio endpoint: the adapter and the transport allowlist");
        assertTrue(Files.readString(Path.of("src/test/java/byx/service/auth/AuthQaMain.java")).contains("FileSecondFactor"), "the file-based test provider lives only in the QA main");
    }

    @Test
    void forbiddenOperationNamesDoNotExistAnywhereInTheMainSources() throws IOException {
        for (String op : new String[] {"auth.execute", "auth.querySql", "auth.setRole", "auth.setMfa", "auth.impersonate", "auth.override", "auth.debugLogin", "auth.legacyLogin", "auth.fallback"}) {
            assertEquals(Set.of(), filesContaining("\"" + op + "\""), op + " defined as an operation");
        }
        assertFalse(AuthIpc.OPERATIONS.stream().anyMatch(o -> o.matches("auth\\.(execute|querySql|setRole|setMfa|impersonate|override|debugLogin)")));
        assertEquals(Set.of("auth.password", "auth.beginSecondFactor", "auth.verifySecondFactor", "auth.sessionStatus", "auth.logout", "auth.adminElevation", "auth.changePassword",
                "auth.sendSecondFactorSms", "auth.verifySecondFactorSms", "auth.enrollTrustedDevice", "auth.listTrustedDevices", "auth.revokeTrustedDevice"), AuthIpc.OPERATIONS);
    }

    @Test
    void noRuntimeFlagCanChangeTheAuthorityBehavior() throws IOException {
        for (String f : new String[] {"auth/AuthService.java", "auth/AuthIpc.java", "auth/AuthPolicy.java", "auth/AuthorityStore.java", "auth/AuthProfile.java", "auth/AuthComposition.java",
            "auth/AuthorityAdmin.java", "auth/RealSecondFactor.java"}) {
            String src = Files.readString(MAIN.resolve("byx/service/" + f));
            assertFalse(src.contains("System.getenv") || src.replace("System.getProperty(\"user.home\")", "").contains("System.getProperty") || src.contains("getBoolean"), f + " reads no environment or property");
        }
    }

    @Test
    void thereIsNoMigrationFreezeSwitchOverIpc() throws IOException {
        assertEquals(Set.of("auth/AuthorityAdmin.java"), filesContaining("setFreeze(").stream().filter(f -> !f.contains("migration/") && !f.endsWith("AuthQaMain.java")).collect(java.util.stream.Collectors.toSet()),
                "the freeze is only switched by the migrator (and the QA simulation), never by an IPC operation");
        assertFalse(Files.readString(MAIN.resolve("byx/service/auth/AuthIpc.java")).contains("setFreeze"));
    }

    @Test
    void theSnapshotUsesOnlyJcaAesGcmAndTheKeyOnlyLivesInTheVault() throws IOException {
        String codec = Files.readString(MAIN.resolve("byx/service/auth/AuthorityCodec.java"));
        assertTrue(codec.contains("AES/GCM/NoPadding"), "standard AEAD");
        for (String weak : new String[] {"/ECB", "/CBC", "DESede", "\"DES\"", "RC4", "Blowfish"}) {
            assertFalse(codec.contains(weak), "no weak or home-made cipher: " + weak);
        }
        assertEquals(Set.of("auth/AuthorityStore.java", "auth/EncryptionKeyVault.java", "auth/MemoryKeyVault.java", "auth/SecretStoreKeyVault.java", "migration/Migrator.java"), filesContaining("EncryptionKeyVault"),
                "the key is reached only through the typed vault");
        String store = Files.readString(MAIN.resolve("byx/service/auth/AuthorityStore.java"));
        assertFalse(store.contains("derivedKey(\"enc") || store.contains("mac(key, \"enc"), "the AEAD key is never derived from the MAC key");
    }

    @Test
    void sessionsAreNeverPersistedAndTheTokenIsNeverStoredRaw() throws IOException {
        for (String f : new String[] {"byx/service/auth/Session.java", "byx/service/auth/SessionStore.java"}) {
            String src = Files.readString(MAIN.resolve(f));
            assertFalse(src.contains("Files.") || src.contains("FileChannel") || src.contains("FileOutputStream"), f + " touches no file: sessions live only in memory");
        }
        assertTrue(Files.readString(MAIN.resolve("byx/service/auth/SessionStore.java")).contains("SHA-256"), "only a hash of the token is stored");
    }
}
