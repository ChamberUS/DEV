package byx.service.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contrato, validação, vazamento e comportamento real do armazenamento seguro (canário aleatório; nenhum segredo real). */
class SecretStoreTest {
    /** Backend de memória: só para provar as regras do decorador e do harness sem o Keychain. */
    static class MemoryBackend implements SecretStore {
        final Map<SecretId, byte[]> items = new HashMap<>();
        volatile SecretStatus failWith;

        private void maybeFail() throws SecretStoreException {
            if (failWith != null) {
                throw new SecretStoreException(failWith, -1);
            }
        }

        public SecretStatus status() {
            return failWith != null ? failWith : SecretStatus.SECURE_STORAGE_AVAILABLE;
        }

        public void write(SecretId id, SecretBytes v) throws SecretStoreException {
            maybeFail();
            items.put(id, v.bytes().clone());
        }

        public boolean update(SecretId id, SecretBytes v) throws SecretStoreException {
            maybeFail();
            if (!items.containsKey(id)) {
                return false;
            }
            items.put(id, v.bytes().clone());
            return true;
        }

        public Optional<SecretBytes> read(SecretId id) throws SecretStoreException {
            maybeFail();
            byte[] b = items.get(id);
            return b == null ? Optional.empty() : Optional.of(SecretBytes.copyOf(b));
        }

        public boolean delete(SecretId id) throws SecretStoreException {
            maybeFail();
            return items.remove(id) != null;
        }
    }

    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private MemoryBackend backend;
    private SecretStore store;

    @BeforeEach
    void up() {
        Log.redirect(logs::add);
        backend = new MemoryBackend();
        store = new ValidatedSecretStore(backend);
    }

    @AfterEach
    void down() {
        Log.redirect(null);
    }

    @Test
    void createReadUpdateDeleteRoundTrip() throws Exception {
        try (SecretBytes v1 = SecretBytes.random(32); SecretBytes v2 = SecretBytes.random(40)) {
            assertTrue(store.read(SecretId.TEST_CANARY).isEmpty());
            store.write(SecretId.TEST_CANARY, v1);
            try (SecretBytes got = store.read(SecretId.TEST_CANARY).orElseThrow()) {
                assertTrue(got.contentEquals(v1));
            }
            assertTrue(store.update(SecretId.TEST_CANARY, v2));
            try (SecretBytes got = store.read(SecretId.TEST_CANARY).orElseThrow()) {
                assertTrue(got.contentEquals(v2));
                assertFalse(got.contentEquals(v1));
            }
            assertTrue(store.delete(SecretId.TEST_CANARY));
            assertTrue(store.read(SecretId.TEST_CANARY).isEmpty());
            assertFalse(store.delete(SecretId.TEST_CANARY), "deleting twice reports absent");
            assertFalse(store.update(SecretId.TEST_CANARY, v1), "updating an absent item reports absent");
        }
    }

    @Test
    void onlyTheTestIdsAreUsableInThisPhase() throws Exception {
        for (SecretId id : SecretId.values()) {
            assertEquals(id != SecretId.TRUSTED_DEVICE_MASTER_KEY && id != SecretId.BINANCE_READONLY_CREDENTIAL, id.usable(), id.name());
            if (!id.usable()) {
                try (SecretBytes v = SecretBytes.random(16)) {
                    assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> store.write(id, v)).status(), id.name());
                    assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> store.read(id)).status());
                    assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> store.delete(id)).status());
                }
            }
        }
        assertTrue(backend.items.isEmpty(), "a non-usable id never reaches the backend");
        assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> store.read(null)).status());
    }

    @Test
    void theAvailabilityProbeNeverTargetsTheCanaryOrAnyRealSecret() throws Exception {
        assertNotEquals(SecretNamespace.service(SecretId.TEST_CANARY), SecretNamespace.PROBE_SERVICE, "a read must never delete the item it is about to read");
        String src = Files.readString(Path.of("src/main/java/byx/service/secrets/SecItemSecretStore.java"));
        int p = src.indexOf("private int probe()");
        String probe = src.substring(p, src.indexOf("private volatile boolean confirmedAvailable"));
        assertTrue(probe.contains("PROBE_SERVICE") && !probe.contains("SecretNamespace.service("), "the probe is built from the dedicated probe name only");
    }

    @Test
    void thereIsNoWayToNameAnArbitraryItem() throws Exception {
        // a API só aceita SecretId (enum fechado): não existe método com nome/serviço/conta livres
        for (var m : SecretStore.class.getMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertNotEquals(String.class, p, m.getName() + " takes no free-form string");
            }
        }
        assertEquals("invalid.byx-canary-test/test-canary", SecretNamespace.service(SecretId.TEST_CANARY));
        assertEquals("com.buynnex.byx.service/secrets/authority-encryption-key", SecretNamespace.service(SecretId.AUTHORITY_ENCRYPTION_KEY), "production items live under the final service id");
        assertNotEquals(SecretNamespace.service(SecretId.AUTHORITY_TEST_ENCRYPTION_KEY).replace("authority-test-", ""), SecretNamespace.service(SecretId.AUTHORITY_ENCRYPTION_KEY).replace("authority-", ""),
                "test and production namespaces never collide");
        assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> SecretNamespace.service(SecretId.BINANCE_READONLY_CREDENTIAL)).status(), "unused ids stay unusable");
    }

    @Test
    void emptyAndOversizedSecretsAreRejectedWithoutEchoingAnything() {
        for (byte[] bad : new byte[][] {new byte[0], new byte[SecretBytes.MAX_BYTES + 1], null}) {
            var e = assertThrows(IllegalArgumentException.class, () -> SecretBytes.copyOf(bad));
            assertEquals("secret size", e.getMessage(), "no size, no content");
        }
        byte[] edge = new byte[SecretBytes.MAX_BYTES];
        java.util.Arrays.fill(edge, (byte) 7);
        try (SecretBytes ok = SecretBytes.copyOf(edge)) {
            assertEquals(SecretBytes.MAX_BYTES, ok.length());
        }
        assertEquals(1, SecretBytes.copyOf(new byte[] {1}).length());
    }

    @Test
    void closeZeroesOurBufferAndTheValueNeverAppearsInToStringOrHash() {
        byte[] src = HexFormat.of().parseHex("deadbeefcafebabe0123456789abcdef");
        SecretBytes s = SecretBytes.copyOf(src);
        byte[] internal = s.bytes();
        assertArrayEquals(src, internal);
        s.close();
        assertTrue(java.util.stream.IntStream.range(0, internal.length).allMatch(i -> internal[i] == 0), "the buffer we control is wiped");
        assertEquals(0, s.length());
        assertEquals("SecretBytes[redacted]", s.toString());
        assertFalse(s.toString().toLowerCase().contains("dead"));
        assertEquals(System.identityHashCode(s), s.hashCode(), "hash is never derived from the content");
        assertThrows(IllegalStateException.class, s::bytes);
        s.close(); // idempotente
    }

    @Test
    void errorsCarryOnlyAFixedCodeNeverTheValueNorAStack() throws Exception {
        backend.failWith = SecretStatus.LOCKED;
        SecretBytes v = SecretBytes.random(24);
        String hex = HexFormat.of().formatHex(v.bytes());
        String b64 = Base64.getEncoder().encodeToString(v.bytes());
        for (var op : List.<ThrowingCall>of(() -> {
            store.write(SecretId.TEST_CANARY, v);
            return null;
        }, () -> store.update(SecretId.TEST_CANARY, v), () -> store.read(SecretId.TEST_CANARY), () -> store.delete(SecretId.TEST_CANARY))) {
            SecretStoreException e = assertThrows(SecretStoreException.class, op::call);
            assertEquals(SecretStatus.LOCKED, e.status());
            assertEquals("LOCKED", e.getMessage());
            assertEquals(0, e.getStackTrace().length, "no stack");
            assertEquals(null, e.getCause(), "no cause");
        }
        assertFalse(String.join("\n", logs).contains(hex) || String.join("\n", logs).contains(b64), "the log has only fixed codes");
        assertTrue(logs.stream().anyMatch(l -> l.contains("secret_store_write LOCKED")), "but it does log the fixed event: " + logs);
        v.close();
    }

    @Test
    void anUnexpectedBackendFailureBecomesAFixedErrorCode() {
        SecretStore broken = new MemoryBackend() {
            @Override
            public void write(SecretId id, SecretBytes v) {
                throw new IllegalStateException("backend exploded with " + HexFormat.of().formatHex(v.bytes()));
            }
        };
        try (SecretBytes v = SecretBytes.random(8)) {
            String hex = HexFormat.of().formatHex(v.bytes());
            var e = assertThrows(SecretStoreException.class, () -> new ValidatedSecretStore(broken).write(SecretId.TEST_CANARY, v));
            assertEquals(SecretStatus.ERROR, e.status());
            assertFalse(e.toString().contains(hex), "the backend's message (which carried the value) is dropped");
            assertFalse(String.join("\n", logs).contains(hex));
        }
    }

    @Test
    void statusMappingCoversTheKeychainCodesWithoutInventingSuccess() {
        assertEquals(SecretStatus.NOT_CONFIGURED, SecItemSecretStore.map(-34018), "errSecMissingEntitlement");
        assertEquals(SecretStatus.NOT_CONFIGURED, SecItemSecretStore.map(-25291), "errSecNotAvailable");
        assertEquals(SecretStatus.LOCKED, SecItemSecretStore.map(-25308), "errSecInteractionNotAllowed");
        assertEquals(SecretStatus.LOCKED, SecItemSecretStore.map(-25315), "errSecInteractionRequired");
        assertEquals(SecretStatus.DENIED, SecItemSecretStore.map(-25293), "errSecAuthFailed");
        assertEquals(SecretStatus.DENIED, SecItemSecretStore.map(-25243), "errSecNoAccessForItem");
        assertEquals(SecretStatus.DENIED, SecItemSecretStore.map(-128), "errSecUserCanceled");
        assertEquals(SecretStatus.ERROR, SecItemSecretStore.map(-99999));
        assertEquals(SecretStatus.ERROR, SecItemSecretStore.map(-25300), "'not found' is handled by the caller, never as availability");
    }

    @Test
    void theQueryIsDataProtectionOnlyNeverSynchronizableAndTheMostRestrictiveAccessibility() throws Exception {
        Map<String, String> q = SecItemSecretStore.describe(SecretId.TEST_CANARY);
        assertEquals("true", q.get("dataProtectionKeychain"));
        assertEquals("false", q.get("synchronizable"), "no iCloud sync for operational secrets");
        assertEquals("WhenUnlockedThisDeviceOnly", q.get("accessible"));
        assertFalse(q.values().stream().anyMatch(v -> v.contains("Always")), "never an 'Always' class");
        assertEquals("invalid.byx-canary-test/test-canary", q.get("service"));
        // o código usa os atributos acima de verdade (não só o descritor)
        String src = Files.readString(Path.of("src/main/java/byx/service/secrets/SecItemSecretStore.java"));
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
        assertTrue(code.contains("kSecUseDataProtectionKeychain") && code.contains("kCFBooleanFalse") && code.contains("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"));
        assertFalse(code.contains("Always") || code.contains("kCFBooleanTrue, kFalse") && false, "no Always");
        assertFalse(code.contains("SecKeychain") || code.contains("SecAccess") || code.contains("SecTrustedApplication"), "no legacy keychain API");
    }

    @Test
    void noProductionCodeUsesTheLegacyKeychainOrPlaintextStorage() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
                for (String banned : List.of("SecKeychain", "SecAccess", "SecTrustedApplication", "kSecAttrSynchronizable, kCFBooleanTrue")) {
                    // ÚNICA exceção: o caminho de MIGRAÇÃO lê os itens legados fixos do painel antigo (nada é criado/alterado/apagado fora do namespace de teste)
                    if (banned.equals("SecKeychain") && f.toString().endsWith("migration/LegacyKeychain.java")) {
                        continue;
                    }
                    assertFalse(code.contains(banned), f + " must not use " + banned);
                }
            }
        }
        String legacy = Files.readString(Path.of("src/main/java/byx/service/migration/LegacyKeychain.java")).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
        assertFalse(legacy.contains("SecKeychainItemModifyAttributesAndData") || legacy.contains("SecKeychainItemCreateFromContent"), "the legacy path never modifies an existing item");
        assertTrue(legacy.contains("requireTest()") && legacy.indexOf("SecKeychainAddGenericPassword") > legacy.indexOf("void addForTest") && legacy.indexOf("SecKeychainItemDelete") > legacy.indexOf("void deleteForTest"),
                "creating or deleting legacy items exists only in the test-only methods, which refuse the real names");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!f.toString().contains("/migration/") && Files.readString(f).contains("LegacyKeychain")) {
                    throw new AssertionError(f + " reaches the legacy keychain outside the migration package");
                }
            }
        }
        String secrets = String.join("\n", Files.readAllLines(Path.of("src/main/java/byx/service/secrets/SecretStores.java")));
        assertFalse(secrets.contains("Files.write") || secrets.contains("FileOutputStream"), "no file fallback");
    }

    /** Backend REAL sem entitlement (JVM de teste, sem perfil): falha FECHADA com NOT_CONFIGURED e não cria nada, em lugar nenhum. */
    @Test
    void theRealBackendWithoutTheEntitlementFailsClosedAndCreatesNothing() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Mac"));
        SecretStore real = SecretStores.system();
        SecretStatus st = real.status();
        org.junit.jupiter.api.Assumptions.assumeTrue(st == SecretStatus.NOT_CONFIGURED, "this JVM unexpectedly has the entitlement: " + st);
        try (SecretBytes v = SecretBytes.random(32)) {
            assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> real.write(SecretId.TEST_CANARY, v)).status());
        }
        assertEquals(SecretStatus.NOT_CONFIGURED, assertThrows(SecretStoreException.class, () -> real.read(SecretId.TEST_CANARY)).status());
        // nenhum item de teste apareceu no keychain de login (consulta só de atributos, sem -g/-w: nenhum valor é lido)
        Process p = new ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", "invalid.byx-canary-test/test-canary").redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        assertEquals(44, p.waitFor(), "security(1) exit 44 = item not found: nothing was created. " + new String(out, StandardCharsets.UTF_8).lines().findFirst().orElse(""));
    }

    @Test
    void theHarnessRunsTheFullCanaryCycleOnAnyBackendAndNeverPrintsTheValue() throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = SecretCanaryHarness.run(store);
        } finally {
            System.setOut(old);
        }
        String out = cap.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        for (String step : List.of("write", "read", "update", "readAfterUpdate", "cleanup.delete", "cleanup.absentCheck")) {
            assertTrue(out.contains("canary." + step + "=OK"), step + " in " + out);
        }
        assertTrue(out.contains("canary.result=OK") && !out.contains("leftover"));
        assertTrue(backend.items.isEmpty(), "cleanup left nothing");
        assertFalse(out.matches("(?s).*[0-9a-f]{32}.*"), "no hex-looking secret in the output");
        // falha do backend: bloqueia com o estado, limpa e relata sem valor
        backend.failWith = SecretStatus.NOT_CONFIGURED;
        cap.reset();
        System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
        try {
            code = SecretCanaryHarness.run(store);
        } finally {
            System.setOut(old);
        }
        out = cap.toString(StandardCharsets.UTF_8);
        assertEquals(2, code);
        assertTrue(out.contains("canary.write=NOT_CONFIGURED") && out.contains("canary.result=BLOCKED"), out);
    }

    @Test
    void thereIsNoIpcOperationForSecretsAndTheProtocolStaysClosed() {
        for (String op : java.util.stream.Stream.concat(byx.service.Protocol.OPERATIONS.stream(), byx.service.Protocol.MARKET_OPERATIONS.stream()).toList()) {
            assertFalse(op.toLowerCase().matches(".*(secret|credential|keychain|dump|getsecret|readsecret).*"), op);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        Object call() throws Exception;
    }
}
