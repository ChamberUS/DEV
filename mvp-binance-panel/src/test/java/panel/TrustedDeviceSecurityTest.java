package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import panel.auth.*;
import panel.security.*;

class TrustedDeviceSecurityTest {
    AuthFixture admin(){var f=AuthFixture.ready();f.seedAdmin();f.auth.login("boss","correct-horse-1".toCharArray());return f;}
    TwoFactorFlow complete(AuthFixture f){
        var flow=f.access.startTwoFactor();flow.sendEmailCode();assertEquals(OtpService.Result.OK,flow.verifyEmail(f.otpProvider.lastCode()));
        flow.sendSmsCode();assertEquals(OtpService.Result.OK,flow.verifySms(f.otpProvider.lastCode()));return flow;
    }
    void relogin(AuthFixture f){f.auth.logout();f.auth.login("boss","correct-horse-1".toCharArray());}
    @Test void deviceTokenIs256BitsAndOnlyHashGoesToDatabase(){
        var f=admin();complete(f).finish(true);char[] token=f.secrets.read(SecretStore.DEVICE).orElseThrow();
        assertEquals(32,java.util.Base64.getUrlDecoder().decode(new String(token)).length);
        String stored=f.db.with(c->{try(var p=c.createStatement();var r=p.executeQuery("SELECT token_hash FROM trusted_devices")){r.next();return r.getString(1);}});
        assertEquals(64,stored.length());assertNotEquals(new String(token),stored);
        assertFalse(f.audit.recent(100).toString().contains(new String(token)));assertFalse(f.devices.list().toString().contains(stored));
    }
    @Test void trustedDeviceSurvivesLogoutButCreatesTimedAdminSession(){
        var f=admin();complete(f).finish(true);relogin(f);assertFalse(f.access.hasValidAdminSession());
        assertTrue(f.access.tryTrustedDevice());assertEquals(AuthMethod.TRUSTED_DEVICE,f.access.adminSession().orElseThrow().method());
        f.clock.advance(Duration.ofMinutes(31));assertThrows(AccessDeniedException.class,f.access::requireAdmin);
    }
    @Test void expiredDeviceRequiresTwoFactorsAndIsAudited(){
        var f=admin();complete(f).finish(true);f.clock.advance(Duration.ofDays(31));relogin(f);
        assertFalse(f.access.tryTrustedDevice());assertEquals(AccessDecision.REQUIRES_2FA,f.access.evaluate());
        assertTrue(f.audit.recent(10).stream().anyMatch(e->e.event().equals("TRUSTED_DEVICE_EXPIRED")));
    }
    @Test void revokedDeviceCannotReturn(){
        var f=admin();complete(f).finish(true);String id=f.devices.list().getFirst().id();f.devices.revoke(id);
        assertFalse(f.access.hasValidAdminSession());assertFalse(f.access.tryTrustedDevice());relogin(f);assertFalse(f.access.tryTrustedDevice());
    }
    @Test void missingOrIncorrectKeychainTokenFailsClosed(){
        var f=admin();complete(f).finish(true);relogin(f);f.secrets.delete(SecretStore.DEVICE);assertFalse(f.access.tryTrustedDevice());
        f.secrets.write(SecretStore.DEVICE,"incorrect-fixture-token".toCharArray());assertFalse(f.access.tryTrustedDevice());
        f.secrets.unavailable=true;assertFalse(f.access.tryTrustedDevice());
    }
    @Test void anotherUserCannotUseDeviceToken(){
        var f=admin();complete(f).finish(true);
        f.userService.createUser("second","second@example.invalid","another-password-1".toCharArray(),"+15555550101",Role.ADMIN);
        f.auth.logout();f.auth.login("second","another-password-1".toCharArray());assertFalse(f.access.tryTrustedDevice());
    }
    @Test void userCannotCreateOrListTrustedDevices(){
        var f=AuthFixture.ready();f.seedUser();f.auth.login("alice","temporary-pass-1".toCharArray());
        assertThrows(AccessDeniedException.class,f.devices::trustCurrent);assertThrows(AccessDeniedException.class,f.devices::list);assertFalse(f.access.tryTrustedDevice());
    }
    @Test void trustedSessionCannotMintAnotherDeviceWithoutFreshTwoFactors(){
        var f=admin();complete(f).finish(true);relogin(f);assertTrue(f.access.tryTrustedDevice());assertThrows(AccessDeniedException.class,f.devices::trustCurrent);
    }
    @Test void logoutInvalidatesEmailChallengeEvenOnSameUserRelogin(){
        var f=admin();var flow=f.access.startTwoFactor();flow.sendEmailCode();String code=f.otpProvider.lastCode();relogin(f);
        assertEquals(OtpService.Result.EXPIRED,flow.verifyEmail(code));assertThrows(AccessDeniedException.class,flow::sendSmsCode);assertFalse(f.access.hasValidAdminSession());
    }
    @Test void logoutDuringSmsRequestCannotAuthorizeNewLogin() throws Exception {
        var f=admin();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        SmsOtpProvider provider=new SmsOtpProvider(){
            public boolean configured(){return true;}public String name(){return "fake";}
            public String startVerification(String phone){return "fake-id";}
            public boolean checkVerification(String phone,String id,String code){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException();}return true;}
        };
        var access=new AdminAccessService(f.sessions,new SecurityConfig(30,true),new OtpService(f.clock),f.otpProvider,provider,f.devices,f.audit,f.clock);
        var flow=access.startTwoFactor();flow.sendEmailCode();flow.verifyEmail(f.otpProvider.lastCode());flow.sendSmsCode();
        var result=new AtomicReference<OtpService.Result>();Thread t=new Thread(()->result.set(flow.verifySms("123456")));t.start();
        try{assertTrue(entered.await(5,TimeUnit.SECONDS));relogin(f);}finally{release.countDown();t.join(5000);}
        assertEquals(OtpService.Result.EXPIRED,result.get());assertFalse(access.hasValidAdminSession());
    }
    @Test void smsWrongExpiredAndAttemptLimitsFailClosed(){
        var f=admin();var flow=f.access.startTwoFactor();flow.sendEmailCode();flow.verifyEmail(f.otpProvider.lastCode());flow.sendSmsCode();
        String correct=f.otpProvider.lastCode(),wrong="000000".equals(correct)?"111111":"000000";
        for(int i=0;i<5;i++)assertEquals(OtpService.Result.INVALID,flow.verifySms(wrong));
        assertEquals(OtpService.Result.TOO_MANY_ATTEMPTS,flow.verifySms(correct));assertFalse(f.access.hasValidAdminSession());
    }
    @Test void smsExpiresAndCannotBeReplayed(){
        var f=admin();var flow=f.access.startTwoFactor();flow.sendEmailCode();flow.verifyEmail(f.otpProvider.lastCode());flow.sendSmsCode();
        f.clock.advance(Duration.ofMinutes(6));assertEquals(OtpService.Result.EXPIRED,flow.verifySms(f.otpProvider.lastCode()));assertFalse(f.access.hasValidAdminSession());
    }
    @Test void userSwitchInvalidatesChallenge(){
        var f=admin();var flow=f.access.startTwoFactor();flow.sendEmailCode();String code=f.otpProvider.lastCode();
        f.sessions.login(new panel.user.User(999,"other","other@example.invalid","unused",Role.ADMIN,panel.user.UserStatus.ACTIVE,"+15555550199",false,false,false,f.clock.instant(),f.clock.instant(),null),f.clock.instant());
        assertEquals(OtpService.Result.EXPIRED,flow.verifyEmail(code));assertFalse(f.access.hasValidAdminSession());
    }
    @Test void contactChangeRequiresPasswordAndRevokesAllTrust(){
        var f=admin();complete(f).finish(true);long id=f.sessions.user().orElseThrow().user().id();
        assertThrows(IllegalArgumentException.class,()->f.userService.changeOwnContact(id,"wrong".toCharArray(),"new@example.invalid","+15555550102"));
        assertThrows(IllegalArgumentException.class,()->f.userService.changeOwnContact(id,"correct-horse-1".toCharArray(),"new@example.invalid","5555550102"));
        f.userService.changeOwnContact(id,"correct-horse-1".toCharArray(),"new@example.invalid","+15555550102");
        assertFalse(f.access.hasValidAdminSession());assertFalse(f.access.tryTrustedDevice());
        String log=f.audit.recent(100).toString();assertFalse(log.contains("new@example.invalid"));assertFalse(log.contains("+15555550102"));
    }
    @Test void passkeyCannotGrantSessionYet(){assertThrows(IllegalArgumentException.class,()->new AdminSession(Instant.now(),AuthMethod.PASSKEY,Duration.ofMinutes(30)));}
    @Test void keychainFailureDoesNotPersistTokenInDatabase(){
        var f=admin();var flow=complete(f);f.secrets.unavailable=true;
        assertThrows(SecretStore.Unavailable.class,()->flow.finish(true));assertTrue(f.devices.list().isEmpty());
        flow.finish(false);assertTrue(f.access.hasValidAdminSession());
    }
}
