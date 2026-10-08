package byx.service.signer;

import byx.service.ServiceInstance;
import byx.service.auth.*;
import byx.service.identity.*;
import byx.service.secrets.*;
import byx.service.wallet.WalletIpc;
import byx.service.tx.CustodyQaFixture;
import byx.service.tx.TxPorts.TxSignRequest;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/** Test-jar-only authenticated Panel composition, with no production authority or transport. */
final class WalletPanelQaService {
    static void run(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("QA_ROOT_REQUIRED");
        Path root = Path.of(args[1]);
        if (!root.isAbsolute() || !root.toRealPath().equals(root) || !root.startsWith(Path.of("/private/tmp"))
                || !root.getFileName().toString().startsWith("byx-wallet-qa-")
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IllegalArgumentException("QA_ROOT_UNTRUSTED");
        if (args[0].equals("wallet-panel-default-runtime")) {
            // Exactly the disabled production composition: no custody client, authority, signer, catalog or secrets.
            var service = ServiceInstance.start(root.resolve("runtime"), ServiceInstance.Limits.defaults(), null,
                    IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID), null, PeerKeys.kernel());
            Runtime.getRuntime().addShutdownHook(new Thread(service::close));
            System.out.println("PANEL_SERVICE_READY"); System.out.flush(); service.awaitStop(); return;
        }
        var client = new CustodyClient(); client.authority();
        var secrets = SecretStores.test();
        var store = AuthorityStore.openWalletQa(root.resolve("catalog.bin"),
                new SecretStoreAnchor(secrets, SecretId.WALLET_LIFECYCLE_TEST_ANCHOR),
                new SecretStoreKeyVault(secrets, SecretId.WALLET_LIFECYCLE_TEST_ENCRYPTION_KEY));
        if (store.status() != AuthorityStore.Status.TRUSTED) {
            // Redacted fail-closed health: there is no trusted owner projection and no lifecycle composition.
            var unavailableAuth = new AuthService(store, new AuthorityAdmin(store, new PasswordVerifier(), Clock.systemUTC()),
                    new PasswordVerifier(), new AuthRateLimiter(null, new byte[32], new byte[32], Clock.systemUTC()),
                    new NotConfiguredSecondFactor(), AuthPolicy.standard(), new AuthAudit(Clock.systemUTC()), Clock.systemUTC());
            var chain = byx.service.chain.ChainConnector.notConfigured();
            var service = ServiceInstance.start(root.resolve("runtime"), ServiceInstance.Limits.defaults(), null,
                    IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID), new AuthIpc(unavailableAuth), PeerKeys.kernel(), chain,
                    byx.service.tx.TxProduction.disabled(unavailableAuth, ServiceInstance.txChain(chain)),
                    new WalletIpc((peer, token, action, request) -> { throw new WalletLifecycle.Failure("CORRUPT_CATALOG"); }));
            Runtime.getRuntime().addShutdownHook(new Thread(service::close));
            System.out.println("PANEL_SERVICE_READY"); System.out.flush(); service.awaitStop(); return;
        }
        if (!args[0].equals("wallet-panel-service")) {
            mutateFixture(args[0], root, store, client); return;
        }
        var clock = Clock.systemUTC();
        var pw = new PasswordVerifier(new PasswordVerifier.Params(1024, 1, 1));
        var admin = new AuthorityAdmin(store, pw, clock);
        if (store.current().accounts().size() == 1) admin.createAccount("panel_qa_user", "synthetic-panel-test-password".toCharArray(), Role.USER);
        SecondFactorProvider second = new SecondFactorProvider() {
            public boolean configured() { return true; }
            public void deliver(String account, char[] code) throws DeliveryException {
                try {
                    Path file = root.resolve("otp-" + account);
                    Files.deleteIfExists(file);
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.writeString(file, new String(code));
                } catch (java.io.IOException e) { throw new DeliveryException(); }
            }
        };
        var auth = new AuthService(store, admin, pw,
                new AuthRateLimiter(null, new byte[32], new byte[32], clock), second, AuthPolicy.standard(), new AuthAudit(clock), clock);
        var endpointRef = new AtomicReference<WalletEndpoint>();
        var confirmed = new ThreadLocal<TxSignRequest>();
        var nativePort = new WalletLifecycleQaCustody(client, "");
        var observedPort = new WalletLifecycle.Custody() {
            public void acquire() throws WalletLifecycle.CustodyFailure {
                if (Files.exists(root.resolve("qa-quiescence-unproven"))) throw new WalletLifecycle.CustodyFailure("CUSTODY_QUIESCENCE_UNPROVEN");
                nativePort.acquire();
            }
            public void verifyAvailability() throws WalletLifecycle.CustodyFailure { nativePort.verifyAvailability(); }
            public WalletLifecycle.Reply call(String action, WalletLifecycle.Request request, TxSignRequest sign) throws WalletLifecycle.CustodyFailure {
                if (action.equals("inventoryPage") && Files.exists(root.resolve("qa-inventory-incomplete")))
                    return new WalletLifecycle.Reply("INVENTORY_INCOMPLETE", 0, null, null, 0, null, null);
                return nativePort.call(action, request, sign);
            }
        };
        var lifecycle = new WalletLifecycle(store, observedPort,
                () -> endpointRef.get().currentSession(), r -> r == confirmed.get() && !r.quote().expiredAt(clock.millis()), clock);
        var current = new ThreadLocal<WalletEndpoint.Principal>();
        var endpoint = new WalletEndpoint(lifecycle, (peer, token) -> auth.resolveSession(peer, token).map(s -> {
            try {
                var a = store.current().byId(s.accountId()).orElseThrow();
                return new WalletEndpoint.Principal(new WalletLifecycle.Session(a.id(), a.credentialVersion(), s.recentMfa()), s.admin(), s.elevated());
            } catch (AuthorityException e) { return null; }
        }).orElse(null), current, (wallet, key) -> {
            var captured = CustodyQaFixture.confirmed(wallet.signingKeyRef(), wallet.address(), CustodyQaFixture.anotherAddress(), "1", "panel synthetic QA");
            if (captured.broadcasts() != 0) throw new AssertionError("QA_BROADCAST_FORBIDDEN");
            confirmed.set(captured.request());
            try { return lifecycle.sign(wallet.walletId(), wallet.version(), key, captured.request(), true).txHash(); }
            finally { confirmed.remove(); }
        });
        endpointRef.set(endpoint);
        try { lifecycle.reconcile(); } catch (WalletLifecycle.Failure e) { System.out.println("PANEL_SERVICE_BLOCKED=" + e.code); }
        // Expose public fail-closed health after reconciliation, including its explicit failure.
        lifecycle.fault(point -> {
            if (point.equals("afterActive") && Files.exists(root.resolve("qa-lost-create-response"))) {
                System.out.println("PANEL_CREATE_COMMITTED"); System.out.flush();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("QA_INTERRUPTED"); }
            }
        });
        var chain = byx.service.chain.ChainConnector.notConfigured();
        var service = ServiceInstance.start(root.resolve("runtime"), ServiceInstance.Limits.defaults(), null,
                IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID), new AuthIpc(auth), PeerKeys.kernel(), chain,
                byx.service.tx.TxProduction.disabled(auth, ServiceInstance.txChain(chain)), new WalletIpc(endpoint));
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        System.out.println("PANEL_SERVICE_READY"); System.out.flush(); service.awaitStop();
    }
    private static void mutateFixture(String action, Path root, AuthorityStore store, CustodyClient client) throws Exception {
        if (action.equals("wallet-panel-corrupt-metadata")) {
            Path snapshot = root.resolve("catalog.bin");
            Files.copy(snapshot, root.resolve("catalog-original.bin"));
            byte[] bytes = Files.readAllBytes(snapshot); bytes[bytes.length - 1] ^= 1; Files.write(snapshot, bytes);
            System.out.println("PANEL_ANOMALY_PREPARED"); return;
        }
        var catalog = store.current().walletCatalog();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        WalletCatalog.Binding binding;
        long version;
        if (action.equals("wallet-panel-orphan-key")) {
            var owner = store.current().accounts().stream().filter(a -> a.username().equals("lifecycle_qa")).findFirst().orElseThrow();
            binding = new WalletCatalog.Binding(catalog.catalogId(), owner.id(), WalletLifecycle.randomId(), WalletLifecycle.randomId(),
                    WalletLifecycle.randomId(), "SYNTHETIC_RANDOM_SCALAR_V1", "cosmos-secp256k1");
            version = 1;
            Path file = root.resolve("orphan-binding.json");
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, json.writeValueAsString(binding));
        } else if (action.equals("wallet-panel-clean-orphan")) {
            binding = json.readValue(Files.readString(root.resolve("orphan-binding.json")), WalletCatalog.Binding.class); version = 1;
        } else {
            var wallet = catalog.wallets().stream().filter(w -> w.durableState() == WalletCatalog.State.ACTIVE).findFirst().orElseThrow();
            binding = wallet.binding(catalog.catalogId()); version = wallet.version();
        }
        String op = switch(action) {
            case "wallet-panel-orphan-metadata", "wallet-panel-clean-orphan" -> "purgeQaBound";
            case "wallet-panel-key-mismatch" -> "revokeDeleteBound";
            case "wallet-panel-orphan-key" -> "provisionBound";
            default -> throw new IllegalArgumentException("QA_MODE_UNSUPPORTED");
        };
        var reply = client.lifecycle(op, new CustodyClient.LifecycleRequest(binding, op.equals("provisionBound") ? binding.creationOperationId() : WalletLifecycle.randomId(),
                WalletLifecycle.sha("PANEL_NEGATIVE_FIXTURE"), version, "", "", 0, ""), null);
        if (!java.util.Set.of("LIVE", "REVOKED", "DELETED").contains(reply.status())) throw new IllegalStateException("QA_FIXTURE_FAILED");
        System.out.println("PANEL_ANOMALY_PREPARED");
    }

}
