package byx.service.signer;

import byx.service.auth.*;
import byx.service.wallet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WalletEndpointTest {
    @TempDir Path root;
    AuthorityStore store;
    WalletLifecycleTest.FakeCustody custody;
    WalletLifecycle lifecycle;
    WalletEndpoint endpoint;
    WalletIpc ipc;
    AtomicReference<WalletEndpoint.Principal> principal = new AtomicReference<>();
    ObjectMapper json = new ObjectMapper();
    String createKey;
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        store = AuthorityStore.openWalletQa(root.resolve("catalog"), new MemoryAnchor(), new MemoryKeyVault());
        store.initializeWalletQa(WalletCatalog.empty(WalletLifecycle.randomId()));
        var admin = new AuthorityAdmin(store, new PasswordVerifier(new PasswordVerifier.Params(1024,1,1)), Clock.systemUTC());
        var owner = admin.createAccount("panel_qa", "synthetic-test-password".toCharArray(), Role.ADMIN);
        principal.set(new WalletEndpoint.Principal(new WalletLifecycle.Session(owner.id(), owner.credentialVersion(), true), true, true));
        custody = new WalletLifecycleTest.FakeCustody();
        var ref = new AtomicReference<WalletEndpoint>();
        lifecycle = new WalletLifecycle(store, custody, () -> ref.get().currentSession(), Clock.systemUTC());
        endpoint = new WalletEndpoint(lifecycle, (p,t) -> principal.get(), new ThreadLocal<>(), null); ref.set(endpoint);
        lifecycle.reconcile(); ipc = new WalletIpc(endpoint); createKey = WalletLifecycle.randomId();
    }
    ObjectNode request(String action) {
        ObjectNode r = json.createObjectNode().put("v",1).put("id","qa").put("op", action).put("session","s".repeat(43)).put("walletSchema",1);
        if (action.equals("wallet.createSynthetic")) r.put("idempotencyKey",createKey).put("lossAcknowledged","true");
        return r;
    }
    ObjectNode call(ObjectNode r) { ObjectNode response=json.createObjectNode(); ipc.handle(7,r.path("op").asText(),r,response);return response; }
    WalletView view() { return json.convertValue(call(request("wallet.list")).path("result"), WalletView.class); }
    @Test void publicSerializationHasNoInternalIdentifiers() {
        assertTrue(call(request("wallet.createSynthetic")).path("ok").asBoolean());
        String publicJson=call(request("wallet.list")).toString();
        for (String forbidden : new String[]{"signingKeyRef", "scalar", "seed", "mnemonic", "keychain", "receipt", "requestDigest", "ownerAccountId"}) assertFalse(publicJson.toLowerCase().contains(forbidden.toLowerCase()));
        assertEquals("READY",view().state()); assertTrue(view().allowedActions().canDelete());
    }
    @Test void duplicatesConvergeAndChangedKeyCannotCreateAnotherWallet() {
        assertTrue(call(request("wallet.createSynthetic")).path("ok").asBoolean());
        assertTrue(call(request("wallet.createSynthetic")).path("ok").asBoolean());
        assertEquals(1,custody.generated);
        assertEquals("ACTION_DENIED",call(request("wallet.createSynthetic").put("idempotencyKey",WalletLifecycle.randomId())).path("error").path("code").asText());
    }
    @Test void userReadCannotLeakOrFailOnAnotherOwnersWallet() throws Exception {
        call(request("wallet.createSynthetic"));
        var user = new AuthorityAdmin(store, new PasswordVerifier(new PasswordVerifier.Params(1024,1,1)), Clock.systemUTC())
                .createAccount("other_user", "synthetic-test-password".toCharArray(), Role.USER);
        principal.set(new WalletEndpoint.Principal(new WalletLifecycle.Session(user.id(),user.credentialVersion(),false),false,false));
        WalletView own = view();assertEquals("NO_WALLET",own.state());assertTrue(own.wallets().isEmpty());assertTrue(own.operations().isEmpty());
        assertFalse(own.allowedActions().canCreate());assertEquals(1,custody.generated);
    }
    @Test void userAndMissingMfaCannotMutateEvenWithBuggyUi() {
        var p=principal.get(); principal.set(new WalletEndpoint.Principal(p.session(),false,false));
        assertFalse(view().allowedActions().canCreate());
        assertEquals("FORBIDDEN",call(request("wallet.createSynthetic")).path("error").path("code").asText());
        principal.set(new WalletEndpoint.Principal(new WalletLifecycle.Session(p.session().ownerAccountId(),1,false),true,true));
        assertEquals("FORBIDDEN",call(request("wallet.createSynthetic")).path("error").path("code").asText());
        assertEquals(0,custody.generated);
    }
    @Test void mismatchedSchemaUnknownFieldsAndBroadcastDenied() {
        assertEquals("SERVICE_VERSION_INCOMPATIBLE",call(request("wallet.list").put("walletSchema",2)).path("error").path("code").asText());
        assertEquals("BAD_REQUEST",call(request("wallet.list").put("signingKeyRef","injected")).path("error").path("code").asText());
        assertEquals("QA_BARRIER",call(request("wallet.broadcast")).path("error").path("code").asText());
    }
    @Test void orphanMetadataDisablesSignAndDeleteAndCreate() {
        call(request("wallet.createSynthetic")); custody.scalars.clear(); lifecycle.reconcile();
        assertEquals("NEEDS_ATTENTION",view().state());
        assertFalse(view().allowedActions().canDelete());assertFalse(view().allowedActions().canCreate());assertFalse(view().allowedActions().canSyntheticSign());
    }
    @Test void deletedTombstoneAndDuplicateDeleteArePublic() {
        call(request("wallet.createSynthetic")); var w=view().wallets().getFirst();
        var r=request("wallet.deleteSynthetic").put("walletId",w.walletId()).put("expectedVersion",Long.toString(w.version()))
                .put("idempotencyKey",WalletLifecycle.randomId()).put("lossAcknowledged","true");
        assertTrue(call(r).path("ok").asBoolean()); assertTrue(call(r).path("ok").asBoolean());
        assertEquals("DELETED",view().state());assertEquals("DELETED",view().wallets().getFirst().healthState());
        lifecycle.reconcile();assertEquals("DELETED",view().wallets().getFirst().healthState());
    }
    @Test void authorizationRevokedDuringNativeDispatchCannotDeliverSuccess() throws Exception {
        custody.entered=new java.util.concurrent.CountDownLatch(1);custody.release=new java.util.concurrent.CountDownLatch(1);
        var original=principal.get();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->call(request("wallet.createSynthetic")));
            assertTrue(custody.entered.await(3,java.util.concurrent.TimeUnit.SECONDS));principal.set(null);custody.release.countDown();
            assertEquals("UNAUTHORIZED",result.get(5,java.util.concurrent.TimeUnit.SECONDS).path("error").path("code").asText());
            principal.set(original);assertEquals("READY",view().state());assertEquals(1,custody.generated);
        } finally {custody.release.countDown();executor.shutdownNow();}
    }
    @Test void observationRefreshNeverMutatesOrRepairsOrphanState() {
        call(request("wallet.createSynthetic"));custody.scalars.clear();
        assertEquals("NEEDS_ATTENTION",view().state());assertFalse(view().allowedActions().canDelete());
        assertEquals(1,custody.generated);assertTrue(custody.scalars.isEmpty());
    }
    @Test void disabledApiRemainsInertAndZeroPeerDenied() {
        ipc=WalletIpc.disabled();assertEquals("DISABLED",view().capability());
        assertEquals("FEATURE_DISABLED",call(request("wallet.createSynthetic")).path("error").path("code").asText());
        var out=json.createObjectNode();ipc.handle(0,"wallet.list",request("wallet.list"),out);assertFalse(out.path("ok").asBoolean());
    }
    @Test void interruptedCreatePublishesUnknownAndDisablesMutations() {
        custody.crash="afterScalar";
        assertFalse(call(request("wallet.createSynthetic")).path("ok").asBoolean());
        assertEquals("UNKNOWN_RESULT",view().state());assertFalse(view().allowedActions().canCreate());
    }
}
