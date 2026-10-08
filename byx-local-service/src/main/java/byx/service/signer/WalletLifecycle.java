package byx.service.signer;

import byx.service.auth.AuthorityException;
import byx.service.auth.AuthorityStore;
import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.TxSignRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import static byx.service.signer.WalletCatalog.*;

/** Synthetic-only composition; no application route, transport or recovery capability. */
public final class WalletLifecycle {
    public record Session(String ownerAccountId,long credentialVersion,boolean recentMfa) { }
    interface Authorization { Session current(); }
    interface Confirmation { boolean current(TxSignRequest request); }
    interface Custody {
        void acquire() throws CustodyFailure;
        default void verifyAvailability() throws CustodyFailure { }
        Reply call(String op,Request request,TxSignRequest sign) throws CustodyFailure;
    }
    static final class CustodyFailure extends Exception {
        private final String code;
        CustodyFailure(String code) { super(code,null,false,false);this.code=code; }
        String code() { return code; }
    }
    record Request(Binding binding,String operationId,String requestDigest,long expectedVersion,String publicKey,String address,int offset,String snapshotDigest) { }
    record Reply(String status,int keychainCalls,String publicKey,String address,int count,JsonNode response,JsonNode lifecycle) { }
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code,null,false,false);this.code=code; }
    }
    record Receipt(int receiptVersion,Binding binding,String state,long revision,String publicKey,String address,
            String lastOperationId,String lastRequestDigest) {
        Receipt {
            if(receiptVersion!=1 || binding==null || !Set.of("PREPARING","LIVE","REVOKED").contains(state) || revision<1) fail("KEY_MISMATCH");
            id(lastOperationId);digest(lastRequestDigest);
            if(publicKey==null || address==null) fail("KEY_MISMATCH");
            if(!publicKey.isEmpty() || !address.isEmpty()) identity(publicKey,address);
            else if(state.equals("LIVE")) fail("KEY_MISMATCH");
        }
        @Override public String toString() { return "Receipt["+state+"]"; }
    }
    record Entry(String signingKeyRef,boolean scalarPresent,Binding scalarBinding,Receipt receipt) {
        Entry { id(signingKeyRef);if(scalarPresent!=(scalarBinding!=null) || scalarBinding!=null && !scalarBinding.signingKeyRef().equals(signingKeyRef)
                || receipt!=null && !receipt.binding().signingKeyRef().equals(signingKeyRef)) fail("INVENTORY_INCOMPLETE"); }
        @Override public String toString() { return "Entry[REDACTED]"; }
    }
    record Page(List<Entry> entries,int totalCount,int nextOffset,boolean complete,String snapshotDigest) {
        Page { entries=List.copyOf(entries);digest(snapshotDigest);if(entries.size()>8 || totalCount<0 || totalCount>64 || nextOffset < -1 || complete!=(nextOffset==-1)) fail("INVENTORY_INCOMPLETE"); }
    }
    private final AuthorityStore store;
    private final Custody custody;
    private final Authorization authorization;
    private final Clock clock;
    private final Confirmation confirmation;
    private final ReentrantLock global=new ReentrantLock(true);
    private final Map<String,ReentrantLock> owners=new HashMap<>();
    private volatile Map<String,String> health=Map.of();
    private volatile boolean reconciled;
    private volatile String lastInventoryFailure = "";
    private java.util.function.Consumer<String> boundary=point->{ };
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    WalletLifecycle(AuthorityStore store,Custody custody,Authorization authorization,Clock clock) {
        this(store,custody,authorization,request->false,clock);
    }
    WalletLifecycle(AuthorityStore store,Custody custody,Authorization authorization,Confirmation confirmation,Clock clock) {
        this.store=store;this.custody=custody;this.authorization=authorization;this.clock=clock;
        this.confirmation=confirmation;
        if(catalog()==null) fail("FEATURE_DISABLED");
    }
    void fault(java.util.function.Consumer<String> boundary) { this.boundary=boundary; }
    record Observation(String availability, Map<String,String> health) { }
    Observation publicationObservation() {
        if (!global.tryLock()) return new Observation("OPERATION_PENDING", Map.of());
        try {
            try { custody.verifyAvailability(); }
            catch (CustodyFailure e) { return new Observation("SIGNER_UNAVAILABLE", Map.of()); }
            if (!reconciled) return new Observation("NEEDS_ATTENTION", Map.of());
            Map<String,Entry> entries;
            lastInventoryFailure = "";
            try { entries = inventory(); }
            catch (Failure e) {
                if (lastInventoryFailure.equals("CUSTODY_QUIESCENCE_UNPROVEN")) return new Observation("NEEDS_ATTENTION", Map.of());
                if (Set.of("SIGNER_UNTRUSTED", "SIGNER_UNAVAILABLE", "SIGNING_FAILED", "TIMEOUT_UNKNOWN_RESULT").contains(lastInventoryFailure)) return new Observation("SIGNER_UNAVAILABLE", Map.of());
                return new Observation("INVENTORY_INCOMPLETE", Map.of());
            }
            Map<String,String> observed = new HashMap<>();
            Set<String> known = new HashSet<>();
            for (Wallet w : catalog().wallets()) {
                known.add(w.signingKeyRef()); Entry entry = entries.get(w.signingKeyRef());
                if (w.durableState() == State.ACTIVE) {
                    String state = entry == null || !entry.scalarPresent() ? "ORPHAN_METADATA"
                            : entry.receipt() == null || !entry.receipt().state().equals("LIVE")
                              || !entry.scalarBinding().equals(w.binding(catalog().catalogId()))
                              || !entry.receipt().binding().equals(w.binding(catalog().catalogId()))
                              || !w.publicKey().equals(entry.receipt().publicKey()) || !w.address().equals(entry.receipt().address())
                            ? "KEY_MISMATCH" : healthOf(w);
                    observed.put(w.walletId(), state);
                }
            }
            if (entries.keySet().stream().anyMatch(ref -> !known.contains(ref))) return new Observation("NEEDS_ATTENTION", Map.copyOf(observed));
            return new Observation("AVAILABLE", Map.copyOf(observed));
        } finally { global.unlock(); }
    }
    WalletCatalog publicationCatalog() { return catalog(); }
    boolean publicationReconciled() { return reconciled; }
    private WalletCatalog catalog() {
        try { return store.current().walletCatalog(); } catch(AuthorityException e) { throw new Failure("CORRUPT_CATALOG"); }
    }
    private Session auth(boolean mfa) {
        Session s=authorization.current();
        if(s==null) fail("UNAUTHORIZED");
        try {
            var a=store.current().byId(s.ownerAccountId()).orElse(null);
            if(a==null || !a.enabled() || a.credentialVersion()!=s.credentialVersion() || store.current().migrationFreeze()) fail("FORBIDDEN");
        } catch(AuthorityException e) { throw new Failure("CORRUPT_CATALOG"); }
        if(mfa && !s.recentMfa()) fail("MFA_REQUIRED");
        return s;
    }
    public List<Wallet> query() {
        Session s=auth(false);
        return catalog().wallets().stream().filter(w->w.ownerAccountId().equals(s.ownerAccountId())).toList();
    }
    public String health(String walletId) {
        Wallet w=query().stream().filter(x->x.walletId().equals(walletId)).findFirst().orElseThrow(()->new Failure("WALLET_NOT_FOUND"));
        return healthOf(w);
    }
    private String healthOf(Wallet w) {
        return w.quarantineReason() != null ? w.quarantineReason() : health.getOrDefault(w.walletId(), "UNRECONCILED");
    }
    private void ready() { if(!reconciled) fail("RECONCILIATION_REQUIRED"); }
    private Reply call(String op,Wallet w,Operation operation,TxSignRequest sign) {
        var req=new Request(w.binding(catalog().catalogId()),operation.operationId(),operation.requestDigest(),w.version(),
                w.publicKey()==null?"":w.publicKey(),w.address()==null?"":w.address(),0,"");
        try { return custody.call(op,req,sign); }
        catch(CustodyFailure e) {
            if(e.code().equals("CUSTODY_QUIESCENCE_UNPROVEN")) reconciled=false;
            throw new Failure(e.code());
        }
    }
    private Map<String,Entry> inventory() {
        Map<String,Entry> entries=new HashMap<>();String digest="";int offset=0,total=-1;
        String op=randomId(),requestDigest=sha("INVENTORY");
        do {
            Reply reply;
            try { reply=custody.call("inventoryPage",new Request(null,op,requestDigest,1,"","",offset,digest),null); }
            catch(CustodyFailure e) {
                lastInventoryFailure = e.code();
                if (e.code().equals("CUSTODY_QUIESCENCE_UNPROVEN")) reconciled = false;
                throw new Failure("INVENTORY_INCOMPLETE");
            }
            if(!"INVENTORY".equals(reply.status())) fail("INVENTORY_INCOMPLETE");
            Page page=decode(reply.lifecycle(),Page.class,"INVENTORY_INCOMPLETE");
            if(total<0) { total=page.totalCount();digest=page.snapshotDigest(); }
            if(total!=page.totalCount() || !digest.equals(page.snapshotDigest()) || page.entries().size()>total-offset || page.entries().isEmpty() && total>offset) fail("INVENTORY_INCOMPLETE");
            for(Entry entry:page.entries()) if(entries.put(entry.signingKeyRef(),entry)!=null) fail("INVENTORY_INCOMPLETE");
            offset+=page.entries().size();
            if(page.complete()) { if(offset!=total || entries.size()!=total) fail("INVENTORY_INCOMPLETE");break; }
            if(page.nextOffset()!=offset || offset>=total) fail("INVENTORY_INCOMPLETE");
        } while(offset<64);
        if(entries.size()!=total) fail("INVENTORY_INCOMPLETE");
        return entries;
    }
    public void reconcile() {
        global.lock();
        try {
            reconciled=false;
            try { custody.acquire(); } catch(CustodyFailure e) { throw new Failure(e.code()); }
            for(Operation op:catalog().operations()) if(op.attemptState()==Attempt.SIGN_ATTEMPT_RESERVED) {
                save(null,updated(op,Status.UNKNOWN,"SIGN_OUTCOME_UNKNOWN",Attempt.SIGN_OUTCOME_UNKNOWN,null),false);
            }
            Map<String,Entry> inventory=inventory();Map<String,String> observed=new HashMap<>();
            Set<String> known=new HashSet<>();
            for(Wallet w:catalog().wallets()) {
                known.add(w.signingKeyRef());Entry e=inventory.get(w.signingKeyRef());String state;
                if(w.quarantineReason()!=null) { observed.put(w.walletId(),w.quarantineReason());continue; }
                if(e!=null && (e.scalarBinding()!=null && !e.scalarBinding().equals(w.binding(catalog().catalogId()))
                        || e.receipt()!=null && !e.receipt().binding().equals(w.binding(catalog().catalogId())))) state="KEY_MISMATCH";
                else if(w.durableState()==State.DELETED) state=e!=null && !e.scalarPresent() && e.receipt()!=null && e.receipt().state().equals("REVOKED")?"DELETED":"KEY_MISMATCH";
                else if(e!=null && e.receipt()!=null && e.receipt().state().equals("REVOKED") && w.durableState()==State.ACTIVE) state="KEY_MISMATCH";
                else if(w.durableState()==State.DELETING) {
                    Operation op=operation(w.deletionOperationId());
                    try { deleteFinish(w,op);state="DELETED"; } catch(Failure f) { state=f.code; }
                } else if(w.durableState()==State.CREATING) {
                    if(e==null || !e.scalarPresent()) state="CREATE_IN_PROGRESS";
                    else if(e.receipt()==null || e.receipt().state().equals("REVOKED")) state="KEY_MISMATCH";
                    else {
                        try { activate(w,operation(w.creationOperationId()),call("inspectBound",w,operation(w.creationOperationId()),null));state="HEALTHY"; }
                        catch(Failure f) { state=f.code; }
                    }
                } else if(e==null || !e.scalarPresent()) state="ORPHAN_METADATA";
                else if(e.receipt()==null || !e.receipt().state().equals("LIVE")) state="KEY_MISMATCH";
                else {
                    Reply reply=call("inspectBound",w,operation(w.creationOperationId()),null);
                    if("LIVE".equals(reply.status())) state=w.publicKey().equals(reply.publicKey()) && w.address().equals(reply.address())?"HEALTHY":"KEY_MISMATCH";
                    else if("KEY_NOT_FOUND".equals(reply.status())) state="ORPHAN_METADATA";
                    else state=reply.status();
                }
                if(Set.of("KEY_MISMATCH","KEY_CORRUPT","ORPHAN_METADATA").contains(state)) quarantineWallet(w,state);
                observed.put(w.walletId(),state);
            }
            for(Entry e:inventory.values()) if(!known.contains(e.signingKeyRef())) quarantineOrphan(e.signingKeyRef());
            health=Map.copyOf(observed);
            reconciled=true;
        } finally { global.unlock(); }
    }
    public Wallet create(String idempotencyKey,String label,boolean recoveryAcknowledged) {
        id(idempotencyKey);if(!recoveryAcknowledged) fail("LOSS_ACKNOWLEDGEMENT_REQUIRED");
        global.lock();ReentrantLock owner=null;
        try {
            ready();Session session=auth(false);owner=owners.computeIfAbsent(session.ownerAccountId(),x->new ReentrantLock());owner.lock();
            if(catalog().quarantines().stream().anyMatch(q->q.resolvedAtMs()==null)) fail("ORPHAN_KEY");
            String digest=createDigest(session.ownerAccountId(),label);
            Operation prior=findIdempotency(session.ownerAccountId(),idempotencyKey,Action.CREATE,digest);
            if(prior!=null) {
                Wallet w=wallet(prior.walletId());Wallet result=prior.status()==Status.COMPLETE?w:createFinish(w,prior);
                auth(false);return result;
            }
            if(catalog().wallets().stream().anyMatch(w->w.ownerAccountId().equals(session.ownerAccountId()) && w.durableState()!=State.DELETED)) fail("CONFLICT");
            capacity(true);long now=clock.millis();String walletId=uniqueId(),ref=uniqueId();while(ref.equals(walletId)) ref=uniqueId();String opId;
            do { opId=uniqueId(); } while(opId.equals(walletId) || opId.equals(ref));
            Wallet w=new Wallet(walletId,session.ownerAccountId(),ref,opId,"SYNTHETIC_RANDOM_SCALAR_V1","cosmos-secp256k1","byx",1,"LOCAL_ONLY_NO_RECOVERY",
                    null,null,State.CREATING,1,label,now,now,null,null,null,null,null);
            Operation op=new Operation(opId,session.ownerAccountId(),idempotencyKey,Action.CREATE,digest,walletId,1,Status.PENDING,null,now,now,Attempt.NONE,null);
            boundary.accept("beforeCreating");save(w,op,false);boundary.accept("afterCreating");
            Wallet result=createFinish(w,op);auth(false);return result;
        } finally { if(owner!=null && owner.isHeldByCurrentThread()) owner.unlock();global.unlock(); }
    }
    private Wallet createFinish(Wallet w,Operation op) {
        if(w.durableState()!=State.CREATING || w.quarantineReason()!=null) fail("WALLET_NOT_ACTIVE");
        try { return activate(w,op,call("provisionBound",w,op,null)); }
        catch(Failure f) { uncertain(op,f);throw f; }
    }
    private Wallet activate(Wallet w,Operation op,Reply reply) {
        if(!"LIVE".equals(reply.status())) fail(reply.status());
        Receipt receipt=decode(reply.lifecycle(),Receipt.class,"KEY_MISMATCH");
        if(!receipt.binding().equals(w.binding(catalog().catalogId())) || !receipt.state().equals("LIVE") || !receipt.lastOperationId().equals(op.operationId())
                || !receipt.lastRequestDigest().equals(op.requestDigest()) || !receipt.publicKey().equals(reply.publicKey()) || !receipt.address().equals(reply.address())) fail("KEY_MISMATCH");
        identity(reply.publicKey(),reply.address());
        Wallet active=new Wallet(w.walletId(),w.ownerAccountId(),w.signingKeyRef(),w.creationOperationId(),w.origin(),w.algorithm(),w.allowedChainId(),w.policyVersion(),w.recoveryPolicy(),
                reply.publicKey(),reply.address(),State.ACTIVE,w.version()+1,w.label(),w.createdAtMs(),clock.millis(),null,null,sha(reply.lifecycle().toString()),null,null);
        boundary.accept("beforeActive");save(active,updated(op,Status.COMPLETE,"CREATED",Attempt.NONE,active.walletId()),true);boundary.accept("afterActive");
        var observed=new HashMap<>(health);observed.put(w.walletId(),"HEALTHY");health=Map.copyOf(observed);
        return active;
    }
    public Wallet delete(String walletId,long expectedVersion,String idempotencyKey,boolean lossAcknowledged) {
        id(walletId);id(idempotencyKey);if(!lossAcknowledged) fail("LOSS_ACKNOWLEDGEMENT_REQUIRED");
        global.lock();ReentrantLock owner=null;
        try {
            ready();Session session=auth(true);owner=owners.computeIfAbsent(session.ownerAccountId(),x->new ReentrantLock());owner.lock();
            Wallet w=owned(walletId,session.ownerAccountId());
            String digest=sha(JSON.valueToTree(List.of("DELETE",session.ownerAccountId(),walletId,expectedVersion,true)).toString());
            Operation prior=findIdempotency(session.ownerAccountId(),idempotencyKey,Action.DELETE,digest);
            if(prior!=null) { Wallet result=prior.status()==Status.COMPLETE?w:deleteFinish(w,prior);auth(true);return result; }
            if(w.version()!=expectedVersion) fail("CONFLICT");
            if(w.durableState()!=State.ACTIVE || "KEY_MISMATCH".equals(w.quarantineReason())) fail("WALLET_NOT_ACTIVE");
            capacity(false);long now=clock.millis();String opId=uniqueId();
            Operation op=new Operation(opId,session.ownerAccountId(),idempotencyKey,Action.DELETE,digest,walletId,expectedVersion,Status.PENDING,null,now,now,Attempt.NONE,null);
            Wallet pending=copy(w,State.DELETING,w.version()+1,opId,null,w.quarantineReason(),w.quarantinedAtMs());
            boundary.accept("beforeDeleting");save(pending,op,false);boundary.accept("afterDeleting");
            try { Wallet result=deleteFinish(pending,op);auth(true);return result; } catch(Failure f) { uncertain(op,f);throw f; }
        } finally { if(owner!=null && owner.isHeldByCurrentThread()) owner.unlock();global.unlock(); }
    }
    private Wallet deleteFinish(Wallet w,Operation op) {
        if(w.durableState()!=State.DELETING) fail("WALLET_NOT_ACTIVE");
        Reply reply=call("revokeDeleteBound",w,op,null);
        if(!"REVOKED".equals(reply.status())) fail(reply.status());
        Receipt r=decode(reply.lifecycle(),Receipt.class,"KEY_MISMATCH");
        if(!r.binding().equals(w.binding(catalog().catalogId())) || !r.state().equals("REVOKED") || !r.lastOperationId().equals(op.operationId()) || !r.lastRequestDigest().equals(op.requestDigest())) fail("KEY_MISMATCH");
        Wallet deleted=copy(w,State.DELETED,w.version()+1,op.operationId(),clock.millis(),w.quarantineReason(),w.quarantinedAtMs());
        boundary.accept("beforeDeleted");save(deleted,updated(op,Status.COMPLETE,"DELETED",Attempt.NONE,deleted.walletId()),true);boundary.accept("afterDeleted");
        var observed = new HashMap<>(health); observed.put(w.walletId(), "DELETED"); health = Map.copyOf(observed);
        return deleted;
    }
    public SignedTx sign(String walletId,long expectedVersion,String idempotencyKey,TxSignRequest request,boolean explicitlyConfirmed) {
        id(idempotencyKey);if(!explicitlyConfirmed) fail("CONFIRMATION_REQUIRED");
        if(!global.tryLock()) fail("WALLET_BUSY");ReentrantLock owner=null;
        try {
            ready();Session session=auth(true);owner=owners.computeIfAbsent(session.ownerAccountId(),x->new ReentrantLock());owner.lock();
            Wallet w=owned(walletId,session.ownerAccountId());
            if(w.version()!=expectedVersion) fail("CONFLICT");
            if(w.durableState()!=State.ACTIVE || !"HEALTHY".equals(health(walletId))) fail("WALLET_NOT_ACTIVE");
            if(!confirmation.current(request) || !request.key().keyId().equals(w.signingKeyRef()) || !request.quote().sender().value().equals(w.address()) || request.quote().expiredAt(clock.millis())) fail("QUOTE_MISMATCH");
            String digest=sha(JSON.valueToTree(List.of("SIGN",session.ownerAccountId(),walletId,expectedVersion,CosmosBankSend.material(request,HexFormat.of().parseHex(w.publicKey())).request())).toString());
            if(findIdempotency(session.ownerAccountId(),idempotencyKey,Action.SIGN,digest)!=null) fail("SIGN_REPLAY_FORBIDDEN");
            if(catalog().operations().stream().anyMatch(op->op.action()==Action.SIGN && op.walletId().equals(walletId) && op.requestDigest().equals(digest))) fail("SIGN_REPLAY_FORBIDDEN");
            capacity(false);long now=clock.millis();Operation op=new Operation(uniqueId(),session.ownerAccountId(),idempotencyKey,Action.SIGN,digest,walletId,expectedVersion,
                    Status.PENDING,null,now,now,Attempt.SIGN_ATTEMPT_RESERVED,null);
            save(null,op,false);
            SignedTx result;
            try {
                Reply reply=call("signBound",w,op,request);
                result=verifySign(reply,request,w.publicKey());
                Receipt receipt=decode(reply.lifecycle(),Receipt.class,"KEY_MISMATCH");
                if(!receipt.binding().equals(w.binding(catalog().catalogId())) || !receipt.state().equals("LIVE")) fail("KEY_MISMATCH");
                Session current=auth(true);if(!current.equals(session) || !confirmation.current(request) || wallet(walletId).version()!=expectedVersion) fail("FORBIDDEN");
                save(null,updated(op,Status.COMPLETE,"SIGNED",Attempt.SIGNED,result.txHash()),true);
            } catch(byx.service.tx.TxPorts.TxSignerException | Failure e) {
                save(null,updated(op,Status.UNKNOWN,"SIGN_OUTCOME_UNKNOWN",Attempt.SIGN_OUTCOME_UNKNOWN,null),false);
                throw new Failure("TIMEOUT_UNKNOWN_RESULT");
            }
            if(!auth(true).equals(session) || !confirmation.current(request)) fail("FORBIDDEN");
            return result;
        } finally { if(owner!=null && owner.isHeldByCurrentThread()) owner.unlock();global.unlock(); }
    }
    private void capacity(boolean create) { WalletCatalog c=catalog();if(create && c.wallets().size()>=64 || c.operations().size()>=1024 || c.auditEvents().size()>=1024) fail("CAPACITY_EXCEEDED"); }
    private static SignedTx verifySign(Reply reply,TxSignRequest sign,String publicKey) throws byx.service.tx.TxPorts.TxSignerException {
        try {
            if(!"SIGNED".equals(reply.status()) || reply.response()==null) throw new byx.service.tx.TxPorts.TxSignerException();
            byte[] pub=HexFormat.of().parseHex(publicKey);
            return SignerClient.verifySigned(reply.response(),sign,CosmosBankSend.material(sign,pub),pub);
        } catch(java.io.IOException | RuntimeException e) { throw new byx.service.tx.TxPorts.TxSignerException(); }
    }
    private void uncertain(Operation op,Failure failure) {
        if(failure.code.equals("TIMEOUT_UNKNOWN_RESULT") || failure.code.equals("CUSTODY_QUIESCENCE_UNPROVEN")) save(null,updated(op,Status.UNKNOWN,"TIMEOUT_UNKNOWN_RESULT",Attempt.NONE,null),false);
    }
    private Operation updated(Operation op,Status status,String outcome,Attempt attempt,String result) {
        return new Operation(op.operationId(),op.ownerAccountId(),op.idempotencyKey(),op.action(),op.requestDigest(),op.walletId(),op.expectedWalletVersion(),status,outcome,op.createdAtMs(),clock.millis(),attempt,result);
    }
    private Wallet copy(Wallet w,State state,long version,String deletion,Long deleted,String quarantine,Long quarantinedAt) {
        return new Wallet(w.walletId(),w.ownerAccountId(),w.signingKeyRef(),w.creationOperationId(),w.origin(),w.algorithm(),w.allowedChainId(),w.policyVersion(),w.recoveryPolicy(),
                w.publicKey(),w.address(),state,version,w.label(),w.createdAtMs(),clock.millis(),deleted,deletion,w.publicCreationReceiptDigest(),quarantine,quarantinedAt);
    }
    private void quarantineWallet(Wallet w,String reason) { save(copy(w,w.durableState(),w.version()+1,w.deletionOperationId(),w.deletedAtMs(),reason,clock.millis()),null,false); }
    private void quarantineOrphan(String ref) {
        WalletCatalog cur=catalog();if(cur.quarantines().stream().anyMatch(q->q.signingKeyRef().equals(ref))) return;
        var q=new ArrayList<>(cur.quarantines());q.add(new Quarantine(uniqueId(),ref,"ORPHAN_KEY",clock.millis(),null));
        commit(new WalletCatalog(cur.catalogId(),cur.revision()+1,1,cur.wallets(),cur.operations(),cur.auditEvents(),q));
    }
    private void save(Wallet wallet,Operation op,boolean audit) {
        WalletCatalog cur=catalog();var ws=new ArrayList<>(cur.wallets());var ops=new ArrayList<>(cur.operations());var events=new ArrayList<>(cur.auditEvents());
        if(wallet!=null) { ws.removeIf(x->x.walletId().equals(wallet.walletId()));ws.add(wallet); }
        if(op!=null) { ops.removeIf(x->x.operationId().equals(op.operationId()));ops.add(op); }
        if(audit) events.add(new Audit(uniqueId(),op.operationId(),op.walletId(),op.action(),op.outcomeCode(),clock.millis()));
        commit(new WalletCatalog(cur.catalogId(),cur.revision()+1,1,ws,ops,events,cur.quarantines()));
    }
    private void commit(WalletCatalog catalog) {
        try { store.mutate(s->s.withWalletCatalog(catalog)); }
        catch(AuthorityException e) { reconciled=false;throw new Failure("CORRUPT_CATALOG"); }
    }
    private Operation operation(String id) { return catalog().operations().stream().filter(x->x.operationId().equals(id)).findFirst().orElseThrow(()->new Failure("CORRUPT_CATALOG")); }
    private Operation findIdempotency(String owner,String key,Action action,String digest) {
        Operation op=catalog().operations().stream().filter(x->x.ownerAccountId().equals(owner) && x.idempotencyKey().equals(key)).findFirst().orElse(null);
        if(op!=null && (op.action()!=action || !op.requestDigest().equals(digest))) fail("CONFLICT");return op;
    }
    private Wallet wallet(String id) { return catalog().wallets().stream().filter(x->x.walletId().equals(id)).findFirst().orElseThrow(()->new Failure("WALLET_NOT_FOUND")); }
    private Wallet owned(String id,String owner) { Wallet w=wallet(id);if(!w.ownerAccountId().equals(owner)) fail("WALLET_NOT_FOUND");return w; }
    private String uniqueId() {
        WalletCatalog c=catalog();Set<String> used=new HashSet<>();used.add(c.catalogId());
        for(Wallet w:c.wallets()) { used.add(w.walletId());used.add(w.signingKeyRef()); }
        for(Operation op:c.operations()) used.add(op.operationId());for(Audit a:c.auditEvents()) used.add(a.eventId());for(Quarantine q:c.quarantines()) { used.add(q.anomalyId());used.add(q.signingKeyRef()); }
        String id;do { id=randomId(); } while(used.contains(id));return id;
    }
    static String randomId() { byte[] b=new byte[16];RANDOM.nextBytes(b);return HexFormat.of().formatHex(b); }
    static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    private static String createDigest(String owner,String label) { return sha(JSON.valueToTree(java.util.Arrays.asList("CREATE",owner,label,"LOCAL_ONLY_NO_RECOVERY",true)).toString()); }
    private static <T> T decode(JsonNode node,Class<T> type,String error) {
        try { if(node==null || !node.isObject()) fail(error);return WalletCatalog.decode(node,type); }
        catch(java.io.IOException | IllegalArgumentException e) { throw new Failure(error); }
    }
    private static void fail(String code) { throw new Failure(code); }
}
