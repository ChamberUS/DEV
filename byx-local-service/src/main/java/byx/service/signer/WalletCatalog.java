package byx.service.signer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Public custody metadata inside the encrypted authority; never contains wallet secret material. */
public record WalletCatalog(String catalogId, long revision, int walletCatalogVersion, List<Wallet> wallets,
        List<Operation> operations, List<Audit> auditEvents, List<Quarantine> quarantines) {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(512).maxNumberLength(20).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    public enum State { CREATING, ACTIVE, DELETING, DELETED }
    public enum Action { CREATE, DELETE, SIGN }
    public enum Status { PENDING, COMPLETE, UNKNOWN }
    public enum Attempt { NONE, SIGN_ATTEMPT_RESERVED, SIGNED, SIGN_OUTCOME_UNKNOWN }

    public record Binding(String catalogId, String ownerAccountId, String walletId, String signingKeyRef,
            String creationOperationId, String origin, String algorithm) {
        public Binding {
            id(catalogId); id(ownerAccountId); id(walletId); id(signingKeyRef); id(creationOperationId);
            if (walletId.equals(signingKeyRef) || !"SYNTHETIC_RANDOM_SCALAR_V1".equals(origin) || !"cosmos-secp256k1".equals(algorithm)) invalid();
        }
        @Override public String toString() { return "Binding[REDACTED]"; }
    }

    public record Wallet(String walletId, String ownerAccountId, String signingKeyRef, String creationOperationId,
            String origin, String algorithm, String allowedChainId, int policyVersion, String recoveryPolicy,
            String publicKey, String address, State durableState, long version, String label, long createdAtMs,
            long updatedAtMs, Long deletedAtMs, String deletionOperationId, String publicCreationReceiptDigest,
            String quarantineReason, Long quarantinedAtMs) {
        public Wallet {
            id(walletId); id(ownerAccountId); id(signingKeyRef); id(creationOperationId);
            if (walletId.equals(signingKeyRef) || !"SYNTHETIC_RANDOM_SCALAR_V1".equals(origin) || !"cosmos-secp256k1".equals(algorithm)
                    || !"byx".equals(allowedChainId) || policyVersion != 1 || !"LOCAL_ONLY_NO_RECOVERY".equals(recoveryPolicy)
                    || durableState == null || version < 1 || createdAtMs < 0 || updatedAtMs < createdAtMs) invalid();
            if (label != null && (label.codePointCount(0,label.length()) > 64 || label.codePoints().anyMatch(Character::isISOControl))) invalid();
            if(label!=null) for(int i=0;i<label.length();i++) {
                char c=label.charAt(i);
                if(Character.isHighSurrogate(c)) { if(++i>=label.length() || !Character.isLowSurrogate(label.charAt(i))) invalid(); }
                else if(Character.isLowSurrogate(c)) invalid();
            }
            if (publicKey == null || address == null) {
                if (publicKey != null || address != null || durableState != State.CREATING) invalid();
            } else identity(publicKey,address);
            if ((durableState == State.DELETED) != (deletedAtMs != null) || deletedAtMs != null && deletedAtMs < createdAtMs) invalid();
            if (deletionOperationId != null) id(deletionOperationId);
            if ((durableState == State.DELETING || durableState == State.DELETED) && deletionOperationId == null) invalid();
            if (publicCreationReceiptDigest != null) digest(publicCreationReceiptDigest);
            if ((quarantineReason == null) != (quarantinedAtMs == null) || quarantinedAtMs != null && quarantinedAtMs < 0) invalid();
            if (quarantineReason != null && !Set.of("KEY_MISMATCH","KEY_CORRUPT","ORPHAN_METADATA").contains(quarantineReason)) invalid();
        }
        public Binding binding(String catalogId) { return new Binding(catalogId,ownerAccountId,walletId,signingKeyRef,creationOperationId,origin,algorithm); }
        @Override public String toString() { return "Wallet[" + durableState + "]"; }
    }

    public record Operation(String operationId, String ownerAccountId, String idempotencyKey, Action action, String requestDigest,
            String walletId, long expectedWalletVersion, Status status, String outcomeCode, long createdAtMs, long updatedAtMs,
            Attempt attemptState, String publicResult) {
        public Operation {
            id(operationId); id(ownerAccountId); id(idempotencyKey); id(walletId); digest(requestDigest);
            if (action == null || status == null || attemptState == null || expectedWalletVersion < 1 || createdAtMs < 0 || updatedAtMs < createdAtMs) invalid();
            if (outcomeCode != null && !outcomeCode.matches("[A-Z_]{1,64}") || publicResult != null && publicResult.length() > 128) invalid();
            if (action != Action.SIGN && attemptState != Attempt.NONE) invalid();
            if(action==Action.SIGN && (status==Status.PENDING && attemptState!=Attempt.SIGN_ATTEMPT_RESERVED
                    || status==Status.COMPLETE && attemptState!=Attempt.SIGNED || status==Status.UNKNOWN && attemptState!=Attempt.SIGN_OUTCOME_UNKNOWN)) invalid();
            if(status!=Status.COMPLETE && publicResult!=null) invalid();
            if(status==Status.COMPLETE && (publicResult==null || action==Action.SIGN && !publicResult.matches("[0-9A-F]{64}")
                    || action!=Action.SIGN && !publicResult.equals(walletId))) invalid();
        }
        @Override public String toString() { return "Operation[" + action + "," + status + "]"; }
    }
    public record Audit(String eventId,String operationId,String walletId,Action action,String resultCode,long timestampMs) {
        public Audit { id(eventId);id(operationId);id(walletId);if(action==null || resultCode==null || !resultCode.matches("[A-Z_]{1,64}") || timestampMs<0) invalid(); }
    }
    public record Quarantine(String anomalyId,String signingKeyRef,String reason,long firstObservedAtMs,Long resolvedAtMs) {
        public Quarantine { id(anomalyId);id(signingKeyRef);if(!Set.of("ORPHAN_KEY","KEY_MISMATCH").contains(reason) || firstObservedAtMs<0 || resolvedAtMs!=null && resolvedAtMs<firstObservedAtMs) invalid(); }
        @Override public String toString() { return "Quarantine[" + reason + "]"; }
    }

    public WalletCatalog {
        id(catalogId);
        if (revision < 1 || walletCatalogVersion != 1 || wallets == null || operations == null || auditEvents == null || quarantines == null) invalid();
        if (wallets.size()>64 || operations.size()>1024 || auditEvents.size()>1024 || quarantines.size()>64) throw new IllegalArgumentException("CAPACITY_EXCEEDED");
        wallets=wallets.stream().sorted(Comparator.comparing(Wallet::walletId)).toList();
        operations=operations.stream().sorted(Comparator.comparing(Operation::operationId)).toList();
        auditEvents=auditEvents.stream().sorted(Comparator.comparing(Audit::eventId)).toList();
        quarantines=quarantines.stream().sorted(Comparator.comparing(Quarantine::anomalyId)).toList();
        Set<String> ids=new HashSet<>(),refs=new HashSet<>(),pubs=new HashSet<>(),owners=new HashSet<>(),opids=new HashSet<>(),idem=new HashSet<>();
        for(Wallet w:wallets) {
            if(!ids.add(w.walletId()) || !refs.add(w.signingKeyRef()) || w.publicKey()!=null && !pubs.add(w.publicKey())
                    || w.durableState()!=State.DELETED && !owners.add(w.ownerAccountId())) invalid();
        }
        for(Operation op:operations) {
            if(!opids.add(op.operationId()) || !idem.add(op.ownerAccountId()+op.idempotencyKey()) || !ids.contains(op.walletId())) invalid();
            Wallet w=wallets.stream().filter(x->x.walletId().equals(op.walletId())).findFirst().orElseThrow();
            if(!w.ownerAccountId().equals(op.ownerAccountId())) invalid();
        }
        for(Wallet w:wallets) {
            if(operations.stream().noneMatch(op->op.operationId().equals(w.creationOperationId()) && op.action()==Action.CREATE && op.walletId().equals(w.walletId()))) invalid();
            if(w.deletionOperationId()!=null && operations.stream().noneMatch(op->op.operationId().equals(w.deletionOperationId()) && op.action()==Action.DELETE && op.walletId().equals(w.walletId()))) invalid();
            Operation create=operations.stream().filter(op->op.operationId().equals(w.creationOperationId())).findFirst().orElseThrow();
            if((w.durableState()==State.CREATING)==(create.status()==Status.COMPLETE)) invalid();
            if(w.deletionOperationId()!=null) {
                Operation delete=operations.stream().filter(op->op.operationId().equals(w.deletionOperationId())).findFirst().orElseThrow();
                if((w.durableState()==State.DELETED)!=(delete.status()==Status.COMPLETE)) invalid();
            }
        }
        Set<String> eventIds=new HashSet<>(),anomalies=new HashSet<>(),qrefs=new HashSet<>();
        for(Audit a:auditEvents) if(!eventIds.add(a.eventId()) || !opids.contains(a.operationId()) || !ids.contains(a.walletId())) invalid();
        for(Quarantine q:quarantines) if(!anomalies.add(q.anomalyId()) || !qrefs.add(q.signingKeyRef())) invalid();
    }
    public static WalletCatalog empty(String id) { return new WalletCatalog(id,1,1,List.of(),List.of(),List.of(),List.of()); }
    public static WalletCatalog parse(JsonNode node) throws java.io.IOException { return JSON.treeToValue(node,WalletCatalog.class); }
    static <T> T decode(JsonNode node,Class<T> type) throws java.io.IOException { return JSON.treeToValue(node,type); }
    public JsonNode json() { return JSON.valueToTree(this); }
    public static void id(String value) { if(value==null || !value.matches("[0-9a-f]{32}")) invalid(); }
    public static void digest(String value) { if(value==null || !value.matches("[0-9a-f]{64}")) invalid(); }
    public static void identity(String pub,String address) {
        if(pub==null || !pub.matches("(?:02|03)[0-9a-f]{64}") || address==null) invalid();
        byte[] bytes=HexFormat.of().parseHex(pub);
        try {
            var point=org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1").getCurve().decodePoint(bytes);
            if(point.isInfinity() || !point.isValid() || !java.util.Arrays.equals(point.getEncoded(true),bytes) || !CosmosBankSend.address(bytes).equals(address)) invalid();
        } catch(RuntimeException e) { throw new IllegalArgumentException("CORRUPT_CATALOG"); }
    }
    private static void invalid() { throw new IllegalArgumentException("CORRUPT_CATALOG"); }
    @Override public String toString() { return "WalletCatalog[revision="+revision+"]"; }
}
