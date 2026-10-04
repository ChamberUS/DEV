package panel.adapter;

import java.nio.file.*;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import panel.model.ByxConfig;
import panel.service.GasSponsorshipPolicy;

/** Explicit DEV opt-in only; the SDK signs in its external TEST keyring. */
public final class LocalnetGasTestSigner extends CosmosGasGrantGateway {
    private final Path script;
    public LocalnetGasTestSigner(Clock clock, boolean devMode, Path script) {
        super(clock);
        if (!devMode || !"I_ACKNOWLEDGE_TEST_ONLY".equals(System.getenv("BYX_LOCALNET_TEST_SIGNER")))
            throw new IllegalStateException("DEV/LOCALNET TEST signer disabled");
        this.script=script.toAbsolutePath();
    }
    private String write(String action, ByxConfig c, GasSponsorshipPolicy p, String address, BigInteger limit, Instant expiration) throws Exception {
        p.matches(c); read(c,p,address);
        var payload=new LinkedHashMap<String,String>(); payload.put("grantee",address); payload.put("granter",p.granter());
        payload.put("chainId",p.chainId()); payload.put("genesis",p.genesis());
        if (limit!=null) { payload.put("limit",limit.toString()); payload.put("expiration",expiration.toString()); }
        var child=new ProcessBuilder("python3",script.toString(),action).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=child.getOutputStream()) { new ObjectMapper().writeValue(input,payload); }
        if (!child.waitFor(45,TimeUnit.SECONDS)) { child.destroy(); throw new java.io.IOException("TEST signer timeout; journal remains claimed"); }
        if (child.exitValue()!=0) throw new java.io.IOException("TEST signing failed; no automatic rebroadcast");
        return new ObjectMapper().readTree(child.getInputStream()).path("tx_hash").asText();
    }
    @Override public String grant(ByxConfig c, GasSponsorshipPolicy p, String a, BigInteger limit, Instant expires) throws Exception { return write("grant",c,p,a,limit,expires); }
    @Override public String revoke(ByxConfig c, GasSponsorshipPolicy p, String a) throws Exception { return write("revoke",c,p,a,null,null); }
}
