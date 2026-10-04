package panel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.math.BigInteger;
import java.util.List;
import panel.model.*;
import panel.model.TreasuryAsset.*;

class TreasuryTest {
    final Instant now=Instant.parse("2026-10-04T02:00:00Z");
    TreasuryAsset asset(Category category,String name,String base,BigInteger amount,int decimals,Source source,Verification status,boolean test) {
        return new TreasuryAsset(category,name,"explicit-custodian-network",base,amount,decimals,source,now,status,test);
    }
    TreasurySnapshot snapshot(TreasuryAsset... assets) {return new TreasurySnapshot(List.of(assets),"NONE / NOT CONFIGURED","ONLINE/FRESH",BigInteger.ZERO,0,BigInteger.ZERO,"Observed",now);}
    @Test void sourcesAndCategoriesRemainSeparate() {
        var s=snapshot(asset(Category.BYX_HOLDINGS,"BYX","ubyx",BigInteger.ONE,6,Source.ON_CHAIN,Verification.VERIFIED,true),asset(Category.STABLECOIN_RESERVE,"USDC","micro-usdc",BigInteger.TEN,6,Source.EXTERNAL_CUSTODY,Verification.VERIFIED,false),asset(Category.GAS_SPONSORSHIP_BUDGET,"BYX","ubyx",BigInteger.TEN,6,Source.ON_CHAIN,Verification.VERIFIED,true));
        assertEquals(3,s.verifiedBalances().size());assertEquals("NONE / NOT CONFIGURED",s.realAssets());
    }
    @Test void manualAndPaperNeverVerified(){for(var source:List.of(Source.MANUAL_UNVERIFIED,Source.PAPER)) {
        assertThrows(IllegalArgumentException.class,()->asset(Category.OPERATING_CASH,"USD","cents",BigInteger.TEN,2,source,Verification.VERIFIED,false));
        assertTrue(snapshot(asset(Category.OPERATING_CASH,"USD","cents",BigInteger.TEN,2,source,Verification.UNVERIFIED,false)).verifiedBalances().isEmpty());
    }}
    @Test void exactArithmeticNoFakeFx(){var n=new BigInteger("9007199254740993123456789");var a=asset(Category.BYX_HOLDINGS,"BYX","ubyx",n,6,Source.ON_CHAIN,Verification.VERIFIED,true);assertEquals("9007199254740993123.456789 BYX",a.formatted());var s=snapshot(a);assertEquals(n,s.verifiedBalances().values().iterator().next());assertTrue(s.verifiedBalances().keySet().stream().noneMatch(u->u.asset().equals("USD")));}
    @Test void staleBalanceExcluded(){assertTrue(snapshot(asset(Category.BYX_HOLDINGS,"BYX","ubyx",BigInteger.TEN,6,Source.ON_CHAIN,Verification.STALE,true)).verifiedBalances().isEmpty());}
    @Test void futureAndOldEvidenceExcluded(){var a=asset(Category.BYX_HOLDINGS,"BYX","ubyx",BigInteger.TEN,6,Source.ON_CHAIN,Verification.VERIFIED,true);assertTrue(new TreasurySnapshot(List.of(a),"NONE","STALE",BigInteger.ZERO,0,BigInteger.ZERO,"",now.plusSeconds(61)).verifiedBalances().isEmpty());}
}
