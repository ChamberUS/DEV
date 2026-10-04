package panel;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import panel.adapter.*;
import panel.model.*;
import panel.repository.*;
import panel.service.*;
import panel.security.AccessDeniedException;

class GasSponsorshipTest {
    ByxWalletOwnershipTest f; GasSponsorshipService service; GasGrantRepository repo;
    GasSponsorshipPolicy policy; Fake gateway; String address;
    class Fake implements ByxGasGrantGateway {
        GasGrantSnapshot grant; int writes,revokes;
        public Optional<GasGrantSnapshot> read(ByxConfig c,GasSponsorshipPolicy p,String a){return Optional.ofNullable(grant);}
        public String grant(ByxConfig c,GasSponsorshipPolicy p,String a,BigInteger limit,Instant expiry){writes++;grant=new GasGrantSnapshot(p.granter(),a,"ubyx",limit,limit,expiry,"ACTIVE","abc",f.auth.clock.instant());return "abc";}
        public String revoke(ByxConfig c,GasSponsorshipPolicy p,String a){revokes++;grant=null;return "def";}
    }
    @BeforeEach void setup() throws Exception {
        f=new ByxWalletOwnershipTest();f.setup();
        f.config=new ByxConfig(f.config.endpoint(),f.config.rpcEndpoint(),"LOCALNET","byx-mvp-localnet-b-fixture",f.config.genesisFingerprint(),"ubyx","BYX",6,"BANK_METADATA","");
        address=f.keys.address();f.identity.verify(f.valid());gateway=new Fake();repo=new GasGrantRepository(f.auth.db);
        policy=new GasSponsorshipPolicy(new ByxWalletOwnershipTest.TestKeyPair().address(),f.config.expectedChainId(),f.config.genesisFingerprint(),Map.of("FREE",BigInteger.ZERO,"HOLDER",BigInteger.ZERO,"PLUS",BigInteger.valueOf(30000),"PRO",BigInteger.valueOf(60000)),300);
        service=new GasSponsorshipService(f.auth.sessions,f.identity,f.benefits,repo,gateway,()->policy,f.auth.clock);
    }
    @AfterEach void close(){f.close();}
    @Test void eligibleNativeQuotaAndIdempotentAcrossRestart() throws Exception {var g=service.request(address);assertEquals(BigInteger.valueOf(30000),g.remaining());assertEquals(g,service.request(address));assertEquals(1,gateway.writes);service=new GasSponsorshipService(f.auth.sessions,f.identity,f.benefits,repo,gateway,()->policy,f.auth.clock);service.request(address);assertEquals(1,gateway.writes);}
    @Test void freeAndHolderIneligible() {for(String amount:List.of("1","100000000")){f.amount=new BigInteger(amount);assertThrows(AccessDeniedException.class,()->service.request(address));}assertEquals(0,gateway.writes);}
    @Test void watchOnlyCannotGrant(){assertThrows(AccessDeniedException.class,()->service.request(new ByxWalletOwnershipTest.TestKeyPair().address()));}
    @Test void revokedWalletCannotGrant(){f.identity.revoke(address);assertThrows(AccessDeniedException.class,()->service.request(address));}
    @Test void wrongChain(){policy=new GasSponsorshipPolicy(policy.granter(),"byx-mvp-localnet-b-wrong",policy.genesis(),policy.quotas(),300);assertThrows(AccessDeniedException.class,()->service.request(address));}
    @Test void offlineAndStale(){f.offline=true;assertThrows(AccessDeniedException.class,()->service.request(address));f.offline=false;f.stale=true;assertThrows(AccessDeniedException.class,()->service.request(address));assertEquals(0,gateway.writes);}
    @Test void consumedQuotaNotReplenished() throws Exception {service.request(address);var g=gateway.grant;gateway.grant=new GasGrantSnapshot(g.granter(),address,"ubyx",g.spendLimit(),BigInteger.valueOf(20000),g.expiration(),"ACTIVE",g.txHash(),g.updatedAt());assertEquals(BigInteger.valueOf(10000),service.request(address).consumed());assertEquals(1,gateway.writes);}
    @Test void expiredGrantNotRenewed() throws Exception {service.request(address);f.auth.clock.advance(Duration.ofSeconds(301));gateway.grant=null;assertThrows(AccessDeniedException.class,()->service.request(address));assertEquals(1,gateway.writes);}
    @Test void revokeConfirmedAndNeverReplenished() throws Exception {service.request(address);service.revoke(address);assertEquals("REVOKED",service.refresh(address).state());assertThrows(AccessDeniedException.class,()->service.request(address));assertEquals(1,gateway.revokes);}
    @Test void downgradeRevokes() throws Exception {f.amount=new BigInteger("10000000000");service.request(address);f.amount=new BigInteger("1000000000");assertThrows(AccessDeniedException.class,()->service.request(address));assertEquals(1,gateway.revokes);assertEquals(1,gateway.writes);}
    @Test void unlinkRefreshRevokesEvenWithoutProof() throws Exception {service.request(address);f.identity.revoke(address);assertEquals("REVOKED",service.refresh(address).state());assertEquals(1,gateway.revokes);}
    @Test void otherUserCannotRevokeOrRecycleQuota() throws Exception {service.request(address);f.auth.sessions.logout();assertThrows(Exception.class,()->service.revoke(address));assertThrows(Exception.class,()->service.request(address));}
    @Test void infiniteOrOversizedPolicyRejected(){assertThrows(IllegalArgumentException.class,()->new GasSponsorshipPolicy(policy.granter(),policy.chainId(),policy.genesis(),Map.of("FREE",BigInteger.ONE,"HOLDER",BigInteger.ZERO,"PLUS",BigInteger.ONE,"PRO",BigInteger.TEN),300));assertThrows(IllegalArgumentException.class,()->new GasSponsorshipPolicy(policy.granter(),policy.chainId(),policy.genesis(),policy.quotas(),3601));}
    @Test void unexpectedAllowanceRejects() throws Exception {var j=new com.fasterxml.jackson.databind.ObjectMapper();assertThrows(IllegalArgumentException.class,()->CosmosGasGrantGateway.parse(j.readTree("{}"),policy.granter(),address,f.auth.clock.instant()));}
    @Test void nativeAllowanceFieldsAreStrictlyBounded() throws Exception {
        var j=new com.fasterxml.jackson.databind.ObjectMapper();
        var node=j.createObjectNode();node.put("granter",policy.granter());node.put("grantee",address);
        var a=node.putObject("allowance");a.put("@type","/cosmos.feegrant.v1beta1.AllowedMsgAllowance");a.putArray("allowed_messages").add("/cosmos.bank.v1beta1.MsgSend");
        var basic=a.putObject("allowance");basic.put("@type","/cosmos.feegrant.v1beta1.BasicAllowance");basic.put("expiration",f.auth.clock.instant().plusSeconds(300).toString());
        var coin=basic.putArray("spend_limit").addObject();coin.put("denom","ubyx");coin.put("amount","30000");
        assertEquals(BigInteger.valueOf(30000),CosmosGasGrantGateway.parse(node,policy.granter(),address,f.auth.clock.instant()).remaining());
        for(String denom:List.of("byx","usdc")){coin.put("denom",denom);assertThrows(IllegalArgumentException.class,()->CosmosGasGrantGateway.parse(node,policy.granter(),address,f.auth.clock.instant()));}coin.put("denom","ubyx");
        coin.put("amount","1000001");assertThrows(IllegalArgumentException.class,()->CosmosGasGrantGateway.parse(node,policy.granter(),address,f.auth.clock.instant()));coin.put("amount","30000");
        basic.remove("expiration");assertThrows(Exception.class,()->CosmosGasGrantGateway.parse(node,policy.granter(),address,f.auth.clock.instant()));
    }
    @Test void changedUserCannotAccessOwnGrant() throws Exception {
        service.request(address);f.auth.authorize();
        f.auth.userService.createUser("gas-user","gas@example.invalid","temporary-pass-1".toCharArray(),null,panel.security.Role.USER);
        f.auth.auth.logout();var user=f.auth.auth.login("gas-user","temporary-pass-1".toCharArray());
        f.auth.userService.changeOwnPassword(user.id(),"temporary-pass-1".toCharArray(),"gas-user-new-pass-2".toCharArray());
        f.auth.auth.login("gas-user","gas-user-new-pass-2".toCharArray());
        f.identity.verify(f.valid());
        assertThrows(AccessDeniedException.class,()->service.revoke(address));
        assertThrows(AccessDeniedException.class,()->service.request(address));
        assertEquals(1,gateway.writes);assertEquals(0,gateway.revokes);
    }
    @Test void overRemainingOrChangedGranterRejected() throws Exception {
        service.request(address);var g=gateway.grant;
        gateway.grant=new GasGrantSnapshot(g.granter(),address,"ubyx",g.spendLimit(),g.spendLimit().add(BigInteger.ONE),g.expiration(),"ACTIVE","",g.updatedAt());
        assertThrows(AccessDeniedException.class,()->service.request(address));
    }
    @Test void productionSignerCannotActivate(){assertThrows(IllegalStateException.class,()->new LocalnetGasTestSigner(f.auth.clock,false,java.nio.file.Path.of("scripts/byx_gas_test.py")));}
}
