package byx.service.signer;

import byx.service.auth.*;
import byx.service.tx.CustodyQaFixture;
import byx.service.tx.TxPorts.TxSignRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static byx.service.signer.WalletCatalog.*;

class WalletLifecycleTest {
    @TempDir Path temporary;
    private Path file;
    private MemoryAnchor anchor;
    private MemoryKeyVault vault;
    private AuthorityStore store;
    private String owner;
    private AtomicReference<WalletLifecycle.Session> session;
    private FakeCustody custody;
    private WalletLifecycle lifecycle;
    private static final ObjectMapper JSON=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        Path root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));file=root.resolve("wallet.bin");
        anchor=new MemoryAnchor();vault=new MemoryKeyVault();store=AuthorityStore.openWalletQa(file,anchor,vault);
        store.initializeWalletQa(WalletCatalog.empty(WalletLifecycle.randomId()));
        var admin=new AuthorityAdmin(store,new PasswordVerifier(new PasswordVerifier.Params(1024,1,1)),Clock.systemUTC());
        owner=admin.createAccount("synthetic","synthetic-test-password".toCharArray(),Role.ADMIN).id();
        session=new AtomicReference<>(new WalletLifecycle.Session(owner,1,true));custody=new FakeCustody();restart();
    }
    private void restart() {
        store=AuthorityStore.openWalletQa(file,anchor,vault);
        lifecycle=new WalletLifecycle(store,custody,session::get,r->true,Clock.systemUTC());
        lifecycle.reconcile();
    }
    private static String key() { return WalletLifecycle.randomId(); }
    private void expect(String code,Runnable action) { assertEquals(code,assertThrows(WalletLifecycle.Failure.class,action::run).code); }

    @Test void createPersistRestartDeleteAndFreshIdentity() {
        String idem=key();Wallet w=lifecycle.create(idem,"test",true);
        assertEquals(State.ACTIVE,w.durableState());assertNotEquals(w.walletId(),w.signingKeyRef());
        assertEquals(w,lifecycle.create(idem,"test",true));assertEquals(1,custody.generated);
        restart();assertEquals("HEALTHY",lifecycle.health(w.walletId()));
        Wallet gone=lifecycle.delete(w.walletId(),w.version(),key(),true);assertEquals(State.DELETED,gone.durableState());
        restart();assertEquals("DELETED",lifecycle.health(w.walletId()));assertFalse(custody.scalars.contains(w.signingKeyRef()));
        Wallet fresh=lifecycle.create(key(),null,true);assertNotEquals(w.walletId(),fresh.walletId());assertNotEquals(w.signingKeyRef(),fresh.signingKeyRef());
        assertEquals(2,lifecycle.query().size());assertEquals(2,custody.generated);
    }
    @Test void ownerSlotAndIdempotencyRejectConflicts() {
        String idem=key();lifecycle.create(idem,"label",true);
        expect("CONFLICT",()->lifecycle.create(idem,"changed",true));expect("CONFLICT",()->lifecycle.create(key(),"second",true));
        expect("LOSS_ACKNOWLEDGEMENT_REQUIRED",()->lifecycle.create(key(),null,false));
        session.set(null);expect("UNAUTHORIZED",lifecycle::query);
        session.set(new WalletLifecycle.Session(owner,2,true));expect("FORBIDDEN",lifecycle::query);
    }
    @Test void serviceCreateCrashBoundariesConvergeThroughExplicitSameOperation() {
        for(String point:List.of("beforeCreating","afterCreating","beforeActive","afterActive")) {
            String idem=key();lifecycle.fault(p->{if(p.equals(point)) throw new InjectedCrash();});
            assertThrows(InjectedCrash.class,()->lifecycle.create(idem,null,true));lifecycle.fault(p->{});restart();
            Wallet w=lifecycle.create(idem,null,true);assertEquals(State.ACTIVE,w.durableState());
            assertEquals(1,lifecycle.query().stream().filter(x->x.durableState()!=State.DELETED).count());
            lifecycle.delete(w.walletId(),w.version(),key(),true);restart();
        }
        assertEquals(4,custody.generated);
    }
    @Test void helperCreateCrashBoundariesNeverRegenerateExistingIdentity() {
        for(String point:List.of("afterPreparing","beforeScalar","afterScalar","beforeLive","afterLive")) {
            String idem=key();custody.crash=point;
            expect("TIMEOUT_UNKNOWN_RESULT",()->lifecycle.create(idem,null,true));custody.crash=null;restart();
            Wallet w=lifecycle.create(idem,null,true);assertEquals(State.ACTIVE,w.durableState());
            lifecycle.delete(w.walletId(),w.version(),key(),true);restart();
        }
        assertEquals(5,custody.generated);
    }
    @Test void deleteCrashBoundariesCannotBecomeActiveAfterCommittedIntent() {
        for(String point:List.of("beforeDeleting","afterDeleting","beforeRevoked","afterRevoked","beforeScalarDelete","afterScalarDelete","beforeAbsence","beforeDeleted","afterDeleted")) {
            Wallet w=lifecycle.create(key(),null,true);String idem=key();
            if(point.startsWith("beforeDelet") || point.equals("afterDeleting") || point.equals("afterDeleted")) lifecycle.fault(p->{if(p.equals(point)) throw new InjectedCrash();});
            else custody.crash=point;
            assertThrows(RuntimeException.class,()->lifecycle.delete(w.walletId(),w.version(),idem,true));
            lifecycle.fault(p->{});custody.crash=null;restart();
            if(point.equals("beforeDeleting")) lifecycle.delete(w.walletId(),w.version(),idem,true);
            assertEquals(State.DELETED,lifecycle.query().stream().filter(x->x.walletId().equals(w.walletId())).findFirst().orElseThrow().durableState());
            assertFalse(custody.scalars.contains(w.signingKeyRef()));
        }
    }
    @Test void missingKeyNeverRegeneratesAndQuarantineSurvivesRestart() {
        Wallet w=lifecycle.create(key(),null,true);custody.scalars.remove(w.signingKeyRef());restart();
        assertEquals("ORPHAN_METADATA",lifecycle.health(w.walletId()));int generated=custody.generated;
        restart();assertEquals("ORPHAN_METADATA",lifecycle.health(w.walletId()));assertEquals(generated,custody.generated);
    }
    @Test void orphanIsNotAdoptedOrDeletedAndIncompleteInventoryBlocks() {
        Wallet w=lifecycle.create(key(),null,true);
        custody.orphan=true;restart();expect("ORPHAN_KEY",()->lifecycle.create(key(),null,true));
        assertTrue(custody.scalars.contains(w.signingKeyRef()));
        custody.incomplete=true;expect("INVENTORY_INCOMPLETE",lifecycle::reconcile);expect("RECONCILIATION_REQUIRED",()->lifecycle.create(key(),null,true));
    }
    @Test void revokedReceiptWinsOverActiveMetadata() {
        Wallet w=lifecycle.create(key(),null,true);custody.revoke(w.signingKeyRef());restart();
        assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));
        var request=CustodyQaFixture.confirmed(w.signingKeyRef(),w.address(),CustodyQaFixture.anotherAddress(),"1500000","").request();
        expect("CONFLICT",()->lifecycle.sign(w.walletId(),w.version(),key(),request,true));assertEquals(0,custody.signs);
        long currentVersion=lifecycle.query().getFirst().version();
        expect("WALLET_NOT_ACTIVE",()->lifecycle.sign(w.walletId(),currentVersion,key(),request,true));assertEquals(0,custody.signs);
    }
    @Test void reservedUnknownSignSurvivesRestartAndNeverRetries() {
        Wallet w=lifecycle.create(key(),null,true);
        var fixture=CustodyQaFixture.confirmed(w.signingKeyRef(),w.address(),CustodyQaFixture.anotherAddress(),"1500000","");assertEquals(0,fixture.broadcasts());
        String idem=key();expect("TIMEOUT_UNKNOWN_RESULT",()->lifecycle.sign(w.walletId(),w.version(),idem,fixture.request(),true));
        restart();expect("SIGN_REPLAY_FORBIDDEN",()->lifecycle.sign(w.walletId(),w.version(),idem,fixture.request(),true));assertEquals(1,custody.signs);
        try { assertEquals(Attempt.SIGN_OUTCOME_UNKNOWN,store.current().walletCatalog().operations().stream().filter(x->x.action()==Action.SIGN).findFirst().orElseThrow().attemptState()); }
        catch(AuthorityException e) { fail("authority"); }
    }
    @Test void concurrentSameCreateAndDeleteAreIdempotentWithoutDeadlock() throws Exception {
        String idem=key();try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Wallet> a=pool.submit(()->lifecycle.create(idem,null,true)),b=pool.submit(()->lifecycle.create(idem,null,true));
            Wallet w=a.get(5,TimeUnit.SECONDS);assertEquals(w,b.get(5,TimeUnit.SECONDS));assertEquals(1,custody.generated);
            String del=key();Future<Wallet> c=pool.submit(()->lifecycle.delete(w.walletId(),w.version(),del,true)),d=pool.submit(()->lifecycle.delete(w.walletId(),w.version(),del,true));
            assertEquals(c.get(5,TimeUnit.SECONDS),d.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void queryObservesCommittedPendingStateWhileCreateWaits() throws Exception {
        custody.entered=new CountDownLatch(1);custody.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Wallet> created=pool.submit(()->lifecycle.create(key(),null,true));assertTrue(custody.entered.await(3,TimeUnit.SECONDS));
            assertEquals(State.CREATING,lifecycle.query().getFirst().durableState());
            expect("WALLET_BUSY",()->lifecycle.sign(lifecycle.query().getFirst().walletId(),1,key(),null,true));
            custody.release.countDown();assertEquals(State.ACTIVE,created.get(5,TimeUnit.SECONDS).durableState());
        }
    }

    @Test void queryDuringDeleteAndSignDuringDeleteNeverReachSigner() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);custody.deleteEntered=new CountDownLatch(1);custody.deleteRelease=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<Wallet> deleted=pool.submit(()->lifecycle.delete(w.walletId(),w.version(),key(),true));
            assertTrue(custody.deleteEntered.await(3,TimeUnit.SECONDS));assertEquals(State.DELETING,lifecycle.query().getFirst().durableState());
            expect("WALLET_BUSY",()->lifecycle.sign(w.walletId(),w.version(),key(),null,true));assertEquals(0,custody.signs);
            custody.deleteRelease.countDown();assertEquals(State.DELETED,deleted.get(5,TimeUnit.SECONDS).durableState());
        }
    }
    @Test void createDeleteRaceRejectsStalePendingVersion() throws Exception {
        custody.entered=new CountDownLatch(1);custody.release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Wallet> created=pool.submit(()->lifecycle.create(key(),null,true));assertTrue(custody.entered.await(3,TimeUnit.SECONDS));
            Wallet pending=lifecycle.query().getFirst();Future<String> deleted=pool.submit(()->{
                try { lifecycle.delete(pending.walletId(),pending.version(),key(),true);return "WRONG"; }
                catch(WalletLifecycle.Failure failure) { return failure.code; }
            });
            custody.release.countDown();assertEquals(State.ACTIVE,created.get(5,TimeUnit.SECONDS).durableState());assertEquals("CONFLICT",deleted.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void twoSignsCannotOverlapAndDeleteWaitsForDurableUnknown() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);var request=CustodyQaFixture.confirmed(w.signingKeyRef(),w.address(),CustodyQaFixture.anotherAddress(),"1500000","").request();
        custody.signEntered=new CountDownLatch(1);custody.signRelease=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<String> first=pool.submit(()->{
                try { lifecycle.sign(w.walletId(),w.version(),key(),request,true);return "WRONG"; }
                catch(WalletLifecycle.Failure failure) { return failure.code; }
            });
            assertTrue(custody.signEntered.await(3,TimeUnit.SECONDS));expect("WALLET_BUSY",()->lifecycle.sign(w.walletId(),w.version(),key(),request,true));
            Future<Wallet> deleted=pool.submit(()->lifecycle.delete(w.walletId(),w.version(),key(),true));
            custody.signRelease.countDown();assertEquals("TIMEOUT_UNKNOWN_RESULT",first.get(5,TimeUnit.SECONDS));assertEquals(State.DELETED,deleted.get(5,TimeUnit.SECONDS).durableState());assertEquals(1,custody.signs);
        }
    }
    @Test void protectedSnapshotRollbackAndIndependentlyRetainedRevocationDenyReactivation() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);byte[] active=Files.readAllBytes(file);var oldAnchor=anchor.read().orElseThrow();
        lifecycle.delete(w.walletId(),w.version(),key(),true);
        Files.write(file,active);assertEquals("rollback",AuthorityStore.openWalletQa(file,anchor,vault).reason());
        anchor.write(oldAnchor);restart();assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));
        assertFalse(custody.scalars.contains(w.signingKeyRef()));assertEquals(1,custody.generated);
    }
    @Test void missingReceiptQuarantineSurvivesRestorationAndRestart() {
        Wallet w=lifecycle.create(key(),null,true);var original=custody.receipts.remove(w.signingKeyRef());restart();
        assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));custody.receipts.put(w.signingKeyRef(),original);restart();
        assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));
    }
    @Test void alteredReferenceAndMalformedPublicIdentityOrTombstoneFailClosed() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);
        for(String field:List.of("publicKey","address","durableState")) {
            var malformed=store.current().walletCatalog().json().deepCopy();
            var wallet=(com.fasterxml.jackson.databind.node.ObjectNode)malformed.path("wallets").get(0);
            wallet.put(field,field.equals("publicKey")?"02"+"00".repeat(32):field.equals("address")?CustodyQaFixture.anotherAddress():"DELETED");
            assertThrows(Exception.class,()->WalletCatalog.parse(malformed),field);
        }
        var altered=store.current().walletCatalog().json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)altered.path("wallets").get(0)).put("signingKeyRef",key());
        var catalog=WalletCatalog.parse(altered);store.mutate(s->s.withWalletCatalog(catalog));restart();
        assertEquals("ORPHAN_METADATA",lifecycle.health(w.walletId()));
        assertTrue(custody.scalars.contains(w.signingKeyRef()));assertEquals(1,custody.generated);
    }
    @Test void liveReceiptWithDifferentValidPublicIdentityIsDurablyQuarantined() {
        Wallet w=lifecycle.create(key(),null,true);var original=custody.receipts.get(w.signingKeyRef());
        String pub=FakeCustody.publicOnlyPoint(100);
        custody.receipts.put(w.signingKeyRef(),new WalletLifecycle.Receipt(1,original.binding(),"LIVE",original.revision()+1,
                pub,CosmosBankSend.address(HexFormat.of().parseHex(pub)),original.lastOperationId(),original.lastRequestDigest()));
        restart();assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));
        custody.receipts.put(w.signingKeyRef(),original);restart();assertEquals("KEY_MISMATCH",lifecycle.health(w.walletId()));
        assertEquals(0,custody.signs);
    }
    @Test void authorizationLossConsentAndRecoverySurfaceFailBeforeHelper() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);int signs=custody.signs;
        expect("LOSS_ACKNOWLEDGEMENT_REQUIRED",()->lifecycle.delete(w.walletId(),w.version(),key(),false));
        expect("CONFIRMATION_REQUIRED",()->lifecycle.sign(w.walletId(),w.version(),key(),null,false));
        session.set(new WalletLifecycle.Session(owner,1,false));expect("MFA_REQUIRED",()->lifecycle.delete(w.walletId(),w.version(),key(),true));
        store.mutate(s->s.withAccounts(s.accounts().stream().map(a->a.withEnabled(false)).toList()));session.set(new WalletLifecycle.Session(owner,2,true));
        expect("FORBIDDEN",lifecycle::query);assertEquals(signs,custody.signs);
        for(var method:WalletLifecycle.class.getMethods()) assertFalse(Set.of("export","import","backup","recover","seed","mnemonic").contains(method.getName()));
    }

    @Test void foreignOwnerAndChangedIdentityMetadataCannotSelectAnotherKey() throws Exception {
        Wallet w=lifecycle.create(key(),null,true);
        var admin=new AuthorityAdmin(store,new PasswordVerifier(new PasswordVerifier.Params(1024,1,1)),Clock.systemUTC());
        String other=admin.createAccount("other","synthetic-test-password".toCharArray(),Role.USER).id();
        session.set(new WalletLifecycle.Session(other,1,true));assertEquals(0,lifecycle.query().size());
        expect("WALLET_NOT_FOUND",()->lifecycle.delete(w.walletId(),w.version(),key(),true));
        session.set(new WalletLifecycle.Session(owner,1,true));
        var json=store.current().walletCatalog().json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("wallets").get(0)).put("walletId",key());
        assertThrows(Exception.class,()->WalletCatalog.parse(json));
        var duplicate=store.current().walletCatalog().json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("wallets")).add(duplicate.path("wallets").get(0));
        assertThrows(Exception.class,()->WalletCatalog.parse(duplicate));
    }
    private static final class InjectedCrash extends RuntimeException { }

    static final class FakeCustody implements WalletLifecycle.Custody {
        final Map<String,WalletLifecycle.Receipt> receipts=new HashMap<>();final Set<String> scalars=new HashSet<>();final Map<String,Binding> scalarBindings=new HashMap<>();int generated,signs;
        String crash;boolean incomplete,orphan;CountDownLatch entered,release,deleteEntered,deleteRelease,signEntered,signRelease;
        public void acquire() { }
        private void boundary(String point) throws WalletLifecycle.CustodyFailure { if(point.equals(crash)) throw new WalletLifecycle.CustodyFailure("TIMEOUT_UNKNOWN_RESULT"); }
        public WalletLifecycle.Reply call(String op,WalletLifecycle.Request request,TxSignRequest sign) throws WalletLifecycle.CustodyFailure {
            if(op.equals("inventoryPage")) {
                if(incomplete) return reply("INVENTORY_INCOMPLETE",null);
                var entries=new ArrayList<WalletLifecycle.Entry>();
                var refs=new HashSet<>(scalars);refs.addAll(receipts.keySet());
                for(var ref:refs) entries.add(new WalletLifecycle.Entry(ref,scalars.contains(ref),scalars.contains(ref)?scalarBindings.get(ref):null,receipts.get(ref)));
                if(orphan) { var b=new Binding("a".repeat(32),"b".repeat(32),"c".repeat(32),"d".repeat(32),"e".repeat(32),"SYNTHETIC_RANDOM_SCALAR_V1","cosmos-secp256k1");entries.add(new WalletLifecycle.Entry(b.signingKeyRef(),true,b,null)); }
                entries.sort(Comparator.comparing(WalletLifecycle.Entry::signingKeyRef));int end=Math.min(request.offset()+8,entries.size());
                return reply("INVENTORY",new WalletLifecycle.Page(entries.subList(request.offset(),end),entries.size(),end==entries.size()?-1:end,end==entries.size(),WalletLifecycle.sha(JSON.valueToTree(entries).toString())));
            }
            String ref=request.binding().signingKeyRef();var receipt=receipts.get(ref);
            if(op.equals("provisionBound")) {
                if(entered!=null) { entered.countDown();try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("deadlock"); } catch(InterruptedException e) { throw new AssertionError(e); } }
                if(receipt==null) { receipt=new WalletLifecycle.Receipt(1,request.binding(),"PREPARING",1,"","",request.operationId(),request.requestDigest());receipts.put(ref,receipt);boundary("afterPreparing"); }
                if(!scalars.contains(ref)) { boundary("beforeScalar");scalars.add(ref);scalarBindings.put(ref,request.binding());generated++;boundary("afterScalar"); }
                boundary("beforeLive");receipt=live(receipt);receipts.put(ref,receipt);boundary("afterLive");return reply("LIVE",receipt);
            }
            if(op.equals("inspectBound")) {
                if(!scalars.contains(ref)) return reply("KEY_NOT_FOUND",receipt);
                if(receipt.state().equals("PREPARING")) { receipt=live(receipt);receipts.put(ref,receipt); }
                return reply(receipt.state(),receipt);
            }
            if(op.equals("revokeDeleteBound")) {
                waitFor(deleteEntered,deleteRelease);
                boundary("beforeRevoked");receipt=new WalletLifecycle.Receipt(1,receipt.binding(),"REVOKED",receipt.revision()+1,receipt.publicKey(),receipt.address(),request.operationId(),request.requestDigest());receipts.put(ref,receipt);
                boundary("afterRevoked");boundary("beforeScalarDelete");scalars.remove(ref);boundary("afterScalarDelete");boundary("beforeAbsence");return reply("REVOKED",receipt);
            }
            if(op.equals("signBound")) { signs++;waitFor(signEntered,signRelease);return reply("SIGNED",receipt); }
            throw new AssertionError("unexpected op");
        }
        private void waitFor(CountDownLatch entered,CountDownLatch release) {
            if(entered==null) return;
            entered.countDown();try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("deadlock"); }
            catch(InterruptedException e) { throw new AssertionError(e); }
        }
        void revoke(String ref) {var r=receipts.get(ref);receipts.put(ref,new WalletLifecycle.Receipt(1,r.binding(),"REVOKED",r.revision()+1,r.publicKey(),r.address(),r.lastOperationId(),r.lastRequestDigest()));}
        private WalletLifecycle.Receipt live(WalletLifecycle.Receipt r) {
            if(r.state().equals("LIVE")) return r;
            String pub=publicOnlyPoint(generated);String address=CosmosBankSend.address(HexFormat.of().parseHex(pub));
            return new WalletLifecycle.Receipt(1,r.binding(),"LIVE",r.revision()+1,pub,address,r.lastOperationId(),r.lastRequestDigest());
        }
        private WalletLifecycle.Reply reply(String status,Object object) {
            var receipt=object instanceof WalletLifecycle.Receipt r?r:null;
            return new WalletLifecycle.Reply(status,0,receipt==null?null:receipt.publicKey(),receipt==null?null:receipt.address(),0,null,object==null?null:JSON.valueToTree(object));
        }
        private static String publicOnlyPoint(int index) {
            for(int salt=0;;salt++) {
                String pub="02"+WalletLifecycle.sha("public-only-fixture:"+index+":"+salt);
                try { CosmosBankSend.address(HexFormat.of().parseHex(pub));return pub; }
                catch(IllegalArgumentException ignored) { }
            }
        }
    }
}
