package panel;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import panel.auth.*;
import panel.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

class RealProviderTest {
    static final String AC="AC"+"0".repeat(32),SK="SK"+"1".repeat(32),VA="VA"+"2".repeat(32),VE="VE"+"3".repeat(32);
    ProviderConfig config(){return new ProviderConfig(AC,SK,VA,"sender@example.invalid",true);}
    @Test void resendUsesSecretStoreAndRedactsErrors(){
        var store=new MemorySecrets();store.write(SecretStore.RESEND,"fixture-only-key".toCharArray());var calls=new AtomicInteger();
        var provider=new ResendEmailOtpProvider(store,config(),(key,from,to,code)->{calls.incrementAndGet();assertEquals("fixture-only-key",new String(key));throw new Exception("fixture-only-key "+code);});
        assertTrue(provider.configured());var error=assertThrows(IllegalStateException.class,()->provider.send("recipient@example.invalid","123456"));
        assertFalse(error.toString().contains("123456"));assertFalse(error.toString().contains("fixture-only-key"));assertNull(error.getCause());
        assertEquals(ProviderStatus.State.ERROR,provider.status().state());assertEquals(1,calls.get());
    }
    @Test void missingVerifyServiceNeverSendsSms(){
        var store=new MemorySecrets();var calls=new AtomicInteger();
        var p=new TwilioVerifySmsProvider(store,new ProviderConfig(AC,SK,null,null,true),(a,b,c)->{calls.incrementAndGet();return null;});
        assertEquals("Missing Verify Service SID",p.status().detail());assertFalse(p.configured());
        assertThrows(TwoFactorNotConfiguredException.class,()->p.startVerification("+15555550123"));assertEquals(0,calls.get());
    }
    @Test void verifyUsesSmsChannelAndVerificationSidNotLocallyGeneratedCode() throws Exception {
        var store=new MemorySecrets();store.write(SecretStore.TWILIO,"fixture-api-secret".toCharArray());var actions=new ArrayList<String>();
        var p=new TwilioVerifySmsProvider(store,config(),(action,fields,key)->{
            actions.add(action);assertEquals("fixture-api-secret",new String(key));
            if(action.equals("Verifications")){assertEquals("sms",fields.get("Channel"));assertFalse(fields.containsKey("Code"));}
            else{assertEquals(VE,fields.get("VerificationSid"));assertEquals("123456",fields.get("Code"));}
            return new ObjectMapper().readTree("{\"sid\":\""+VE+"\",\"account_sid\":\""+AC+"\",\"service_sid\":\""+VA+"\",\"status\":\""+(action.equals("Verifications")?"pending":"approved")+"\"}");
        });
        assertEquals(VE,p.startVerification("+15555550123"));assertTrue(p.checkVerification("+15555550123",VE,"123456"));
        assertEquals(List.of("Verifications","VerificationCheck"),actions);
        assertThrows(IllegalArgumentException.class,()->p.startVerification("5555550123"));
    }
    @Test void wrongTwilioAccountFailsClosed(){
        var store=new MemorySecrets();store.write(SecretStore.TWILIO,"fixture-secret".toCharArray());
        var p=new TwilioVerifySmsProvider(store,config(),(a,b,c)->new ObjectMapper().readTree("{\"status\":\"approved\"}"));
        assertThrows(IllegalStateException.class,()->p.checkVerification("+15555550123",VE,"123456"));
    }
    @Test void absentSecretsAndUnavailableKeychainNeverSelectDevProvider(){
        var store=new MemorySecrets();var email=new ResendEmailOtpProvider(store,config(),(a,b,c,d)->fail("must not send"));
        var sms=new TwilioVerifySmsProvider(store,config(),(a,b,c)->{fail("must not send");return null;});
        assertFalse(email.configured());assertFalse(sms.configured());assertEquals("Resend",email.name());assertEquals("Twilio Verify",sms.name());
        store.unavailable=true;assertEquals(ProviderStatus.State.ERROR,email.status().state());assertEquals(ProviderStatus.State.ERROR,sms.status().state());
        assertEquals(30,new SecurityConfig(30).sessionTimeoutMinutes());
    }
    @Test void auditRedactsContactValues(){
        var f=AuthFixture.ready();f.audit.record(AuditEvent.LOGIN_FAILED,"person@example.invalid","phone=+15555550123");
        assertFalse(f.audit.recent(10).toString().contains("person@example.invalid"));assertFalse(f.audit.recent(10).toString().contains("+15555550123"));
    }
}
