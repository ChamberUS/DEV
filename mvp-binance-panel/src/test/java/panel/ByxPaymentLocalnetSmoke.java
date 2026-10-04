package panel;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.math.BigInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import panel.model.*;
import panel.repository.*;
import panel.service.*;
import panel.adapter.*;

/** Explicit TEST-only SDK signer/broadcast runner, never included in production UI. */
public final class ByxPaymentLocalnetSmoke {
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper();Path root=Path.of(System.getProperty("user.home"),".byx-mvp-localnet-b-v1");
        var manifest=json.readTree(root.resolve("localnet.json").toFile());
        var service=json.readTree(Path.of("/private/tmp/byx-spend-phase/service-wallet.json").toFile());
        var config=ByxLocalnetSmoke.config(manifest,"bob-test");Clock clock=Clock.systemUTC();
        var auth=AuthFixture.ready();auth.seedAdmin();auth.auth.login("boss","correct-horse-1".toCharArray());
        var identity=new ByxWalletIdentityService(auth.sessions,new ByxWalletRepository(auth.db),()->config,clock);
        var challenge=identity.challenge(config.observedAddress());
        var signer=new ProcessBuilder(root.resolve("bin/wallet-test-signer").toString());signer.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");
        var child=signer.start();try(var input=child.getOutputStream()){json.writeValue(input,challenge);}
        byte[] publicProof=child.getInputStream().readAllBytes();ByxLocalnetSmoke.check(child.waitFor(15,TimeUnit.SECONDS)&&child.exitValue()==0,"DEV proof signer failed");
        identity.verify(json.readValue(publicProof,WalletProof.class));
        var gateway=new CosmosByxChainGateway(clock);
        try(var benefits=new ByxBenefitsService(identity,gateway,clock,ByxBenefitsService.defaults())){
            var p=new ByxPaymentPolicy(service.path("recipient").asText(),config.expectedChainId(),config.genesisFingerprint(),BigInteger.valueOf(10000),300,5);
            var payments=new ByxPaymentService(auth.sessions,identity,new ByxPaymentRepository(auth.db),new CosmosByxPaymentVerifier(clock),clock,()->p);
            var entitlements=new EntitlementService(benefits,payments);
            var before=benefits.refresh(config.observedAddress()).get();ByxLocalnetSmoke.check(before.tier().equals("FREE"),"Bob TEST FREE fixture required");
            ByxLocalnetSmoke.check(!entitlements.allows(config.observedAddress(),"advanced_analytics"),"FREE analytics closed");
            var intent=payments.create(config.observedAddress());
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("id",intent.id());payload.put("chainId",intent.chainId());payload.put("genesisFingerprint",intent.genesisFingerprint());payload.put("recipient",intent.recipient());payload.put("verifiedWallet",intent.verifiedWallet());payload.put("amountUbyx",intent.amountUbyx().toString());payload.put("purpose",intent.purpose());payload.put("createdAt",intent.createdAt().toString());payload.put("expiresAt",intent.expiresAt().toString());
            Path evidence=root.resolve("evidence/payment-"+intent.id()+".json");Files.writeString(evidence,json.writeValueAsString(payload));
            var broadcast=new ProcessBuilder("python3","scripts/byx_payment_test.py","send");broadcast.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");var sender=broadcast.start();
            try(var input=sender.getOutputStream()){json.writeValue(input,payload);}
            byte[] result=sender.getInputStream().readAllBytes();ByxLocalnetSmoke.check(sender.waitFor(15,TimeUnit.SECONDS)&&sender.exitValue()==0,"TEST transfer failed");
            String hash=json.readTree(result).path("tx_hash").asText();payload.put("tx_hash",hash);Files.writeString(evidence,json.writeValueAsString(payload));
            PaymentReceipt receipt=null;
            for(int n=0;n<30;n++){
                try{receipt=payments.confirm(intent.id(),hash);break;}
                catch(java.io.IOException notIndexed){Thread.sleep(1000);}
            }
            ByxLocalnetSmoke.check(receipt!=null,"No confirmed receipt");
            ByxLocalnetSmoke.check(entitlements.allows(config.observedAddress(),"advanced_analytics"),"Paid analytics gate opens");
            ByxLocalnetSmoke.check(!entitlements.analyticsPreview(config.observedAddress()).isEmpty(),"Feature consumer executes");
            var after=benefits.refresh(config.observedAddress()).get();
            ByxLocalnetSmoke.check(before.balanceUbyx().subtract(after.balanceUbyx()).equals(BigInteger.valueOf(20000)),"Exact payment plus gas reconciled");
            ByxLocalnetSmoke.check(after.tier().equals("FREE"),"Payment never upgrades tier");
            Thread.sleep(5500);ByxLocalnetSmoke.check(!entitlements.allows(config.observedAddress(),"advanced_analytics"),"Expiry gate closed");
            ByxLocalnetSmoke.check(identity.verified(config.observedAddress()).isPresent(),"Proof still valid: expiry is the payment pass");
            identity.revoke(config.observedAddress());ByxLocalnetSmoke.check(payments.active(config.observedAddress()).isEmpty(),"Revoke disables pass");
            ByxLocalnetSmoke.check(payments.receipts().size()==1,"Revoke preserves receipt");
            payload.put("height",receipt.height());payload.put("confirmed_at",receipt.confirmedAt().toString());payload.put("starts_at",receipt.startsAt().toString());payload.put("expires_at",receipt.expiresAt().toString());payload.put("before_ubyx",before.balanceUbyx().toString());payload.put("after_ubyx",after.balanceUbyx().toString());payload.put("test_only",true);payload.put("smoke","PASS");Files.writeString(evidence,json.writeValueAsString(payload));
            System.out.println("PAYMENT_LOCALNET_SMOKE_OK tx="+hash+" height="+receipt.height()+" FREE -> BYX_PAYMENT analytics -> revoked/expired; 10000ubyx + 10000ubyx gas");
        }auth.db.close();
    }
}
