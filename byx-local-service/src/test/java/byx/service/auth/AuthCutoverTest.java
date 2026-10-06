package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.auth.AuthService.Code;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V2.1G: segundo fator de dois estágios, dispositivo confiável do serviço, trava de migração, login por e-mail, auditoria encadeada, snapshot v3. */
class AuthCutoverTest {
    private AuthFixture f;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private static final long PEER = 4242;

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        f = new AuthFixture();
        f.second.sms = true;
    }

    @AfterEach
    void down() throws Exception {
        f.close();
        Log.redirect(null);
    }

    private String adminLogin() {
        return AuthFixture.token(f.loginAdmin(PEER));
    }

    /** E-mail → SMS; devolve o token com MFA completo. */
    private void fullMfa(String token, String accountId) {
        var begin = f.auth.beginSecondFactor(PEER, token);
        assertEquals(Code.OK, begin.code(), "begin");
        var v = f.auth.verifySecondFactor(PEER, token, (String) begin.data().get("challenge"), f.second.last.get(accountId));
        assertEquals(Code.OK, v.code());
        assertEquals("SMS", v.data().get("next"), "email alone does not complete the second factor");
        assertEquals(Code.OK, f.auth.sendSecondFactorSms(PEER, token).code());
        assertEquals(Code.OK, f.auth.verifySecondFactorSms(PEER, token, f.second.smsCodes.get(accountId)).code());
    }

    // ---- dois estágios ---------------------------------------------------------------------------------------------------------------

    @Test
    void emailAloneNeverCompletesTheSecondFactorAndElevationNeedsBothStages() {
        String t = adminLogin();
        var begin = f.auth.beginSecondFactor(PEER, t);
        f.auth.verifySecondFactor(PEER, t, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, t).code(), "after the e-mail only, no elevation");
        assertEquals(true, f.auth.sessionStatus(PEER, t).data().get("smsPending"));
        assertEquals(false, f.auth.sessionStatus(PEER, t).data().get("mfaRecent"));
        assertEquals(Code.OK, f.auth.sendSecondFactorSms(PEER, t).code());
        assertEquals(Code.OK, f.auth.verifySecondFactorSms(PEER, t, f.second.smsCodes.get(f.adminId)).code());
        assertEquals(Code.OK, f.auth.adminElevation(PEER, t).code());
    }

    @Test
    void smsStageNeedsTheEmailStageAndHasBoundedAttemptsAndSends() {
        String t = adminLogin();
        assertEquals(Code.CHALLENGE_EXPIRED, f.auth.sendSecondFactorSms(PEER, t).code(), "no SMS before the e-mail was verified");
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactorSms(PEER, t, "123456").code());
        var begin = f.auth.beginSecondFactor(PEER, t);
        f.auth.verifySecondFactor(PEER, t, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        assertEquals(Code.OK, f.auth.sendSecondFactorSms(PEER, t).code());
        assertEquals(Code.COOLDOWN, f.auth.sendSecondFactorSms(PEER, t).code());
        String good = f.second.smsCodes.get(f.adminId);
        String wrong = good.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < AuthLimits.SMS_MAX_ATTEMPTS; i++) {
            assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactorSms(PEER, t, wrong).code());
        }
        assertTrue(java.util.Set.of(Code.TOO_MANY_ATTEMPTS, Code.RATE_LIMITED).contains(f.auth.verifySecondFactorSms(PEER, t, good).code()), "attempts accumulate: even the right code is refused afterwards");
        for (int i = 1; i < AuthLimits.SMS_MAX_SENDS; i++) {
            f.clock.advance(AuthLimits.OTP_RESEND_COOLDOWN.plusSeconds(1));
            Code c = f.auth.sendSecondFactorSms(PEER, t).code();
            assertTrue(c == Code.OK || c == Code.RATE_LIMITED, "send " + c);
        }
    }

    @Test
    void smsVerificationExpiresAndTheFlowHasAnAbsoluteWindow() {
        String t = adminLogin();
        var begin = f.auth.beginSecondFactor(PEER, t);
        f.auth.verifySecondFactor(PEER, t, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        f.auth.sendSecondFactorSms(PEER, t);
        String code = f.second.smsCodes.get(f.adminId);
        f.clock.advance(AuthLimits.SMS_TTL.plusSeconds(1));
        assertEquals(Code.CHALLENGE_EXPIRED, f.auth.verifySecondFactorSms(PEER, t, code).code());
        assertEquals(Code.CHALLENGE_EXPIRED, f.auth.sendSecondFactorSms(PEER, t).code(), "stage cleared: start again from the e-mail");
    }

    @Test
    void smsCodesFromAnotherSessionOrAccountAreRefusedAndNeverLeaked() {
        String a = adminLogin();
        String u = AuthFixture.token(f.loginUser(PEER + 1));
        var begin = f.auth.beginSecondFactor(PEER, a);
        f.auth.verifySecondFactor(PEER, a, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        f.auth.sendSecondFactorSms(PEER, a);
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactorSms(PEER + 1, u, f.second.smsCodes.get(f.adminId)).code(), "another account/session cannot use it");
        assertEquals(Code.AUTH_REQUIRED, f.auth.verifySecondFactorSms(PEER + 9, a, f.second.smsCodes.get(f.adminId)).code(), "another peer cannot use the session");
        String all = String.join("\n", logs) + f.audit.entries();
        assertFalse(all.contains(f.second.smsCodes.get(f.adminId)) || all.contains(f.second.last.get(f.adminId)), "no code in logs/audit");
    }

    @Test
    void aSessionRevokedWhileTheProviderAnswersNeverGainsMfa() throws Exception {
        String t = adminLogin();
        var begin = f.auth.beginSecondFactor(PEER, t);
        f.auth.verifySecondFactor(PEER, t, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        f.auth.sendSecondFactorSms(PEER, t);
        f.admin.bumpCredentialVersion(f.adminId); // credencial muda antes da conclusão
        assertEquals(Code.AUTH_REQUIRED, f.auth.verifySecondFactorSms(PEER, t, f.second.smsCodes.get(f.adminId)).code());
    }

    @Test
    void deliveryFailureIsAFixedDeniedWithoutDetail() {
        String t = adminLogin();
        f.second.failDelivery = true;
        assertEquals(Code.DENIED, f.auth.beginSecondFactor(PEER, t).code());
        f.second.failDelivery = false;
        var begin = f.auth.beginSecondFactor(PEER, t);
        assertEquals(Code.OK, begin.code());
    }

    // ---- elevação deslizante -----------------------------------------------------------------------------------------------------------

    @Test
    void elevationSlidesWithActivityUpToTheAbsoluteSessionLimitAndLapsesWhenIdle() throws Exception {
        f.admin.setProviders(new ProviderSettings(null, null, null, null, 30)); // janela migrada do legado: 30 min
        String t = adminLogin();
        fullMfa(t, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER, t).code());
        for (int i = 0; i < 8; i++) { // atividade a cada 10 min mantém a elevação (além da janela de MFA recente, que só vale para ELEVAR)
            f.clock.advance(Duration.ofMinutes(10));
            assertEquals(Code.OK, f.auth.adminElevation(PEER, t).code(), "slide " + i);
            assertEquals(true, f.auth.sessionStatus(PEER, t).data().get("elevated"));
        }
        assertTrue(f.auth.sessionStatus(PEER, t).data().get("elevatedForSec") instanceof Long l && l > 25 * 60 && l <= 30 * 60);
        f.clock.advance(Duration.ofMinutes(31)); // ocioso além da janela: a sessão cai por inatividade de 15 min (e a elevação lapsaria de qualquer forma)
        assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(PEER, t, "qa.adminOp", null).code());
        String t2 = adminLogin();
        fullMfa(t2, f.adminId);
        f.auth.adminElevation(PEER, t2);
        f.clock.advance(Duration.ofMinutes(14)); // sessão ainda viva (idle 15), elevação ainda válida
        f.auth.sessionStatus(PEER, t2);
        f.clock.advance(Duration.ofMinutes(14));
        f.auth.sessionStatus(PEER, t2);
        f.clock.advance(Duration.ofMinutes(14)); // 42 min sem elevar: lapsou (janela 30)
        assertEquals(Code.DENIED, f.auth.authorize(PEER, t2, "qa.adminOp", null).code());
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, t2).code(), "lapsed elevation needs the second factor (or a trusted device) again");
    }

    @Test
    void theElevationWindowDefaultsToTheStrictLimitWhenNothingWasMigrated() throws Exception {
        assertEquals(AuthLimits.ADMIN_ELEVATION_TIMEOUT.toMillis(), ProviderSettings.NONE.elevationMs());
        assertEquals(30 * 60_000L, new ProviderSettings(null, null, null, null, 30).elevationMs());
        assertEquals(AuthLimits.ADMIN_ELEVATION_TIMEOUT.toMillis(), new ProviderSettings(null, null, null, null, 999).elevationMs(), "out-of-range values are not honored");
    }

    // ---- dispositivo confiável -------------------------------------------------------------------------------------------------------

    @Test
    void trustedDeviceIsEnrolledByFreshMfaThenReducesTheSecondFactorUntilRevokedOrExpired() throws Exception {
        String t = adminLogin();
        assertEquals(Code.DENIED, f.auth.enrollTrustedDevice(PEER, t).code(), "no enrolment without MFA + elevation");
        fullMfa(t, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER, t).code());
        var enrolled = f.auth.enrollTrustedDevice(PEER, t);
        assertEquals(Code.OK, enrolled.code());
        String device = (String) enrolled.data().get("device");
        assertTrue(device.matches("[0-9a-f]{32}"));
        // nova sessão sem 2º fator: a elevação passa pelo dispositivo confiável
        String t2 = adminLogin();
        assertEquals(true, f.auth.sessionStatus(PEER, t2).data().get("trustedDevice"));
        assertEquals(Code.OK, f.auth.adminElevation(PEER, t2).code());
        assertTrue(f.audit.entries().stream().anyMatch(e -> e.event().equals("ELEVATION_TRUSTED_DEVICE")));
        var list = f.auth.listTrustedDevices(PEER, t2);
        assertEquals(Code.OK, list.code());
        assertTrue(((String) list.data().get("devices")).startsWith(device + ",ACTIVE"));
        // revogar encerra a elevação e a próxima sessão volta a exigir o 2º fator
        assertEquals(Code.OK, f.auth.revokeTrustedDevice(PEER, t2, device).code());
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, t2).code());
        String t3 = adminLogin();
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, t3).code());
        // expira
        fullMfa(t3, f.adminId);
        f.auth.adminElevation(PEER, t3);
        f.auth.enrollTrustedDevice(PEER, t3);
        f.clock.advance(AuthLimits.TRUSTED_DEVICE_VALIDITY.plusSeconds(1));
        String t4 = adminLogin();
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, t4).code(), "expired device");
    }

    @Test
    void trustedDevicesAreRevokedOnPasswordRoleOrStatusChangeAndNeverForAUser() throws Exception {
        String t = adminLogin();
        fullMfa(t, f.adminId);
        f.auth.adminElevation(PEER, t);
        f.auth.enrollTrustedDevice(PEER, t);
        f.admin.changePassword(f.adminId, "a-brand-new-admin-pass-1".toCharArray());
        assertTrue(f.store.current().devices().stream().allMatch(d -> d.revokedAtMs() != 0), "password change revokes the device");
        var login = f.auth.login(PEER, "admin_user", "a-brand-new-admin-pass-1".toCharArray());
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER, AuthFixture.token(login)).code());
        String u = AuthFixture.token(f.loginUser(PEER + 2));
        assertEquals(Code.DENIED, f.auth.enrollTrustedDevice(PEER + 2, u).code(), "a USER cannot enrol a device");
        assertEquals(Code.DENIED, f.auth.listTrustedDevices(PEER + 2, u).code());
    }

    @Test
    void freshMfaIsRequiredToEnrolAndItIsNotReusedAfterTheWindow() {
        String t = adminLogin();
        fullMfa(t, f.adminId);
        f.auth.adminElevation(PEER, t);
        f.clock.advance(AuthLimits.TRUSTED_DEVICE_ENROLL_MFA_WINDOW.plusSeconds(1));
        assertEquals(Code.DENIED, f.auth.enrollTrustedDevice(PEER, t).code(), "MFA older than 5 minutes");
    }

    // ---- trava de migração -----------------------------------------------------------------------------------------------------------

    @Test
    void theMigrationFreezeBlocksTheMutationsThatWouldComplicateRollbackButNotLoginOrOtp() throws Exception {
        String t = adminLogin();
        fullMfa(t, f.adminId);
        f.auth.adminElevation(PEER, t);
        f.admin.setFreeze(true);
        assertEquals(Code.FROZEN, f.auth.enrollTrustedDevice(PEER, t).code(), "no permanent device enrolment");
        assertEquals(Code.FROZEN, f.auth.changePassword(PEER, t, f.adminPw.toCharArray(), "another-strong-admin-pass".toCharArray()).code(), "no password change");
        for (var op : List.<org.junit.jupiter.api.function.Executable>of(() -> f.admin.setRole(f.userId, Role.ADMIN), () -> f.admin.setEnabled(f.userId, false), () -> f.admin.delete(f.userId),
                () -> f.admin.changePassword(f.userId, "yet-another-strong-pass-1".toCharArray()), () -> f.admin.bumpCredentialVersion(f.userId),
                () -> f.admin.enrollDevice(f.userId, 1000), () -> f.admin.createAccount("later_user", "later-user-password-1".toCharArray(), Role.USER))) {
            var e = org.junit.jupiter.api.Assertions.assertThrows(AuthorityException.class, op);
            assertEquals("frozen", e.code);
        }
        assertEquals(Code.OK, f.loginUser(PEER + 3).code(), "login still works");
        assertEquals(Code.OK, f.auth.beginSecondFactor(PEER, t).code(), "OTP still works");
        assertEquals(Code.OK, f.auth.logout(PEER, t).code(), "logout still works");
        f.admin.revokeDevice(f.adminId, "0".repeat(32)); // revogar (reduzir confiança) nunca é bloqueado
        assertTrue(f.store.current().migrationFreeze());
        f.admin.setFreeze(false);
        f.admin.setRole(f.userId, Role.USER); // depois da validação explícita: liberado
    }

    @Test
    void theFreezeSurvivesRestartAndIsInTheEncryptedSnapshot() throws Exception {
        f.admin.setFreeze(true);
        AuthorityStore again = AuthorityStore.open(f.file, f.anchor, f.vault);
        assertTrue(again.current().migrationFreeze());
        byte[] raw = Files.readAllBytes(f.file);
        assertFalse(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).contains("migrationFreeze"));
    }

    // ---- login por usuário OU e-mail, contato e snapshot v3 --------------------------------------------------------------------------

    @Test
    void loginAcceptsUsernameOrEmailIdenticallyAndExposesPresentationData() throws Exception {
        var a = f.store.current().byId(f.userId).orElseThrow();
        f.admin.importAccount(new Account("a".repeat(32), "migrated_user", Role.USER, true, 1, a.passwordHash(), 1000, 7, "Migrated.User@example.com", "+5511999990000", true, false, false, 0));
        var byName = f.auth.login(PEER, "migrated_user", f.userPw.toCharArray());
        var byMail = f.auth.login(PEER + 1, "MIGRATED.user@Example.com", f.userPw.toCharArray());
        assertEquals(Code.OK, byName.code());
        assertEquals(Code.OK, byMail.code());
        assertEquals(7L, byName.data().get("userId"), "stable legacy identity is presented");
        assertEquals("Migrated.User@example.com", byMail.data().get("email"));
        assertEquals(true, byName.data().get("emailVerified"));
        AuthorityStore again = AuthorityStore.open(f.file, f.anchor, f.vault);
        assertEquals("+5511999990000", again.current().byId("a".repeat(32)).orElseThrow().phone(), "contacts survive the encrypted snapshot");
        assertTrue(again.current().byId("a".repeat(32)).orElseThrow().lastLoginAtMs() > 0);
    }

    @Test
    void importPreservesVerifierRoleAndEnabledStateAndRefusesCollisions() throws Exception {
        var a = f.store.current().byId(f.userId).orElseThrow();
        Account disabled = new Account("b".repeat(32), "legacy_off", Role.USER, false, 1, a.passwordHash(), 5, 3, "off@example.com", null, false, false, false, 0);
        f.admin.importAccount(disabled);
        var got = f.store.current().byId("b".repeat(32)).orElseThrow();
        assertEquals(a.passwordHash(), got.passwordHash(), "the Argon2 verifier is preserved as is");
        assertFalse(got.enabled(), "a disabled account is never reactivated");
        assertEquals(Role.USER, got.role());
        assertEquals(1, got.credentialVersion());
        assertEquals(Code.INVALID_CREDENTIALS, f.auth.login(PEER, "legacy_off", f.userPw.toCharArray()).code());
        for (Account dup : List.of(new Account("c".repeat(32), "legacy_off", Role.USER, true, 1, a.passwordHash(), 5, 9, "x@example.com", null, false, false, false, 0),
                new Account("d".repeat(32), "other_name", Role.USER, true, 1, a.passwordHash(), 5, 3, "y@example.com", null, false, false, false, 0), // legacy id repetido
                new Account("b".repeat(32), "third_name", Role.USER, true, 1, a.passwordHash(), 5, 11, "z@example.com", null, false, false, false, 0))) { // id repetido
            assertEquals("invalid_change", org.junit.jupiter.api.Assertions.assertThrows(AuthorityException.class, () -> f.admin.importAccount(dup)).code);
        }
        // e-mail duplicado (sem diferença de caixa) é recusado pelo parser ao recarregar: a importação nunca produz estado que não reabre
        assertEquals(AuthorityStore.Status.TRUSTED, AuthorityStore.open(f.file, f.anchor, f.vault).status());
    }

    // ---- auditoria persistente encadeada -----------------------------------------------------------------------------------------------

    @Test
    void persistentAuditIsChainedTamperEvidentAndNeverHoldsSecrets() throws Exception {
        java.nio.file.Path log = f.dir.resolve("audit.log");
        byte[] key = new byte[32];
        AuthAudit audit = new AuthAudit(f.clock, log, key);
        audit.record("LOGIN_OK", f.userId);
        audit.record("LOGOUT", f.userId);
        audit.record("TYPED_ATTEMPT", "someone typed this");
        assertTrue(AuthAudit.verify(log, key).ok());
        assertEquals(3, AuthAudit.verify(log, key).lines());
        String text = Files.readString(log);
        assertFalse(text.contains("someone typed this"), "only the opaque id is ever recorded");
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
        // adulteração: editar, remover linha, reordenar
        String[] lines = text.split("\n");
        Files.writeString(log, text.replace("LOGOUT", "LOGIN_OK"));
        assertFalse(AuthAudit.verify(log, key).ok(), "edited line");
        Files.writeString(log, lines[0] + "\n" + lines[2] + "\n");
        assertFalse(AuthAudit.verify(log, key).ok(), "removed line");
        Files.writeString(log, lines[1] + "\n" + lines[0] + "\n" + lines[2] + "\n");
        assertFalse(AuthAudit.verify(log, key).ok(), "reordered");
        // reabrir um arquivo adulterado: preserva o quebrado e registra o evento
        new AuthAudit(f.clock, log, key);
        try (var s = Files.list(f.dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("audit.log.broken-")), "the tampered file is preserved");
        }
        assertTrue(AuthAudit.verify(log, key).ok());
        assertTrue(Files.readString(log).contains("AUDIT_CHAIN_BROKEN"));
    }

    @Test
    void theServiceWritesLoginRateLimitSecondFactorElevationLogoutAndCredentialEventsToItsOwnAudit() throws Exception {
        java.nio.file.Path log = f.dir.resolve("service-audit.log");
        byte[] key = f.store.derivedKey("audit");
        AuthAudit audit = new AuthAudit(f.clock, log, key);
        AuthRateLimiter lim = new AuthRateLimiter(null, f.store.derivedKey("a"), f.store.derivedKey("b"), f.clock);
        AuthService svc = new AuthService(f.store, new AuthorityAdmin(f.store, AuthFixture.PW, f.clock), AuthFixture.PW, lim, f.second, AuthPolicy.standard(), audit, f.clock);
        for (int i = 0; i < 6; i++) {
            svc.login(PEER, "normal_user", "wrong-password-attempt".toCharArray());
        }
        f.clock.advance(Duration.ofMinutes(6));
        String t = AuthFixture.token(svc.login(PEER, "admin_user", f.adminPw.toCharArray()));
        var begin = svc.beginSecondFactor(PEER, t);
        svc.verifySecondFactor(PEER, t, (String) begin.data().get("challenge"), f.second.last.get(f.adminId));
        svc.sendSecondFactorSms(PEER, t);
        svc.verifySecondFactorSms(PEER, t, f.second.smsCodes.get(f.adminId));
        svc.adminElevation(PEER, t);
        svc.changePassword(PEER, t, f.adminPw.toCharArray(), "a-fresh-admin-pass-123".toCharArray());
        svc.logout(PEER, t);
        String text = Files.readString(log);
        for (String ev : new String[] {"LOGIN_FAILED", "LOGIN_RATE_LIMITED", "LOGIN_OK", "OTP_SENT", "OTP_OK", "SMS_SENT", "SMS_OK", "ELEVATION_GRANTED", "PASSWORD_CHANGED", "LOGOUT"}) {
            assertTrue(text.contains("|" + ev + "|"), ev);
        }
        for (String secret : new String[] {f.adminPw, "a-fresh-admin-pass-123", "wrong-password-attempt", t, f.second.last.get(f.adminId), f.second.smsCodes.get(f.adminId), "admin_user", "normal_user"}) {
            assertFalse(text.contains(secret), "secret-like value in the audit: " + secret.substring(0, 5));
        }
        assertTrue(AuthAudit.verify(log, key).ok());
        assertNotEquals(0, AuthAudit.verify(log, key).lines());
    }
}
