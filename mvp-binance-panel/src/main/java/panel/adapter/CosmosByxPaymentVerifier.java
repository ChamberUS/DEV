package panel.adapter;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import panel.model.*;

/** Checks a confirmed bank transfer against the audited local REST/RPC node. No signing. */
public final class CosmosByxPaymentVerifier implements ByxPaymentVerifier {
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper();
    public CosmosByxPaymentVerifier(Clock clock){this.clock=clock;}
    private JsonNode get(URI origin,String path) throws Exception {
        var response=http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new java.io.IOException("Payment confirmation unavailable");
        return json.readTree(response.body());
    }
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("Transaction does not match TEST intent");}
    private static Map<Integer,List<byte[]>> fields(byte[] data) throws java.io.IOException {
        require(data.length <= 65536);
        var in=com.google.protobuf.CodedInputStream.newInstance(data);
        Map<Integer,List<byte[]>> result=new HashMap<>();
        for(int tag;(tag=in.readTag())!=0;) {
            require((tag & 7)==2);
            result.computeIfAbsent(tag >>> 3,k->new ArrayList<>()).add(in.readByteArray());
        }
        return result;
    }
    private static byte[] one(Map<Integer,List<byte[]>> fields,int field) {
        require(fields.containsKey(field)&&fields.get(field).size()==1);
        return fields.get(field).get(0);
    }
    private static String text(Map<Integer,List<byte[]>> fields,int field) {
        return new String(one(fields,field),java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void checkSignedBody(byte[] raw,PaymentIntent i) throws Exception {
        var tx=fields(raw); var body=fields(one(tx,1));
        require(body.keySet().equals(Set.of(1,2))&&text(body,2).equals(i.reference()));
        var any=fields(one(body,1));
        require(any.keySet().equals(Set.of(1,2))&&text(any,1).equals("/cosmos.bank.v1beta1.MsgSend"));
        var send=fields(one(any,2));
        require(send.keySet().equals(Set.of(1,2,3))&&text(send,1).equals(i.verifiedWallet())&&text(send,2).equals(i.recipient()));
        var coin=fields(one(send,3));String amount=text(coin,2);
        require(coin.keySet().equals(Set.of(1,2))&&text(coin,1).equals("ubyx")&&amount.matches("[0-9]{1,78}")
                &&new BigInteger(amount).equals(i.amountUbyx()));
    }
    public PaymentReceipt verify(ByxConfig c,PaymentIntent i,String hash,long passSeconds)throws Exception {
        require("LOCALNET".equals(c.environment())&&c.expectedChainId().equals(i.chainId())&&c.genesisFingerprint().equals(i.genesisFingerprint()));
        var snapshot=new CosmosByxChainGateway(clock).read(c);
        require("VERIFIED".equals(snapshot.identity()));
        if(!"ONLINE".equals(snapshot.connection())||!"FRESH".equals(snapshot.freshness())||!Boolean.FALSE.equals(snapshot.syncing()))
            throw new java.io.IOException("Chain offline, stale or syncing; payment not confirmed");
        var rest=get(c.endpoint(),"/cosmos/tx/v1beta1/txs/"+hash);
        var response=rest.path("tx_response");
        var rpc=get(c.rpcEndpoint(),"/tx?hash=0x"+hash+"&prove=false").path("result");
        require(hash.equalsIgnoreCase(response.path("txhash").asText())&&hash.equalsIgnoreCase(rpc.path("hash").asText())
                &&response.path("code").asInt(-1)==0&&rpc.path("tx_result").path("code").asInt(-1)==0);
        String height=rpc.path("height").asText();
        require(height.matches("[1-9][0-9]*")&&height.equals(response.path("height").asText()));
        byte[] raw=Base64.getDecoder().decode(rpc.path("tx").asText());
        require(hash.equalsIgnoreCase(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))));
        checkSignedBody(raw,i);
        var block=get(c.rpcEndpoint(),"/block?height="+height).path("result").path("block");
        var header=block.path("header");
        require(i.chainId().equals(header.path("chain_id").asText())&&height.equals(header.path("height").asText()));
        boolean included=false;
        for(var tx:block.path("data").path("txs"))if(tx.asText().equals(rpc.path("tx").asText()))included=true;
        require(included);
        var body=rest.path("tx").path("body");
        require(body.path("memo").asText().equals(i.reference())&&body.path("messages").size()==1);
        var send=body.path("messages").get(0);
        require("/cosmos.bank.v1beta1.MsgSend".equals(send.path("@type").asText())
                &&i.verifiedWallet().equals(send.path("from_address").asText())&&i.recipient().equals(send.path("to_address").asText())
                &&send.path("amount").size()==1);
        var coin=send.path("amount").get(0);String amount=coin.path("amount").asText();
        require("ubyx".equals(coin.path("denom").asText())&&amount.matches("[0-9]{1,78}")&&i.amountUbyx().equals(new BigInteger(amount)));
        Instant now=clock.instant(),confirmed=Instant.parse(header.path("time").asText());
        require(!now.isBefore(i.createdAt())&&now.isBefore(i.expiresAt())&&!confirmed.isBefore(i.createdAt().minusSeconds(30))
                &&confirmed.isBefore(i.expiresAt())&&!confirmed.isAfter(now.plusSeconds(5)));
        return new PaymentReceipt(i.id(),i.userId(),i.verifiedWallet(),i.chainId(),i.genesisFingerprint(),hash,
                Long.parseLong(height),i.amountUbyx(),confirmed,now,now.plusSeconds(passSeconds));
    }
}
