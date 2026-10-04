package panel;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.math.BigInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import panel.model.*;
import panel.repository.*;
import panel.service.*;
import panel.adapter.*;

/** Explicit real LOCALNET TEST smoke; not discovered by JUnit or shipped in the application. */
public final class ByxGasLocalnetSmoke {
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper();Path root=Path.of(System.getProperty("user.home"),".byx-mvp-localnet-b-v1");
        var manifest=json.readTree(root.resolve("localnet.json").toFile());
        var config=ByxLocalnetSmoke.config(manifest,"alice-test");var clock=Clock.systemUTC();
        var auth=AuthFixture.ready();auth.seedAdmin();auth.auth.login("boss","correct-horse-1".toCharArray());
        var identity=new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,clock);
        var signer=new ProcessBuilder(root.resolve("bin/wallet-test-signer").toString());signer.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");
        var child=signer.start();try(var input=child.getOutputStream()){json.writeValue(input,identity.challenge(config.observedAddress()));}
        var proof=child.getInputStream().readAllBytes();ByxLocalnetSmoke.check(child.waitFor(15,TimeUnit.SECONDS)&&child.exitValue()==0,"TEST proof failed");
        identity.verify(json.readValue(proof,WalletProof.class));
        var gateway=new CosmosByxChainGateway(clock);var policy=GasSponsorshipPolicy.load();
        try(var benefits=new ByxBenefitsService(identity,gateway,clock,ByxBenefitsService.defaults())) {
            var gasGateway=new LocalnetGasTestSigner(clock,true,Path.of("scripts/byx_gas_test.py"));
            var journalDb=panel.security.Database.open(root.resolve("evidence/gas-journal.db"));
            var repo=new GasGrantRepository(journalDb);
            var service=new GasSponsorshipService(auth.sessions,identity,benefits,repo,gasGateway,()->policy,clock);
            var before=benefits.refresh(config.observedAddress()).get();ByxLocalnetSmoke.check(before.tier().equals("PLUS"),"PLUS TEST wallet required");
            var sponsorConfig=new ByxConfig(config.endpoint(),config.rpcEndpoint(),"LOCALNET",config.expectedChainId(),config.genesisFingerprint(),"ubyx","BYX",6,"BANK_METADATA",policy.granter());
            BigInteger sponsorBefore=gateway.read(sponsorConfig).balance();
            var grant=service.request(config.observedAddress());ByxLocalnetSmoke.check(grant.remaining().equals(policy.quotas().get("PLUS")),"Exact quota");
            ByxLocalnetSmoke.check(service.request(config.observedAddress()).remaining().equals(grant.remaining()),"Duplicate is idempotent");
            var payload=Map.of("grantee",config.observedAddress(),"granter",policy.granter(),"chainId",policy.chainId(),"genesis",policy.genesis());
            var send=send(json,payload,"send");var after=benefits.refresh(config.observedAddress()).get();
            var used=service.refresh(config.observedAddress());
            ByxLocalnetSmoke.check(before.balanceUbyx().subtract(after.balanceUbyx()).equals(BigInteger.ONE),"User pays only TEST amount");
            ByxLocalnetSmoke.check(grant.remaining().subtract(used.remaining()).equals(BigInteger.valueOf(10000)),"Native quota reduced");
            ByxLocalnetSmoke.check(sponsorBefore.subtract(gateway.read(sponsorConfig).balance()).equals(BigInteger.valueOf(20000)),"Sponsor pays grant + sponsored fee");
            identity.revoke(config.observedAddress());service.revoke(config.observedAddress());
            ByxLocalnetSmoke.check(gasGateway.read(config,policy,config.observedAddress()).isEmpty(),"Revoke confirmed on-chain");
            boolean rejected=false;try{send(json,payload,"send");}catch(IllegalStateException failure){rejected=true;}
            ByxLocalnetSmoke.check(rejected,"Later fee-granter transfer rejected");
            var normal=send(json,payload,"send-unsponsored");
            var finalBalance=gateway.read(config).balance();
            ByxLocalnetSmoke.check(after.balanceUbyx().subtract(finalBalance).equals(BigInteger.valueOf(10001)),"Unsponsored sender pays amount + gas");
            ByxLocalnetSmoke.check(sponsorBefore.subtract(gateway.read(sponsorConfig).balance()).equals(BigInteger.valueOf(30000)),"Sponsor final: grant, fee, revoke only");
            var evidence=new LinkedHashMap<String,Object>();evidence.put("test_only",true);evidence.put("chain_id",policy.chainId());evidence.put("granter",policy.granter());evidence.put("wallet",config.observedAddress());evidence.put("tier","PLUS");evidence.put("grant_tx",grant.txHash());evidence.put("sponsored_tx",send);evidence.put("revoke_tx",repo.all().getFirst().txHash());evidence.put("unsponsored_tx",normal);evidence.put("quota_ubyx",grant.spendLimit().toString());evidence.put("remaining_after_use",used.remaining().toString());evidence.put("user_before",before.balanceUbyx().toString());evidence.put("user_final",finalBalance.toString());evidence.put("sponsor_before",sponsorBefore.toString());evidence.put("sponsor_final",gateway.read(sponsorConfig).balance().toString());evidence.put("timestamp",clock.instant().toString());evidence.put("smoke","PASS");
            Files.writeString(root.resolve("evidence/gas-smoke.json"),json.writeValueAsString(evidence));
            journalDb.close();System.out.println(json.writeValueAsString(evidence));
        }auth.db.close();
    }
    private static com.fasterxml.jackson.databind.JsonNode send(ObjectMapper json,Map<String,String> p,String action)throws Exception {
        var builder=new ProcessBuilder("python3","scripts/byx_gas_test.py",action).redirectError(ProcessBuilder.Redirect.DISCARD);builder.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");var child=builder.start();
        try(var input=child.getOutputStream()){json.writeValue(input,p);}
        if(!child.waitFor(40,TimeUnit.SECONDS)){child.destroy();throw new IllegalStateException("TEST tx timeout");}
        if(child.exitValue()!=0)throw new IllegalStateException("TEST transfer rejected");return json.readTree(child.getInputStream());
    }
}
