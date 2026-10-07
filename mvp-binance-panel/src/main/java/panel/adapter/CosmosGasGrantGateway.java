package panel.adapter;

import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import panel.model.*;
import panel.service.GasSponsorshipPolicy;

/** Native SDK AllowedMsgAllowance(BasicAllowance), limited to MsgSend. Read-only by default. */
public class CosmosGasGrantGateway implements ByxGasGrantGateway {
    private final Clock clock;
    /** Criado no primeiro uso: montar o HttpClient (TLS) custa ~100 ms e estes adaptadores não são usados antes do login. */
    private volatile HttpClient client;
    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
                }
            }
        }
        return c;
    }

    private final ObjectMapper json=new ObjectMapper();
    public CosmosGasGrantGateway(Clock clock) { this.clock=clock; }
    private void trust(ByxConfig c, GasSponsorshipPolicy p) throws Exception {
        p.matches(c); var s=new CosmosByxChainGateway(clock).read(c); var now=clock.instant();
        if (!"ONLINE".equals(s.connection()) || !"VERIFIED".equals(s.identity()) || !"FRESH".equals(s.freshness())
                || !Boolean.FALSE.equals(s.syncing()) || !p.chainId().equals(s.chainId()) || s.blockTime()==null
                || s.updatedAt()==null || s.updatedAt().isBefore(now.minusSeconds(60)) || s.blockTime().isBefore(now.minusSeconds(60))
                || s.blockTime().isAfter(now.plusSeconds(30))) throw new java.io.IOException("Fresh LOCALNET identity required");
    }
    public Optional<GasGrantSnapshot> read(ByxConfig c, GasSponsorshipPolicy p, String address) throws Exception {
        trust(c,p);
        if (!address.matches("byx1[023456789acdefghjklmnpqrstuvwxyz]{38}")) throw new IllegalArgumentException("Invalid grantee");
        var uri=c.endpoint().resolve("/cosmos/feegrant/v1beta1/allowance/"+p.granter()+"/"+address);
        var response=client().send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()==404) return Optional.empty();
        if (response.statusCode()==500 && response.body().length()<1024) {
            var error=json.readTree(response.body());
            if(error.path("code").asInt()==13 && "fee-grant not found: not found".equals(error.path("message").asText())) return Optional.empty();
        }
        if (response.statusCode()!=200 || response.body().length()>65536) throw new java.io.IOException("Allowance unavailable");
        return Optional.of(parse(json.readTree(response.body()).path("allowance"),p.granter(),address,clock.instant()));
    }
    public static GasGrantSnapshot parse(JsonNode grant,String granter,String grantee,Instant now) {
        var a=grant.path("allowance"); var basic=a.path("allowance"); var coins=basic.path("spend_limit");
        if (!granter.equals(grant.path("granter").asText()) || !grantee.equals(grant.path("grantee").asText())
                || !"/cosmos.feegrant.v1beta1.AllowedMsgAllowance".equals(a.path("@type").asText())
                || a.path("allowed_messages").size()!=1 || !"/cosmos.bank.v1beta1.MsgSend".equals(a.path("allowed_messages").path(0).asText())
                || !"/cosmos.feegrant.v1beta1.BasicAllowance".equals(basic.path("@type").asText())
                || coins.size()!=1 || !"ubyx".equals(coins.path(0).path("denom").asText())
                || !coins.path(0).path("amount").asText().matches("[0-9]+")) throw new IllegalArgumentException("Unexpected/unbounded allowance");
        BigInteger amount=new BigInteger(coins.path(0).path("amount").asText());
        if (amount.compareTo(BigInteger.valueOf(1000000))>0) throw new IllegalArgumentException("TEST limit exceeded");
        Instant expires=Instant.parse(basic.path("expiration").asText());
        if (expires.isAfter(now.plusSeconds(3605))) throw new IllegalArgumentException("TEST expiration exceeded");
        return new GasGrantSnapshot(granter,grantee,"ubyx",amount,amount,expires,expires.isAfter(now)?"ACTIVE":"EXPIRED","",now);
    }
    public String grant(ByxConfig c, GasSponsorshipPolicy p, String address, BigInteger amount, Instant expiration) throws Exception { throw new UnsupportedOperationException("DEV TEST signer disabled"); }
    public String revoke(ByxConfig c, GasSponsorshipPolicy p, String address) throws Exception { throw new UnsupportedOperationException("DEV TEST signer disabled"); }
}
