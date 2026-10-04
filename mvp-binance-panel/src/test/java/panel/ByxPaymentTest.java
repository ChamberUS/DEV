package panel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.google.protobuf.CodedOutputStream;
import panel.model.*;
import panel.service.*;
import panel.adapter.*;
import panel.repository.*;
import panel.security.AccessDeniedException;

class ByxPaymentTest {
    final ByxWalletOwnershipTest f=new ByxWalletOwnershipTest();
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    ByxPaymentService payments;
    EntitlementService entitlements;
    ByxPaymentPolicy policy;
    PaymentIntent intent;
    String hash;
    boolean offline;
    Map<String,Object> responses=new HashMap<>();
    String chain="byx-mvp-localnet-b-fixture";
    @BeforeEach void setup()throws Exception {
        f.setup();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            byte[] bytes=json.writeValueAsBytes(responses.getOrDefault(e.getRequestURI().getPath(),Map.of()));
            e.sendResponseHeaders(offline?503:200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        var genesis=json.createObjectNode().put("chain_id",chain);
        URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        f.config=new ByxConfig(origin,origin,"LOCALNET",chain,CosmosByxChainGateway.fingerprint(genesis),"ubyx","BYX",6,"BANK_METADATA","");
        f.identity.verify(f.valid());
        policy=new ByxPaymentPolicy(new ByxWalletOwnershipTest.TestKeyPair().address(),chain,f.config.genesisFingerprint(),BigInteger.valueOf(10000),300,10);
        payments=new ByxPaymentService(f.auth.sessions,f.identity,new ByxPaymentRepository(f.auth.db),new CosmosByxPaymentVerifier(f.auth.clock),f.auth.clock,()->policy);
        entitlements=new EntitlementService(f.benefits,payments);
        responses.put("/cosmos/base/tendermint/v1beta1/node_info",Map.of("default_node_info",Map.of("network",chain,"default_node_id","testnode")));
        responses.put("/status",Map.of("result",Map.of("node_info",Map.of("network",chain,"id","testnode"))));
        responses.put("/genesis",Map.of("result",Map.of("genesis",genesis)));
        responses.put("/cosmos/bank/v1beta1/denoms_metadata/ubyx",Map.of("metadata",Map.of("base","ubyx","display","BYX","denom_units",List.of(Map.of("denom","ubyx","exponent",0),Map.of("denom","BYX","exponent",6)))));
        responses.put("/cosmos/base/tendermint/v1beta1/blocks/latest",Map.of("block",Map.of("header",Map.of("chain_id",chain,"height","55","time",f.auth.clock.instant().toString()))));
        responses.put("/cosmos/base/tendermint/v1beta1/syncing",Map.of("syncing",false));
        intent=payments.create(f.keys.address());
    }
    @AfterEach void close(){server.stop(0);f.close();}
    static byte[] proto(Object... values)throws Exception {
        var output=new java.io.ByteArrayOutputStream();var coded=CodedOutputStream.newInstance(output);
        for(int i=0;i<values.length;i+=2)coded.writeByteArray((Integer)values[i],values[i+1] instanceof String s?s.getBytes(java.nio.charset.StandardCharsets.UTF_8):(byte[])values[i+1]);
        coded.flush();return output.toByteArray();
    }
    void transaction(String sender,String recipient,String amount,String denom,String memo,String blockChain)throws Exception {
        byte[] send=proto(1,sender,2,recipient,3,proto(1,denom,2,amount));
        byte[] raw=proto(1,proto(1,proto(1,"/cosmos.bank.v1beta1.MsgSend",2,send),2,memo));
        hash=HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        String encoded=Base64.getEncoder().encodeToString(raw);
        var message=Map.of("@type","/cosmos.bank.v1beta1.MsgSend","from_address",sender,"to_address",recipient,"amount",List.of(Map.of("denom",denom,"amount",amount)));
        responses.put("/cosmos/tx/v1beta1/txs/"+hash,Map.of("tx",Map.of("body",Map.of("memo",memo,"messages",List.of(message))),"tx_response",Map.of("txhash",hash,"height","55","code",0)));
        responses.put("/tx",Map.of("result",Map.of("hash",hash,"height","55","tx",encoded,"tx_result",Map.of("code",0))));
        responses.put("/block",Map.of("result",Map.of("block",Map.of("header",Map.of("chain_id",blockChain,"height","55","time",f.auth.clock.instant().toString()),"data",Map.of("txs",List.of(encoded))))));
    }
    void validTransaction()throws Exception{transaction(intent.verifiedWallet(),intent.recipient(),intent.amountUbyx().toString(),"ubyx",intent.reference(),chain);}
    @Test void validPaymentActivatesAndExpiresIndependentOfTier()throws Exception {
        f.amount=BigInteger.ZERO;f.benefits.refresh(f.keys.address()).get();
        assertFalse(entitlements.allows(f.keys.address(),"advanced_analytics"));
        validTransaction();var receipt=payments.confirm(intent.id(),hash);
        assertEquals(PaymentIntent.Status.CONSUMED,payments.get(intent.id()).status());
        assertEquals("FREE",f.benefits.snapshot(f.keys.address()).tier());
        assertTrue(entitlements.allows(f.keys.address(),"advanced_analytics"));
        assertFalse(entitlements.analyticsPreview(f.keys.address()).isEmpty());
        var entitlement=entitlements.snapshot(f.keys.address()).stream().filter(e->e.id().equals("advanced_analytics")).findFirst().orElseThrow();
        assertEquals("BYX_PAYMENT",entitlement.source());assertEquals(receipt.txHash(),entitlement.txHash());
        f.auth.clock.advance(Duration.ofSeconds(10));assertFalse(entitlements.allows(f.keys.address(),"advanced_analytics"));
        f.amount=new BigInteger("1000000000");f.benefits.refresh(f.keys.address()).get();
        assertTrue(entitlements.allows(f.keys.address(),"advanced_analytics"));
    }
    @Test void wrongSenderRejected()throws Exception{transaction(policy.recipient(),intent.recipient(),"10000","ubyx",intent.reference(),chain);assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void wrongRecipientRejected()throws Exception{transaction(intent.verifiedWallet(),intent.verifiedWallet(),"10000","ubyx",intent.reference(),chain);assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void wrongAmountAndPartialRejected()throws Exception{transaction(intent.verifiedWallet(),intent.recipient(),"9999","ubyx",intent.reference(),chain);assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));assertTrue(payments.receipts().isEmpty());}
    @Test void wrongDenomRejected()throws Exception{transaction(intent.verifiedWallet(),intent.recipient(),"10000","byx",intent.reference(),chain);assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void wrongChainRejected()throws Exception{transaction(intent.verifiedWallet(),intent.recipient(),"10000","ubyx",intent.reference(),"other");assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void wrongReferenceRejected()throws Exception{transaction(intent.verifiedWallet(),intent.recipient(),"10000","ubyx","other-intent",chain);assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void expiredIntentNeverAccepts()throws Exception{validTransaction();f.auth.clock.advance(Duration.ofSeconds(300));assertThrows(AccessDeniedException.class,()->payments.confirm(intent.id(),hash));assertEquals(PaymentIntent.Status.EXPIRED,payments.get(intent.id()).status());}
    @Test void watchOnlyAndUnverifiedCannotCreate(){assertThrows(AccessDeniedException.class,()->payments.create(policy.recipient()));f.identity.revoke(f.keys.address());assertThrows(AccessDeniedException.class,()->payments.create(f.keys.address()));}
    @Test void intentAndTransactionReplayCannotCreateSecondPass()throws Exception {
        validTransaction();payments.confirm(intent.id(),hash);
        assertThrows(AccessDeniedException.class,()->payments.confirm(intent.id(),hash));
        var second=payments.create(f.keys.address());
        assertThrows(IllegalArgumentException.class,()->payments.confirm(second.id(),hash));
        assertEquals(1,payments.receipts().size());
    }
    @Test void databasePreventsSameTxForTwoIntentsEvenIfVerifierMisbehaves()throws Exception {
        validTransaction();payments.confirm(intent.id(),hash);var second=payments.create(f.keys.address());
        var faulty=new ByxPaymentService(f.auth.sessions,f.identity,new ByxPaymentRepository(f.auth.db),
            (c,i,h,d)->new PaymentReceipt(i.id(),i.userId(),i.verifiedWallet(),i.chainId(),i.genesisFingerprint(),h,55,i.amountUbyx(),f.auth.clock.instant(),f.auth.clock.instant(),f.auth.clock.instant().plusSeconds(d)),f.auth.clock,()->policy);
        assertThrows(IllegalStateException.class,()->faulty.confirm(second.id(),hash));assertEquals(1,payments.receipts().size());assertEquals(PaymentIntent.Status.REJECTED,payments.get(second.id()).status());
    }
    @Test void revokeDisablesAccessWithoutFalsifyingReceipt()throws Exception{validTransaction();var receipt=payments.confirm(intent.id(),hash);f.identity.revoke(f.keys.address());assertTrue(payments.active(f.keys.address()).isEmpty());assertEquals(receipt,payments.receipts().get(0));}
    @Test void offlineNeverMarksPaidAndCanRetry()throws Exception{validTransaction();offline=true;assertThrows(java.io.IOException.class,()->payments.confirm(intent.id(),hash));assertEquals(PaymentIntent.Status.AWAITING_PAYMENT,payments.get(intent.id()).status());assertTrue(payments.receipts().isEmpty());offline=false;assertNotNull(payments.confirm(intent.id(),hash));}
    @Test void unconfirmedFailureIsNotPaid()throws Exception{validTransaction();responses.put("/tx",Map.of("result",Map.of("hash",hash,"height","55","tx_result",Map.of("code",5))));assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));assertTrue(payments.receipts().isEmpty());}
    @Test void rawHashCannotBeSubstituted()throws Exception{validTransaction();responses.put("/tx",Map.of("result",Map.of("hash",hash,"height","55","tx",Base64.getEncoder().encodeToString(new byte[]{1}),"tx_result",Map.of("code",0))));assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));}
    @Test void forbiddenCapabilitiesRemainDenied()throws Exception{validTransaction();payments.confirm(intent.id(),hash);for(String id:List.of("ADMIN","VALIDATION","FINAL_HOLDOUT","live_trading","strategy_execution"))assertFalse(entitlements.allows(f.keys.address(),id));}
    @Test void concurrentConfirmationConsumesOnce()throws Exception {
        validTransaction();
        var worker=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results=worker.invokeAll(List.of(
                ()->{try{payments.confirm(intent.id(),hash);return true;}catch(AccessDeniedException denied){return false;}},
                ()->{try{payments.confirm(intent.id(),hash);return true;}catch(AccessDeniedException denied){return false;}}));
            int successes=0;for(var result:results)if(Boolean.TRUE.equals(result.get()))successes++;
            assertEquals(1,successes);assertEquals(1,payments.receipts().size());
        }finally{worker.shutdownNow();}
    }
    @Test void previousCommitBlockTimeIsAcceptedWithinBound()throws Exception {
        validTransaction();var result=(Map<String,Object>)responses.get("/block");
        var block=(Map<String,Object>)((Map<String,Object>)result.get("result")).get("block");
        block=new HashMap<>(block);block.put("header",Map.of("chain_id",chain,"height","55","time",f.auth.clock.instant().minusSeconds(2).toString()));
        responses.put("/block",Map.of("result",Map.of("block",block)));
        assertNotNull(payments.confirm(intent.id(),hash));
    }
    @Test void oldBlockBeyondCreationToleranceIsRejected()throws Exception {
        validTransaction();var result=(Map<String,Object>)responses.get("/block");
        var block=new HashMap<>((Map<String,Object>)((Map<String,Object>)result.get("result")).get("block"));
        block.put("header",Map.of("chain_id",chain,"height","55","time",f.auth.clock.instant().minusSeconds(31).toString()));
        responses.put("/block",Map.of("result",Map.of("block",block)));
        assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));
    }
    @Test void decodedRestCannotHideWrongSignedRecipient()throws Exception {
        transaction(intent.verifiedWallet(),intent.verifiedWallet(),"10000","ubyx",intent.reference(),chain);
        var correct=Map.of("@type","/cosmos.bank.v1beta1.MsgSend","from_address",intent.verifiedWallet(),"to_address",intent.recipient(),"amount",List.of(Map.of("denom","ubyx","amount","10000")));
        responses.put("/cosmos/tx/v1beta1/txs/"+hash,Map.of("tx",Map.of("body",Map.of("memo",intent.reference(),"messages",List.of(correct))),"tx_response",Map.of("txhash",hash,"height","55","code",0)));
        assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));
    }
    @Test void confirmedHashMustActuallyBeIncludedInBlock()throws Exception {
        validTransaction();responses.put("/block",Map.of("result",Map.of("block",Map.of("header",Map.of("chain_id",chain,"height","55","time",f.auth.clock.instant().toString()),"data",Map.of("txs",List.of())))));
        assertThrows(IllegalArgumentException.class,()->payments.confirm(intent.id(),hash));
    }
    @Test void receiptSurvivesServiceRestartAndReplayStillRejected()throws Exception {
        validTransaction();var receipt=payments.confirm(intent.id(),hash);
        var restored=new ByxPaymentService(f.auth.sessions,f.identity,new ByxPaymentRepository(f.auth.db),new CosmosByxPaymentVerifier(f.auth.clock),f.auth.clock,()->policy);
        assertEquals(receipt,restored.active(f.keys.address()).orElseThrow());
        assertThrows(AccessDeniedException.class,()->restored.confirm(intent.id(),hash));
    }
    @Test void anotherAuthenticatedUserCannotReadOrConfirmIntent()throws Exception {
        validTransaction();f.auth.authorize();
        var other=f.auth.userService.createUser("payment-other","other@example.invalid","temporary-pay-1".toCharArray(),null,panel.security.Role.USER);
        f.auth.sessions.login(other,f.auth.clock.instant());
        f.auth.userService.changeOwnPassword(other.id(),"temporary-pay-1".toCharArray(),"different-pay-2".toCharArray());
        f.auth.auth.login("payment-other","different-pay-2".toCharArray());
        assertThrows(AccessDeniedException.class,()->payments.get(intent.id()));
        assertThrows(AccessDeniedException.class,()->payments.confirm(intent.id(),hash));
        assertTrue(payments.receipts().isEmpty());
    }
}
