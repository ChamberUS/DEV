package byx.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStatus;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import byx.service.secrets.ValidatedSecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O segredo vive só dentro do serviço: nenhuma resposta IPC, log ou diagnóstico o contém, e o servidor de IPC nem referencia o cofre. */
class ServiceSecretIsolationTest {
    private static final byte[] RAW = new byte[32];

    static {
        new java.security.SecureRandom().nextBytes(RAW);
    }

    @Test
    void aCanaryHeldInTheServiceJvmNeverAppearsInAnyIpcResponseOrLog() throws Exception {
        Path home = Files.createTempDirectory(Path.of("/tmp"), "si");
        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Log.redirect(logs::add);
        Map<SecretId, byte[]> mem = new HashMap<>();
        SecretStore store = new ValidatedSecretStore(new SecretStore() {
            public SecretStatus status() {
                return SecretStatus.SECURE_STORAGE_AVAILABLE;
            }

            public void write(SecretId id, SecretBytes v) {
                mem.put(id, RAW.clone());
            }

            public boolean update(SecretId id, SecretBytes v) {
                return false;
            }

            public Optional<SecretBytes> read(SecretId id) {
                return Optional.ofNullable(mem.get(id)).map(SecretBytes::copyOf);
            }

            public boolean delete(SecretId id) {
                return mem.remove(id) != null;
            }
        });
        try (ServiceInstance service = ServiceInstance.start(home, new ServiceInstance.Limits(4, 800, 800, 2_000, 800)); SecretBytes canary = SecretBytes.copyOf(RAW)) {
            store.write(SecretId.TEST_CANARY, canary);
            String hex = HexFormat.of().formatHex(RAW);
            String b64 = Base64.getEncoder().encodeToString(RAW);
            StringBuilder all = new StringBuilder();
            try (TestClient c = new TestClient(service.runtimeDir().socket())) {
                c.handshake(TestClient.readToken(home), true);
                for (String op : List.of("health", "version", "capabilities")) {
                    all.append(c.call(op));
                }
                for (String op : List.of("secret.get", "secrets.dump", "readSecret", "getSecret", "listSecretValues", "keychain.read")) {
                    all.append(c.call(op)); // não existem: unsupported_operation
                }
            }
            assertFalse(all.toString().contains(hex) || all.toString().contains(b64), "no IPC response carries the secret");
            assertTrue(all.toString().contains("unsupported_operation"));
            assertFalse(String.join("\n", logs).contains(hex) || String.join("\n", logs).contains(b64));
            store.delete(SecretId.TEST_CANARY);
        } finally {
            Log.redirect(null);
            try (var w = Files.walk(home)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void theIpcServerDoesNotReferenceTheSecretStoreAtAll() throws Exception {
        for (String f : List.of("ServiceInstance.java", "Operations.java", "Protocol.java", "MarketSubscriber.java")) {
            Path p = f.equals("MarketSubscriber.java") ? Path.of("src/main/java/byx/service/market", f) : Path.of("src/main/java/byx/service", f);
            String src = Files.readString(p);
            assertFalse(src.contains("byx.service.secrets") || src.contains("SecretStore") || src.contains("SecretId"), f + " has no path to the secrets");
        }
    }
}
