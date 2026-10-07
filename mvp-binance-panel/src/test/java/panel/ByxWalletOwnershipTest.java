package panel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.math.BigInteger;
import java.net.URI;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.util.BigIntegers;
import panel.model.*;
import panel.service.*;
import panel.repository.ByxWalletRepository;
import panel.security.*;
import panel.adapter.ByxChainGateway;

class ByxWalletOwnershipTest {
    final AuthFixture auth = AuthFixture.ready();
    final TestKeyPair keys = new TestKeyPair();
    final FakeServerAuthorizer authorizer = new FakeServerAuthorizer();
    ByxConfig config;
    ByxWalletIdentityService identity;
    ByxBenefitsService benefits;
    BigInteger amount = new BigInteger("1000000000");
    boolean offline, stale;
    static class TestKeyPair {
        final ECPrivateKeyParameters privateKey;
        final byte[] publicKey;
        TestKeyPair() {
            var c = CustomNamedCurves.getByName("secp256k1");
            var gen = new ECKeyPairGenerator(); gen.init(new ECKeyGenerationParameters(new ECDomainParameters(c.getCurve(),c.getG(),c.getN(),c.getH()),new SecureRandom()));
            var pair = gen.generateKeyPair(); privateKey = (ECPrivateKeyParameters) pair.getPrivate();
            publicKey = ((ECPublicKeyParameters) pair.getPublic()).getQ().getEncoded(true);
        }
        WalletProof sign(WalletChallenge c) throws Exception {
            var signer = new ECDSASigner(); signer.init(true,privateKey);
            var rs = signer.generateSignature(MessageDigest.getInstance("SHA-256").digest(CosmosWalletProof.signBytes(c)));
            var n=privateKey.getParameters().getN();if(rs[1].compareTo(n.shiftRight(1))>0)rs[1]=n.subtract(rs[1]);
            byte[] sig = new byte[64];System.arraycopy(BigIntegers.asUnsignedByteArray(32,rs[0]),0,sig,0,32);System.arraycopy(BigIntegers.asUnsignedByteArray(32,rs[1]),0,sig,32,32);
            return new WalletProof(c,Base64.getEncoder().encodeToString(publicKey),Base64.getEncoder().encodeToString(sig));
        }
        String address() { return CosmosWalletProof.address(publicKey); }
    }
    @BeforeEach void setup() {
        auth.seedAdmin(); auth.auth.login("boss","correct-horse-1".toCharArray());
        config = new ByxConfig(URI.create("http://127.0.0.1:1417"),URI.create("http://127.0.0.1:27657"),"LOCALNET","test-chain","a".repeat(64),"ubyx","BYX",6,"BANK_METADATA","");
        identity = new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,auth.clock,authorizer);
        benefits = new ByxBenefitsService(identity,new ByxChainGateway() {
            public String source(){return "LIVE_NODE";}
            public ByxSnapshot read(ByxConfig c) throws Exception {
                if(offline)throw new java.io.IOException("Fixture offline");
                return new ByxSnapshot("LIVE_NODE","LOCALNET","ONLINE","VERIFIED",stale?"STALE":"FRESH",false,c.expectedChainId(),"42",
                    auth.clock.instant(),c.observedAddress(),amount,"BYX",6,auth.clock.instant(),"Fixture");
            }
        },auth.clock,ByxBenefitsService.defaults());
    }
    @AfterEach void close(){benefits.close();auth.db.close();}
    WalletProof valid() throws Exception{return keys.sign(identity.challenge(keys.address()));}
    @Test void validProofPersistsPublicDataAndLinksMultipleWallets() throws Exception {
        var w=identity.verify(valid());assertTrue(w.validAt(auth.clock.instant()));
        var second=new TestKeyPair();identity.verify(second.sign(identity.challenge(second.address())));
        assertEquals(2,identity.wallets().size());assertEquals(auth.sessions.user().orElseThrow().user().id(),w.userId());
        assertEquals("sign/MsgSignData",new com.fasterxml.jackson.databind.ObjectMapper().readTree(CosmosWalletProof.signBytes(identity.challenge(keys.address()))).path("msgs").get(0).path("type").asText());
    }
    @Test void wrongSignatureRejected() throws Exception {
        var p=valid();assertThrows(AccessDeniedException.class,()->identity.verify(new WalletProof(p.challenge(),p.publicKey(),Base64.getEncoder().encodeToString(new byte[64]))));
    }
    @Test void highSMalleableSignatureRejected() throws Exception {
        var proof=valid();var signature=Base64.getDecoder().decode(proof.signature());
        var n=CustomNamedCurves.getByName("secp256k1").getN();
        var s=new BigInteger(1,Arrays.copyOfRange(signature,32,64));
        System.arraycopy(BigIntegers.asUnsignedByteArray(32,n.subtract(s)),0,signature,32,32);
        var altered=new WalletProof(proof.challenge(),proof.publicKey(),Base64.getEncoder().encodeToString(signature));
        assertThrows(AccessDeniedException.class,()->identity.verify(altered));
    }
    @Test void wrongPublicKeyRejected() throws Exception {
        var p=valid();var other=new TestKeyPair();assertThrows(AccessDeniedException.class,()->identity.verify(new WalletProof(p.challenge(),Base64.getEncoder().encodeToString(other.publicKey),p.signature())));
    }
    @Test void addressMismatchRejectedEvenWithValidSignature() throws Exception {
        var other=new TestKeyPair();var p=keys.sign(identity.challenge(other.address()));assertThrows(AccessDeniedException.class,()->identity.verify(p));
    }
    WalletChallenge change(WalletChallenge c,String nonce,String chain,String context) {
        return new WalletChallenge(nonce,c.userId(),c.address(),chain,c.genesisFingerprint(),c.issuedAt(),c.expiresAt(),context);
    }
    @Test void changedNonceRejected() throws Exception {
        var p=valid();var altered=keys.sign(change(p.challenge(),"b".repeat(64),p.challenge().chainId(),p.challenge().context()));
        assertThrows(AccessDeniedException.class,()->identity.verify(altered));
    }
    @Test void wrongChainRejected() throws Exception {
        var p=valid();var altered=keys.sign(change(p.challenge(),p.challenge().nonce(),"other-chain",p.challenge().context()));
        assertThrows(AccessDeniedException.class,()->identity.verify(altered));
    }
    @Test void wrongContextRejected() throws Exception {
        var p=valid();var altered=keys.sign(change(p.challenge(),p.challenge().nonce(),p.challenge().chainId(),"other-domain"));
        assertThrows(AccessDeniedException.class,()->identity.verify(altered));
    }
    @Test void wrongUserOrNewLoginCannotUseOldChallenge() throws Exception {
        var p=valid();auth.authorize();auth.createUser("other","other@example.invalid","temporary-pass-1".toCharArray(),null,Role.USER);
        var other=auth.auth.login("other","temporary-pass-1".toCharArray());auth.userService.changeOwnPassword(other.id(),"temporary-pass-1".toCharArray(),"different-pass-2".toCharArray());auth.auth.login("other","different-pass-2".toCharArray());assertThrows(AccessDeniedException.class,()->identity.verify(p));
    }
    @Test void expiredChallengeRejectedAtBoundary() throws Exception {
        var p=valid();auth.clock.advance(Duration.ofSeconds(300));assertThrows(AccessDeniedException.class,()->identity.verify(p));
    }
    @Test void replayAndRestartRejected() throws Exception {
        var p=valid();identity.verify(p);assertThrows(AccessDeniedException.class,()->identity.verify(p));
        var restarted=new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,auth.clock,authorizer);
        assertThrows(AccessDeniedException.class,()->restarted.verify(p));assertEquals(1,restarted.wallets().size());
    }
    @Test void revokedWalletCannotUnlockAndOldPendingProofCannotRelink() throws Exception {
        identity.verify(valid());var pending=valid();identity.revoke(keys.address());
        assertThrows(AccessDeniedException.class,()->identity.verify(pending));assertTrue(identity.verified(keys.address()).isEmpty());
        assertFalse(benefits.refresh(keys.address()).get().benefitsEnabled());assertEquals("EXPIRED/REVOKED",benefits.snapshot(keys.address()).walletStatus());
        auth.clock.advance(Duration.ofSeconds(1));identity.verify(valid());assertTrue(identity.verified(keys.address()).isPresent());
    }
    @Test void watchOnlyCannotUnlockBenefits() throws Exception {
        assertEquals("WATCH-ONLY",benefits.refresh(keys.address()).get().walletStatus());assertFalse(benefits.snapshot(keys.address()).benefitsEnabled());
    }
    @Test void offlineStaleAndAgedCacheCannotEscalate() throws Exception {
        identity.verify(valid());assertEquals("PLUS",benefits.refresh(keys.address()).get().tier());
        var previous=benefits.snapshot(keys.address());
        amount=new BigInteger("9999999999999999999999");offline=true;var offlineSnapshot=benefits.refresh(keys.address()).get();
        assertEquals("FREE",offlineSnapshot.tier());assertEquals(previous.lastChainUpdate(),offlineSnapshot.lastChainUpdate());
        assertEquals(previous.balanceUbyx(),offlineSnapshot.balanceUbyx());
        offline=false;stale=true;assertEquals("FREE",benefits.refresh(keys.address()).get().tier());
        stale=false;assertEquals("PRO",benefits.refresh(keys.address()).get().tier());
        auth.clock.advance(Duration.ofSeconds(61));assertFalse(benefits.snapshot(keys.address()).benefitsEnabled());
    }
    @Test void exactUbyxBoundariesAndNoAdminPermissions() throws Exception {
        identity.verify(valid());amount=new BigInteger("999999999");assertEquals("HOLDER",benefits.refresh(keys.address()).get().tier());
        amount=amount.add(BigInteger.ONE);assertEquals("PLUS",benefits.refresh(keys.address()).get().tier());
        amount=new BigInteger("900719925474099312345678");assertEquals("900719925474099312.345678 BYX",benefits.refresh(keys.address()).get().formattedBalance());
        assertTrue(auth.sessions.admin().isEmpty());assertThrows(AccessDeniedException.class,auth.access::requireAdmin);
    }
    @Test void verificationExpiresAndReverificationPreservesFirstDate() throws Exception {
        var first=identity.verify(valid());auth.clock.advance(Duration.ofDays(1));assertFalse(benefits.snapshot(keys.address()).benefitsEnabled());
        var latest=identity.verify(valid());assertEquals(first.verifiedAt(),latest.verifiedAt());assertEquals(auth.clock.instant(),latest.lastVerifiedAt());
    }
    @Test void changedConfiguredGenesisInvalidatesChallengeAndExistingWallet() throws Exception {
        identity.verify(valid());var pending=valid();
        config=new ByxConfig(config.endpoint(),config.rpcEndpoint(),"LOCALNET",config.expectedChainId(),"b".repeat(64),"ubyx","BYX",6,"BANK_METADATA","");
        assertThrows(AccessDeniedException.class,()->identity.verify(pending));assertTrue(identity.wallets().isEmpty());
        assertFalse(benefits.snapshot(keys.address()).benefitsEnabled());
    }
    @Test void onlyOneConcurrentVerificationCanSucceed() throws Exception {
        var proof=valid();var pool=Executors.newFixedThreadPool(2);
        try {
            var task=(Callable<Boolean>)()->{try{identity.verify(proof);return true;}catch(AccessDeniedException e){return false;}};
            var results=pool.invokeAll(List.of(task,task));assertNotEquals(results.get(0).get(),results.get(1).get());
        } finally {pool.shutdownNow();}
    }
    @Test void policyConfigurationRejectsFinancialEnvironmentAndFractionalUnits() {
        var p=ByxBenefitsService.defaults();p.setProperty("environment","MAINNET");
        assertThrows(IllegalArgumentException.class,()->new ByxBenefitsService(identity,null,auth.clock,p));
        p.setProperty("environment","LOCALNET_TEST_ONLY");p.setProperty("HOLDER","0.1");
        assertThrows(IllegalArgumentException.class,()->new ByxBenefitsService(identity,null,auth.clock,p));
    }
    @Test void logoutCannotReuseAnEarlierUsersBenefitCache() throws Exception {
        identity.verify(valid());assertTrue(benefits.refresh(keys.address()).get().benefitsEnabled());
        auth.auth.logout();assertThrows(AccessDeniedException.class,()->benefits.snapshot(keys.address()));
    }
    @Test void databasePersistsOnlyPermittedPublicColumns(@TempDir Path dir) throws Exception {
        var file=dir.resolve("wallets.db");var proof=valid();var w=identity.verify(proof);
        try(var db=Database.openRuntime(file)){new ByxWalletRepository(db).save(w);}
        try(var db=Database.openRuntime(file)){
            assertEquals(w,new ByxWalletRepository(db).list(w.userId()).get(0));
            var columns=db.with(c->{var result=new HashSet<String>();try(var s=c.createStatement();var rs=s.executeQuery("PRAGMA table_info(verified_wallets)")){while(rs.next())result.add(rs.getString("name"));}return result;});
            assertEquals(Set.of("user_id","address","public_key","chain_id","genesis_fingerprint","verified_at","last_verified_at","revoked_at"),columns);
        }
    }
    /** Contrato C: a lógica de domínio acima só é provada se o autorizador foi realmente exigido; e a negação do serviço vem ANTES da lógica. */
    @Test void domainTestsConsultTheAuthorizerAndADeniedOperationNeverReachesTheDomainLogic() throws Exception {
        var proof=valid();identity.verify(proof);
        assertTrue(authorizer.calls().contains(ServerOperation.WALLET_IDENTITY));
        authorizer.deny(ServerOperation.WALLET_IDENTITY);
        var e=assertThrows(AccessDeniedException.class,()->identity.verify(keys.sign(identity.challenge(keys.address()))));
        assertEquals(ServerAuthorization.REQUIRED+": wallet.identity",e.getMessage());
        assertEquals(1,new ByxWalletRepository(auth.db).list(auth.sessions.user().orElseThrow().user().id()).size());
    }
}
