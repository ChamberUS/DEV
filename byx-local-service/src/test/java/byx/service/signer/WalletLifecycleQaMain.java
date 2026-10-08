package byx.service.signer;

import byx.service.auth.*;
import byx.service.secrets.*;
import byx.service.tx.CustodyQaFixture;
import byx.service.tx.TxPorts.TxSignRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Signed test-role entry point; synthetic storage and TEST-scoped anchors only. */
final class WalletLifecycleQaMain {
    record Fixture(String ownerAccountId,String createIdempotencyKey,String deleteIdempotencyKey,String signIdempotencyKey) { }
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static void run(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("QA_ROOT_REQUIRED");
        Path root=Path.of(args[1]);
        if(!root.isAbsolute() || Files.isSymbolicLink(root) || !root.toRealPath().equals(root) || !root.startsWith(Path.of("/private/tmp"))
                || !root.getFileName().toString().startsWith("byx-wallet-qa-") || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalArgumentException("QA_ROOT_UNTRUSTED");
        }
        var client=new CustodyClient();client.authority();
        var secrets=SecretStores.test();
        var anchor=new SecretStoreAnchor(secrets,SecretId.WALLET_LIFECYCLE_TEST_ANCHOR);
        var vault=new SecretStoreKeyVault(secrets,SecretId.WALLET_LIFECYCLE_TEST_ENCRYPTION_KEY);
        Path snapshot=root.resolve("catalog.bin"),fixturePath=root.resolve("request.json");
        var store=AuthorityStore.openWalletQa(snapshot,anchor,vault);
        Fixture fixture;
        if(args[0].equals("wallet-init")) {
            if(anchor.read().isPresent() || vault.read().isPresent()) throw new IllegalStateException("QA_ANCHOR_ALREADY_PRESENT");
            store.initializeWalletQa(WalletCatalog.empty(WalletLifecycle.randomId()));
            var admin=new AuthorityAdmin(store,new PasswordVerifier(new PasswordVerifier.Params(1024,1,1)),Clock.systemUTC());
            String owner=admin.createAccount("lifecycle_qa","synthetic-lifecycle-test-password".toCharArray(),Role.ADMIN).id();
            fixture=new Fixture(owner,WalletLifecycle.randomId(),WalletLifecycle.randomId(),WalletLifecycle.randomId());
            byte[] bytes=JSON.writeValueAsBytes(fixture);
            try(var ch=FileChannel.open(fixturePath,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())ch.write(buffer);ch.force(true);
            }
            emit("initialized",true);return;
        }
        if(!Files.isRegularFile(fixturePath,LinkOption.NOFOLLOW_LINKS) || Files.size(fixturePath)>1024) throw new IllegalArgumentException("QA_FIXTURE_INVALID");
        fixture=JSON.readValue(Files.readAllBytes(fixturePath),Fixture.class);
        var account=store.current().byId(fixture.ownerAccountId()).orElseThrow();
        if(!account.username().equals("lifecycle_qa") || store.current().accounts().size()!=1) throw new IllegalArgumentException("QA_ACCOUNT_REQUIRED");
        String point=args.length>2?args[2]:"";
        if(args[0].equals("wallet-wire")) {
            client.observeWire(bytes->{
                String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                text=switch(point) {
                    case "duplicate" -> text.replace("\"protocolVersion\":3","\"protocolVersion\":3,\"protocolVersion\":3");
                    case "missing" -> text.replace("\"expectedVersion\":1,","");
                    case "type" -> text.replace("\"expectedVersion\":1","\"expectedVersion\":\"1\"");
                    case "unknown" -> text.replace("\"expectedVersion\":1","\"expectedVersion\":1,\"extra\":true");
                    case "trailing_json" -> text+"{}";
                    default -> text;
                };
                return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            },out->{
                if(point.equals("trailing_frame") || point.equals("delayed_frame")) {
                    try { if(point.equals("delayed_frame")) Thread.sleep(75);out.writeInt(2);out.write(new byte[]{'{','}'});out.flush(); }
                    catch(Exception e) { throw new IllegalStateException("QA_WIRE_PROBE"); }
                }
            },reply->{
                emit("wireKeychainCalls",reply.path("keychainCalls").asInt(-1));
                if(point.equals("stale_reply")) ((com.fasterxml.jackson.databind.node.ObjectNode)reply).put("generation","0".repeat(32));
            });
            try {
                client.lifecycle("inventoryPage",new CustodyClient.LifecycleRequest(null,WalletLifecycle.randomId(),WalletLifecycle.sha("QA_WIRE"),1,"","",0,""),null);
                throw new AssertionError("QA_WIRE_ACCEPTED");
            } catch(CustodyClient.CustodyException expected) { emit("wireRejected",expected.code()); }
            return;
        }
        AtomicReference<TxSignRequest> confirmed=new AtomicReference<>();
        var port=new WalletLifecycleQaCustody(client,HELPER_POINTS.contains(point)?point:"");
        if(HELPER_POINTS.contains(point)) client.observeProbe(n->emit("boundary",n.path("probeStage").asText()));
        client.observeChild(pid->emit("helperPid",pid));
        var lifecycle=new WalletLifecycle(store,port,()->new WalletLifecycle.Session(account.id(),account.credentialVersion(),true),r->r==confirmed.get() && !r.quote().expiredAt(System.currentTimeMillis()),Clock.systemUTC());
        lifecycle.reconcile();
        if(point.startsWith("writer:")) WalletWriterQaFaults.attach(store,boundary->{
            if(point.equals("writer:"+boundary)) {
                emit("boundary",point);
                try { System.in.read(); } catch(java.io.IOException e) { throw new IllegalStateException("QA_STDIN"); }
                throw new IllegalStateException("QA_RESUME_FORBIDDEN");
            }
        });
        lifecycle.fault(boundary->{
            if(boundary.equals(point)) {
                emit("boundary",boundary);
                try { System.in.read(); } catch(java.io.IOException e) { throw new IllegalStateException("QA_STDIN"); }
                throw new IllegalStateException("QA_RESUME_FORBIDDEN");
            }
        });
        switch(args[0]) {
            case "wallet-create" -> lifecycle.create(fixture.createIdempotencyKey(),null,true);
            case "wallet-delete" -> {
                var wallet=lifecycle.query().getFirst();
                var prior=store.current().walletCatalog().operations().stream().filter(op->op.idempotencyKey().equals(fixture.deleteIdempotencyKey())).findFirst().orElse(null);
                lifecycle.delete(wallet.walletId(),prior==null?wallet.version():prior.expectedWalletVersion(),fixture.deleteIdempotencyKey(),true);
            }
            case "wallet-sign" -> {
                var wallet=lifecycle.query().getFirst();
                var capture=CustodyQaFixture.confirmed(wallet.signingKeyRef(),wallet.address(),CustodyQaFixture.anotherAddress(),"1500000","");
                if(capture.broadcasts()!=0) throw new AssertionError("BROADCAST_FORBIDDEN");
                confirmed.set(capture.request());
                var signed=lifecycle.sign(wallet.walletId(),wallet.version(),fixture.signIdempotencyKey(),capture.request(),true);
                emit("independentSignature",signed!=null);emit("broadcasts",capture.broadcasts());
            }
            case "wallet-reconcile" -> { }
            case "wallet-purge" -> {
                for(var wallet:store.current().walletCatalog().wallets()) {
                    var request=new CustodyClient.LifecycleRequest(wallet.binding(store.current().walletCatalog().catalogId()),WalletLifecycle.randomId(),WalletLifecycle.sha("QA_TEARDOWN"),wallet.version(),"","",0,"");
                    if(!client.lifecycle("purgeQaBound",request,null).status().equals("DELETED")) throw new IllegalStateException("QA_PURGE_FAILED");
                }
                if(client.lifecycle("inventoryPage",new CustodyClient.LifecycleRequest(null,WalletLifecycle.randomId(),WalletLifecycle.sha("QA_INVENTORY"),1,"","",0,""),null).lifecycle().path("totalCount").asInt(-1)!=0) throw new IllegalStateException("QA_ITEMS_REMAIN");
                secrets.delete(SecretId.WALLET_LIFECYCLE_TEST_ANCHOR);secrets.delete(SecretId.WALLET_LIFECYCLE_TEST_ENCRYPTION_KEY);
                emit("purged",true);return;
            }
            default -> throw new IllegalArgumentException("QA_MODE_UNKNOWN");
        }
        var catalog=store.current().walletCatalog();
        emit("walletCount",catalog.wallets().size());
        for(var wallet:catalog.wallets()) { emit("state",wallet.durableState());emit("health",lifecycle.health(wallet.walletId())); }
        emit("unknownSigns",catalog.operations().stream().filter(op->op.attemptState()==WalletCatalog.Attempt.SIGN_OUTCOME_UNKNOWN).count());
        emit("scalarCount",client.call("count","",null).count());
        emit("broadcasts",0);
    }
    private static void emit(String name,Object value) { System.out.println("wallet."+name+"="+value);System.out.flush(); }
    private static final java.util.Set<String> HELPER_POINTS=java.util.Set.of("afterPreparing","beforeScalar","afterScalar","beforeLive","afterLive","beforeRevoked","afterRevoked","beforeScalarDelete","afterScalarDelete","beforeAbsence","beforeSign","afterSign");
}
