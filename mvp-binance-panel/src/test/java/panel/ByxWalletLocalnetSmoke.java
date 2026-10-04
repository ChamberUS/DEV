package panel;

import java.nio.file.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import panel.model.WalletProof;
import panel.repository.ByxWalletRepository;
import panel.service.*;
import panel.adapter.CosmosByxChainGateway;

/** Manual DEV-only runner. Standalone signer owns keys; Java sees only public proofs. */
public final class ByxWalletLocalnetSmoke {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("user.home"),".byx-mvp-localnet-b-v1");
        var json=new ObjectMapper();var m=json.readTree(root.resolve("localnet.json").toFile());
        var config=ByxLocalnetSmoke.config(m,"alice-test");var auth=AuthFixture.ready();auth.seedAdmin();auth.auth.login("boss","correct-horse-1".toCharArray());
        var identity=new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,Clock.systemUTC());
        var challenge=identity.challenge(config.observedAddress());
        var process=new ProcessBuilder(root.resolve("bin/wallet-test-signer").toString());
        process.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");
        var child=process.start();try(var input=child.getOutputStream()){json.writeValue(input,challenge);}
        byte[] publicProof=child.getInputStream().readAllBytes();
        ByxLocalnetSmoke.check(child.waitFor(15,TimeUnit.SECONDS)&&child.exitValue()==0,"DEV signer failed");
        WalletProof proof=json.readValue(publicProof,WalletProof.class);
        var wallet=identity.verify(proof);ByxLocalnetSmoke.check(identity.verified(wallet.address()).isPresent(),"Link failed");
        try(var benefits=new ByxBenefitsService(identity,new CosmosByxChainGateway(Clock.systemUTC()),Clock.systemUTC(),ByxBenefitsService.defaults())) {
            var before=benefits.refresh(wallet.address()).get(25,TimeUnit.SECONDS);
            System.out.println("PROOF_VERIFIED before="+before.formattedBalance()+" tier="+before.tier());
            ByxLocalnetSmoke.check(before.tier().equals("HOLDER"),"Expected initial HOLDER");
            var transfer=new ProcessBuilder("python3","scripts/byx_wallet_tier_transfer.py").inheritIO().start();
            ByxLocalnetSmoke.check(transfer.waitFor(55,TimeUnit.SECONDS)&&transfer.exitValue()==0,"Test transfer failed");
            var after=benefits.refresh(wallet.address()).get(25,TimeUnit.SECONDS);
            ByxLocalnetSmoke.check(after.tier().equals("PLUS")&&after.benefitsEnabled(),"Tier must refresh to PLUS");
            ByxLocalnetSmoke.check(after.balanceUbyx().subtract(before.balanceUbyx()).toString().equals("2000000"),"Exact balance delta");
            identity.revoke(wallet.address());var revoked=benefits.snapshot(wallet.address());
            ByxLocalnetSmoke.check(!revoked.benefitsEnabled()&&revoked.tier().equals("FREE"),"Revocation must disable immediately");
            System.out.println("WALLET_LOCALNET_SMOKE_OK after="+after.formattedBalance()+" tier=PLUS revoked=FREE no_admin="+auth.sessions.admin().isEmpty());
        }
        auth.db.close();
    }
}
