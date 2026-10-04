package panel;

import java.nio.file.Path;
import java.time.Clock;
import java.math.BigInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import panel.adapter.*;
import panel.repository.*;
import panel.service.*;

/** Read-only LOCALNET TEST treasury reconciliation; no signing or broadcast. */
public final class ByxTreasuryLocalnetSmoke {
    public static void main(String[] args) throws Exception {
        var root=Path.of(System.getProperty("user.home"),".byx-mvp-localnet-b-v1");
        var manifest=new ObjectMapper().readTree(root.resolve("localnet.json").toFile());
        var config=ByxLocalnetSmoke.config(manifest,"alice-test");var clock=Clock.systemUTC();
        var auth=AuthFixture.ready();auth.seedAdmin();auth.auth.login("boss","correct-horse-1".toCharArray());
        var identity=new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,clock);
        var policy=GasSponsorshipPolicy.load();var chain=new CosmosByxChainGateway(clock);
        try(var db=panel.security.Database.open(root.resolve("evidence/gas-journal.db"))) {
            var journal=new GasGrantRepository(db);
            var snapshot=new TreasuryService(identity,chain,new CosmosGasGrantGateway(clock),journal,()->policy,clock).refresh();
            ByxLocalnetSmoke.check(snapshot.realAssets().equals("NONE / NOT CONFIGURED"),"No real assets");
            ByxLocalnetSmoke.check(snapshot.activeGrants()==0,"Smoke grant revoked");
            ByxLocalnetSmoke.check(snapshot.observedConsumptionUbyx().equals(BigInteger.valueOf(10000)),"Exact observed gas consumption");
            ByxLocalnetSmoke.check(snapshot.verifiedBalances().size()==1 && snapshot.assets().getFirst().testOnly(),"One TEST allocation; no duplicated cash/BYX total");
            ByxLocalnetSmoke.check(snapshot.assets().getFirst().balance().equals(BigInteger.valueOf(170000)),"Native sponsor balance reconciled");
            System.out.println("TREASURY_LOCALNET_SMOKE_OK "+snapshot.assets().getFirst().formatted()+"; 0 active; 10000ubyx observed; REAL ASSETS NONE");
        }auth.db.close();
    }
}
