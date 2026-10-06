package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import panel.adapter.AdaptiveTraderCli;
import panel.adapter.CommandSpec;
import panel.auth.AccessDecision;
import panel.auth.AuthMethod;
import panel.auth.AuthService.Failure;
import panel.auth.AuthService.LoginException;
import panel.auth.OtpService.Result;
import panel.auth.TwoFactorFlow;
import panel.auth.TwoFactorNotConfiguredException;
import panel.security.AccessDeniedException;
import panel.security.Role;
import panel.service.JobManager;
import panel.user.UserStatus;

class AuthSecurityTest {
    private static final char[] ADMIN_PW = "correct-horse-1".toCharArray();
    private static final char[] USER_PW = "temporary-pass-1".toCharArray();

    private static TwoFactorFlow flow(AuthFixture f) {
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        return f.access.startTwoFactor();
    }

    @Test
    void passwordsAreHashedWithArgon2id() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        String hash = f.users.findByUsernameOrEmail("boss").orElseThrow().passwordHash();
        assertTrue(hash.startsWith("$argon2id$"));
        assertFalse(hash.contains("correct-horse"));
        assertTrue(f.hasher.verify(ADMIN_PW, hash));
        assertFalse(f.hasher.verify("wrong-password".toCharArray(), hash));
        assertNotEquals(hash, f.hasher.hash(ADMIN_PW));
    }

    @Test
    void userAndAdminCanLogIn() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        assertEquals(Role.USER, f.auth.login("alice", USER_PW).role());
        f.auth.logout();
        assertEquals(Role.ADMIN, f.auth.login("boss@example.com", ADMIN_PW).role());
        assertTrue(f.users.findByUsernameOrEmail("boss").orElseThrow().lastLoginAt() != null);
    }

    @Test
    void invalidPasswordAndUnknownUserAreRejectedAndAudited() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        assertEquals(Failure.INVALID_CREDENTIALS, assertThrows(LoginException.class, () -> f.auth.login("boss", "nope-nope-nope".toCharArray())).failure);
        assertEquals(Failure.INVALID_CREDENTIALS, assertThrows(LoginException.class, () -> f.auth.login("ghost", ADMIN_PW)).failure);
        assertTrue(f.sessions.user().isEmpty());
        assertTrue(f.audit.recent(10).stream().anyMatch(e -> e.event().equals("LOGIN_FAILED")));
    }

    @Test
    void disabledUserCannotLogIn() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        f.auth.login("boss", ADMIN_PW);
        f.authorize();
        f.userService.setStatus(f.users.findByUsernameOrEmail("alice").orElseThrow().id(), UserStatus.DISABLED);
        f.auth.logout();
        assertEquals(Failure.ACCOUNT_DISABLED, assertThrows(LoginException.class, () -> f.auth.login("alice", USER_PW)).failure);
    }

    @Test
    void repeatedFailuresAreRateLimited() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        for (int i = 0; i < 3; i++) {
            assertThrows(LoginException.class, () -> f.auth.login("boss", "bad-password-1".toCharArray()));
        }
        assertEquals(Failure.RATE_LIMITED, assertThrows(LoginException.class, () -> f.auth.login("boss", ADMIN_PW)).failure);
        f.clock.advance(Duration.ofSeconds(61));
        f.auth.login("boss", ADMIN_PW);
    }

    @Test
    void initialAdminSetupOnlyWorksOnce() {
        AuthFixture f = AuthFixture.ready();
        assertTrue(f.auth.firstRun());
        f.seedAdmin();
        assertFalse(f.auth.firstRun());
        assertThrows(AccessDeniedException.class, () -> f.userService.createInitialAdmin("x-admin", "x@example.com", ADMIN_PW, null));
    }

    @Test
    void userCannotAccessResearch() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        f.auth.login("alice", USER_PW);
        assertEquals(AccessDecision.FORBIDDEN_NOT_ADMIN, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertThrows(AccessDeniedException.class, f.access::startTwoFactor);
    }

    @Test
    void withoutLoginNothingGrantsAdmin() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
    }

    @Test
    void adminWithoutDeviceRequiresEmailAndSms() {
        AuthFixture f=AuthFixture.ready();f.seedAdmin();f.auth.login("boss",ADMIN_PW);
        assertEquals(AccessDecision.REQUIRES_2FA,f.access.evaluate());
        assertFalse(f.access.tryTrustedDevice());
        assertThrows(AccessDeniedException.class,f.access::requireAdmin);
    }

    @Test
    void adminLoginAloneRequiresTwoFactor() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
    }

    @Test
    void emailOtpAloneDoesNotGrantAccess() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        assertEquals(Result.OK, flow.verifyEmail(f.otpProvider.lastCode()));
        assertFalse(flow.complete());
        assertFalse(f.access.hasValidAdminSession());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
    }

    @Test
    void smsOtpAloneDoesNotGrantAccess() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        assertThrows(IllegalStateException.class, flow::sendSmsCode);
        assertEquals(Result.NO_CHALLENGE, flow.verifySms("123456"));
        assertFalse(f.access.hasValidAdminSession());
    }

    @Test
    void emailPlusSmsGrantsAccess() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        assertEquals(Result.OK, flow.verifyEmail(f.otpProvider.lastCode()));
        flow.sendSmsCode();
        assertEquals(Result.OK, flow.verifySms(f.otpProvider.lastCode()));
        assertTrue(flow.complete());
        assertEquals(AuthMethod.TWO_FACTOR, f.access.adminSession().orElseThrow().method());
        assertEquals("boss", f.access.requireAdmin().username());
    }

    @Test
    void expiredOtpIsRejected() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        String code = f.otpProvider.lastCode();
        f.clock.advance(Duration.ofMinutes(6));
        assertEquals(Result.EXPIRED, flow.verifyEmail(code));
        assertFalse(flow.emailVerified());
    }

    @Test
    void reusedOtpIsRejected() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        String code = f.otpProvider.lastCode();
        assertEquals(Result.OK, flow.verifyEmail(code));
        assertEquals(Result.NO_CHALLENGE, flow.verifyEmail(code));
    }

    @Test
    void otpHasAttemptLimitAndResendCooldown() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        String code = f.otpProvider.lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertEquals(Result.INVALID, flow.verifyEmail(wrong));
        }
        assertEquals(Result.TOO_MANY_ATTEMPTS, flow.verifyEmail(code));
        assertThrows(panel.auth.OtpService.CooldownException.class, flow::sendEmailCode);
        f.clock.advance(Duration.ofSeconds(31));
        flow.sendEmailCode();
        assertEquals(Result.OK, flow.verifyEmail(f.otpProvider.lastCode()));
    }

    @Test
    void unconfiguredProvidersNeverUnlockAdmin() {
        AuthFixture f = new AuthFixture(false);
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
        TwoFactorNotConfiguredException e = assertThrows(TwoFactorNotConfiguredException.class, f.access::startTwoFactor);
        assertEquals("Two-factor authentication is not configured.", e.getMessage());
        assertFalse(f.access.hasValidAdminSession());
    }

    @Test
    void expiredAdminSessionDeniesAccess() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        f.authorize();
        f.clock.advance(Duration.ofMinutes(29));
        f.access.touch();
        f.clock.advance(Duration.ofMinutes(29));
        assertEquals("boss", f.access.requireAdmin().username());
        f.clock.advance(Duration.ofMinutes(31));
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertTrue(f.audit.recent(10).stream().anyMatch(e -> e.event().equals("ADMIN_SESSION_EXPIRED")));
    }

    @Test
    void logoutInvalidatesBothSessions() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        f.authorize();
        f.auth.logout();
        assertTrue(f.sessions.user().isEmpty());
        assertTrue(f.sessions.admin().isEmpty());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
    }

    @Test
    void adminOperationsAreProtectedAtServiceLayer() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        assertThrows(AccessDeniedException.class, () -> f.userService.createUser("bob", "bob@example.com", USER_PW, null, Role.USER));
        assertThrows(AccessDeniedException.class, f.userService::listUsers);
        JobManager jobs = new JobManager(new AdaptiveTraderCli(() -> "/x/adaptive-trader"), () -> java.nio.file.Path.of("."), () -> { }, f.access::requireAdmin);
        assertThrows(AccessDeniedException.class, () -> jobs.submit(CommandSpec.LABEL_STATUS, null));
        assertThrows(AccessDeniedException.class, () -> jobs.submit(CommandSpec.LABEL_RUN, null));
    }

    @Test
    void adminCannotDisableSelfOrLastAdmin() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        f.authorize();
        long id = f.users.findByUsernameOrEmail("boss").orElseThrow().id();
        assertThrows(IllegalArgumentException.class, () -> f.userService.setStatus(id, UserStatus.DISABLED));
        assertThrows(IllegalArgumentException.class, () -> f.userService.changeRole(id, Role.USER));
    }

    @Test
    void temporaryPasswordForcesChange() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        assertTrue(f.auth.login("alice", USER_PW).mustChangePassword());
        long id = f.users.findByUsernameOrEmail("alice").orElseThrow().id();
        f.userService.changeOwnPassword(id, USER_PW, "a-brand-new-password".toCharArray());
        assertFalse(f.users.findById(id).orElseThrow().mustChangePassword());
        assertThrows(IllegalArgumentException.class, () -> f.userService.changeOwnPassword(id, "wrong-current-pw".toCharArray(), "another-new-password".toCharArray()));
    }

    @Test
    void auditLogNeverContainsSecrets() {
        AuthFixture f = AuthFixture.ready();
        TwoFactorFlow flow = flow(f);
        flow.sendEmailCode();
        String code = f.otpProvider.lastCode();
        flow.verifyEmail(code);
        String all = f.audit.recent(100).toString();
        assertFalse(all.contains(code));
        assertFalse(all.contains("correct-horse"));
        assertFalse(all.contains("argon2"));
    }

    @Test
    void legacyIpv6ConfigurationDoesNotGrantAccess(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var path=dir.resolve("security.properties");
        java.nio.file.Files.writeString(path,"security.admin.trustedIpv6=2001:db8::1\nsecurity.admin.sessionTimeoutMinutes=7\n");
        var config=panel.security.SecurityConfig.load(path);
        assertEquals(7,config.sessionTimeoutMinutes());assertFalse(config.devMode());
        AuthFixture f=AuthFixture.ready();f.seedAdmin();f.auth.login("boss",ADMIN_PW);
        var access=new panel.auth.AdminAccessService(f.sessions,f.users,config,new panel.auth.OtpService(f.clock),f.otpProvider,f.otpProvider,f.devices,f.audit,f.clock);
        assertEquals(AccessDecision.REQUIRES_2FA,access.evaluate());
        assertFalse(access.tryTrustedDevice());assertThrows(AccessDeniedException.class,access::requireAdmin);
    }

    @Test
    void validationAndFinalHoldoutRemainBlockedEvenForAdmin() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", ADMIN_PW);
        f.authorize();
        f.access.requireAdmin();
        AdaptiveTraderCli cli = new AdaptiveTraderCli(() -> "/x/adaptive-trader");
        assertThrows(IllegalArgumentException.class, () -> cli.build(CommandSpec.LABEL_RUN_SESSION, "microstructure-20260814T011521Z-validation"));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveTraderCli(() -> "/x/FINAL_HOLDOUT/cli").build(CommandSpec.LABEL_STATUS, null));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveTraderCli(() -> "/x/validation/cli").build(CommandSpec.LABEL_STATUS, null));
    }
}
