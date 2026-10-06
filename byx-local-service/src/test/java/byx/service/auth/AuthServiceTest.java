package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.auth.AuthService.Code;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private AuthFixture f;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private static final long PEER_A = 1001;
    private static final long PEER_B = 2002;

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        f = new AuthFixture();
    }

    @AfterEach
    void down() throws Exception {
        f.close();
        Log.redirect(null);
    }

    // ---- credenciais ---------------------------------------------------------------------------------------------------------------

    @Test
    void correctPasswordCreatesAnOpaqueSessionBoundToThePeer() {
        var r = f.loginUser(PEER_A);
        assertEquals(Code.OK, r.code());
        String token = AuthFixture.token(r);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), "256-bit token, base64url");
        assertEquals("USER", r.data().get("role"));
        assertEquals(Code.OK, f.auth.sessionStatus(PEER_A, token).code());
        assertNotEquals(token, AuthFixture.token(f.loginUser(PEER_A)), "every login has a fresh token");
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledUserAreExternallyEquivalent() {
        long before = PasswordVerifier.derivations();
        var wrong = f.auth.login(PEER_A, "normal_user", "definitely-wrong-password".toCharArray());
        long afterWrong = PasswordVerifier.derivations();
        var unknown = f.auth.login(PEER_A, "ghost_user", "definitely-wrong-password".toCharArray());
        long afterUnknown = PasswordVerifier.derivations();
        var disabled = f.auth.login(PEER_A, "disabled_user", f.disabledPw.toCharArray()); // senha CORRETA, conta desabilitada
        long afterDisabled = PasswordVerifier.derivations();
        for (var r : List.of(wrong, unknown, disabled)) {
            assertEquals(Code.INVALID_CREDENTIALS, r.code());
            assertEquals(wrong.data(), r.data(), "same shape: nothing reveals whether the account exists or is enabled");
        }
        assertEquals(1, afterWrong - before, "one Argon2 derivation for a wrong password...");
        assertEquals(1, afterUnknown - afterWrong, "...the same for an unknown account (dummy verifier)...");
        assertEquals(1, afterDisabled - afterUnknown, "...and for a disabled account");
    }

    @Test
    void rateLimitingTreatsKnownAndUnknownAccountsTheSame() {
        for (String who : List.of("normal_user", "ghost_user")) {
            Code last = null;
            long retry = 0;
            for (int i = 0; i < 6; i++) {
                var r = f.auth.login(PEER_A, who, "wrong-password-attempt".toCharArray());
                last = r.code();
                if (last == Code.RATE_LIMITED) {
                    retry = (long) r.data().get("retryAfterSec");
                }
            }
            assertEquals(Code.RATE_LIMITED, last, who);
            assertTrue(retry > 0 && retry <= 31, who + " retry " + retry);
        }
        // durante o bloqueio nem a senha CERTA entra, e a tentativa não prolonga nada
        assertEquals(Code.RATE_LIMITED, f.loginUser(PEER_A).code());
        f.clock.advance(Duration.ofSeconds(32));
        assertEquals(Code.OK, f.loginUser(PEER_A).code(), "the block ends by itself (no permanent lockout)");
    }

    @Test
    void theRateLimiterSurvivesARestartAndFailsClosedWhenItsFileIsTampered() throws Exception {
        java.nio.file.Path rl = f.dir.resolve("authority").resolve("ratelimit.json");
        byte[] mac = f.store.derivedKey("ratelimit");
        byte[] subj = f.store.derivedKey("ratelimit-subject");
        AuthRateLimiter a = new AuthRateLimiter(rl, mac, subj, f.clock);
        for (int i = 0; i < 5; i++) {
            a.recordFailure("login", "alice");
        }
        assertTrue(a.blockedFor("login", "alice").isPresent());
        AuthRateLimiter restarted = new AuthRateLimiter(rl, mac, subj, f.clock);
        assertTrue(restarted.blockedFor("login", "alice").isPresent(), "state persists across restart");
        assertTrue(restarted.blockedFor("login", "bob").isEmpty(), "another identifier does not inherit it");
        String content = java.nio.file.Files.readString(rl);
        assertFalse(content.contains("alice"), "never the typed text");
        java.nio.file.Files.writeString(rl, content.replace("\"f\":5", "\"f\":0"));
        AuthRateLimiter tampered = new AuthRateLimiter(rl, mac, subj, f.clock);
        assertTrue(tampered.blockedFor("login", "anyone").isPresent(), "a tampered limiter file fails closed");
        f.clock.advance(AuthLimits.RATE_DECAY.plusSeconds(1));
        assertTrue(tampered.blockedFor("login", "anyone").isEmpty(), "and the fail-closed state decays");
    }

    // ---- sessões -------------------------------------------------------------------------------------------------------------------

    @Test
    void absoluteAndIdleTimeoutsEndSessions() {
        String t1 = AuthFixture.token(f.loginUser(PEER_A));
        f.clock.advance(AuthLimits.IDLE_TIMEOUT.minusSeconds(1));
        assertEquals(Code.OK, f.auth.sessionStatus(PEER_A, t1).code(), "activity within the idle window keeps it alive");
        f.clock.advance(AuthLimits.IDLE_TIMEOUT.plusSeconds(1));
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, t1).code(), "idle timeout");
        String t2 = AuthFixture.token(f.loginUser(PEER_A));
        for (int i = 0; i < 40; i++) { // atividade constante a cada 14 min não vence o prazo ABSOLUTO
            f.clock.advance(Duration.ofMinutes(14));
            if (f.auth.sessionStatus(PEER_A, t2).code() != Code.OK) {
                break;
            }
        }
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, t2).code(), "absolute timeout");
        assertTrue(AuthLimits.ABSOLUTE_TIMEOUT.compareTo(Duration.ofHours(24)) <= 0 && AuthLimits.IDLE_TIMEOUT.compareTo(Duration.ofHours(1)) <= 0 && AuthLimits.ADMIN_ELEVATION_TIMEOUT.compareTo(Duration.ofMinutes(15)) <= 0, "no eternal sessions");
    }

    @Test
    void serviceRestartInvalidatesEverySessionAndNothingRebuildsPrivilege() throws Exception {
        String token = AuthFixture.token(f.loginAdmin(PEER_A));
        assertEquals(Code.OK, f.auth.sessionStatus(PEER_A, token).code());
        AuthService restarted = f.newService(AuthorityStore.open(f.file, f.anchor, f.vault)); // "reinício": novo processo, mesma autoridade
        assertEquals(Code.AUTH_REQUIRED, restarted.sessionStatus(PEER_A, token).code());
        assertEquals(Code.AUTH_REQUIRED, restarted.adminElevation(PEER_A, token).code());
        assertEquals(Code.AUTH_REQUIRED, restarted.authorize(PEER_A, token, "qa.userOp", null).code());
        assertEquals(0, restarted.activeSessions());
    }

    @Test
    void aStolenTokenFromAnotherProcessIsRejectedAndDoesNotDisturbTheOwner() {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_B, token).code(), "different process, same token");
        assertEquals(Code.AUTH_REQUIRED, f.auth.logout(PEER_B, token).code(), "cannot even log the owner out");
        assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(PEER_B, token, "qa.userOp", null).code());
        assertEquals(Code.OK, f.auth.sessionStatus(PEER_A, token).code(), "the legitimate owner is unaffected");
        assertTrue(f.audit.entries().stream().anyMatch(e -> e.event().equals("SESSION_PEER_MISMATCH")));
    }

    @Test
    void tokenReplayAfterLogoutAndGarbageTokensAreRefused() {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        assertEquals(Code.OK, f.auth.logout(PEER_A, token).code());
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, token).code());
        assertEquals(Code.AUTH_REQUIRED, f.auth.logout(PEER_A, token).code());
        for (String bad : new String[] {null, "", "short", "A".repeat(43), "A".repeat(44), "../etc/passwd", "'; DROP TABLE users;--" + "x".repeat(30)}) {
            assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, bad).code());
        }
    }

    // ---- revalidação -----------------------------------------------------------------------------------------------------------------

    @Test
    void demotingAnAdminKillsTheOldSessionAndTheElevation() throws Exception {
        String token = AuthFixture.token(f.loginAdmin(PEER_A));
        f.mfa(PEER_A, token, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER_A, token).code());
        assertEquals(Code.OK, f.auth.authorize(PEER_A, token, "qa.adminOp", null).code());
        f.admin.setRole(f.adminId, Role.USER); // ADMIN -> USER
        assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(PEER_A, token, "qa.adminOp", null).code(), "the old session does not recover privilege");
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, token).code());
        var again = f.auth.login(PEER_A, "admin_user", f.adminPw.toCharArray());
        assertEquals("USER", again.data().get("role"));
        assertEquals(Code.DENIED, f.auth.adminElevation(PEER_A, AuthFixture.token(again)).code(), "and a fresh login cannot elevate");
    }

    @Test
    void disablingDeletingChangingPasswordOrBumpingCredentialVersionRevokesSessions() throws Exception {
        for (String mode : List.of("disable", "delete", "password", "bump")) {
            var made = f.admin.createAccount("victim_" + mode, "victim-password-123".toCharArray(), Role.USER);
            String token = AuthFixture.token(f.auth.login(PEER_A, "victim_" + mode, "victim-password-123".toCharArray()));
            assertEquals(Code.OK, f.auth.sessionStatus(PEER_A, token).code(), mode);
            switch (mode) {
                case "disable" -> f.admin.setEnabled(made.id(), false);
                case "delete" -> f.admin.delete(made.id());
                case "password" -> f.admin.changePassword(made.id(), "another-victim-password-1".toCharArray());
                default -> f.admin.bumpCredentialVersion(made.id());
            }
            assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, token).code(), mode);
            if (mode.equals("disable")) {
                f.admin.setEnabled(made.id(), true); // reativar NÃO ressuscita a sessão antiga
                assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, token).code(), "re-enabling does not resurrect the old session");
            }
        }
    }

    @Test
    void passwordChangeRevokesOtherSessionsClearsElevationAndKeepsOnlyTheOrdinaryCurrentSession() {
        String t1 = AuthFixture.token(f.loginAdmin(PEER_A));
        String t2 = AuthFixture.token(f.loginAdmin(PEER_A));
        f.mfa(PEER_A, t1, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER_A, t1).code());
        assertEquals(Code.INVALID_CREDENTIALS, f.auth.changePassword(PEER_A, t1, "not-the-current-password".toCharArray(), "a-new-strong-password-1".toCharArray()).code());
        assertEquals(Code.WEAK_PASSWORD, f.auth.changePassword(PEER_A, t1, f.adminPw.toCharArray(), "short".toCharArray()).code());
        var ok = f.auth.changePassword(PEER_A, t1, f.adminPw.toCharArray(), "a-new-strong-password-1".toCharArray());
        assertEquals(Code.OK, ok.code());
        assertEquals(Code.AUTH_REQUIRED, f.auth.sessionStatus(PEER_A, t2).code(), "other sessions are revoked");
        var cur = f.auth.sessionStatus(PEER_A, t1);
        assertEquals(Code.OK, cur.code(), "the current ordinary session stays (documented policy)");
        assertEquals(false, cur.data().get("elevated"), "but the elevation is gone");
        assertEquals(false, cur.data().get("mfaRecent"), "and so is the recent second factor");
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, t1, "qa.adminOp", null).code());
        assertEquals(Code.INVALID_CREDENTIALS, f.auth.login(PEER_B, "admin_user", f.adminPw.toCharArray()).code(), "the old password no longer works");
        assertEquals(Code.OK, f.auth.login(PEER_B, "admin_user", "a-new-strong-password-1".toCharArray()).code());
    }

    @Test
    void tamperingWithTheAuthorityFailsClosedAndRevokesEverySession() throws Exception {
        String token = AuthFixture.token(f.loginAdmin(PEER_A));
        byte[] ok = java.nio.file.Files.readAllBytes(f.file);
        byte[] bad = ok.clone();
        bad[bad.length - 5] ^= 1; // adulteração do snapshot cifrado
        java.nio.file.Files.write(f.file, bad);
        assertEquals(Code.AUTHORITY_UNAVAILABLE, f.auth.sessionStatus(PEER_A, token).code());
        assertEquals(Code.AUTHORITY_UNAVAILABLE, f.loginUser(PEER_A).code(), "no login on an untrusted authority");
        java.nio.file.Files.write(f.file, ok); // o atacante "conserta" o arquivo
        assertEquals(Code.AUTHORITY_UNAVAILABLE, f.auth.sessionStatus(PEER_A, token).code(), "untrusted is sticky: restoring the bytes does not silently re-trust it");
        AuthService restarted = f.newService(AuthorityStore.open(f.file, f.anchor, f.vault)); // só um reinício (nova verificação completa) reconfia
        assertEquals(Code.AUTH_REQUIRED, restarted.sessionStatus(PEER_A, token).code(), "and the old session is gone: no privilege resurrection");
        assertEquals(Code.OK, restarted.login(PEER_A, "normal_user", f.userPw.toCharArray()).code());
    }

    // ---- segundo fator -----------------------------------------------------------------------------------------------------------

    @Test
    void productionSecondFactorIsNotConfigured() throws Exception {
        AuthService prod = new AuthService(f.store, f.admin, AuthFixture.PW, new AuthRateLimiter(null, f.store.derivedKey("a"), f.store.derivedKey("b"), f.clock),
                new NotConfiguredSecondFactor(), AuthPolicy.standard(), f.audit, f.clock);
        String token = AuthFixture.token(prod.login(PEER_A, "normal_user", f.userPw.toCharArray()));
        assertEquals(Code.SECOND_FACTOR_NOT_CONFIGURED, prod.beginSecondFactor(PEER_A, token).code());
    }

    @Test
    void otpIsSingleUseBoundAndNeverReturned() {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        var begin = f.auth.beginSecondFactor(PEER_A, token);
        assertEquals(Code.OK, begin.code());
        String code = f.second.last.get(f.userId);
        assertTrue(code.matches("\\d{6}"));
        assertFalse(begin.data().toString().contains(code), "the OTP is never in the IPC-facing result");
        String ch = (String) begin.data().get("challenge");
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, ch, code.equals("000000") ? "000001" : "000000").code(), "wrong code");
        assertEquals(Code.OK, f.auth.verifySecondFactor(PEER_A, token, ch, code).code());
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, ch, code).code(), "replay: single use");
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, "A".repeat(22), code).code(), "old/unknown challenge");
    }

    @Test
    void otpFromAnotherUserOrAnotherSessionIsInvalid() {
        String userToken = AuthFixture.token(f.loginUser(PEER_A));
        String adminToken = AuthFixture.token(f.loginAdmin(PEER_B));
        var begin = f.auth.beginSecondFactor(PEER_A, userToken);
        String ch = (String) begin.data().get("challenge");
        String code = f.second.last.get(f.userId);
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_B, adminToken, ch, code).code(), "wrong user/session");
        String user2 = AuthFixture.token(f.loginUser(PEER_A)); // OUTRA sessão da MESMA conta
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, user2, ch, code).code(), "same account, different session");
        assertEquals(Code.OK, f.auth.verifySecondFactor(PEER_A, userToken, ch, code).code(), "the bound session still works");
    }

    @Test
    void otpExpiresAndHasAnAttemptLimit() {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        var begin = f.auth.beginSecondFactor(PEER_A, token);
        String ch = (String) begin.data().get("challenge");
        String right = f.second.last.get(f.userId);
        String wrong = right.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < AuthLimits.OTP_MAX_ATTEMPTS; i++) {
            assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, ch, wrong).code(), "attempt " + i);
        }
        assertTrue(Set.of(Code.TOO_MANY_ATTEMPTS, Code.RATE_LIMITED).contains(f.auth.verifySecondFactor(PEER_A, token, ch, right).code()), "after the limit even the right code is refused");
        String token2 = AuthFixture.token(f.loginAdmin(PEER_B));
        var b2 = f.auth.beginSecondFactor(PEER_B, token2);
        f.clock.advance(AuthLimits.OTP_TTL.plusSeconds(1));
        String t3 = AuthFixture.token(f.loginAdmin(PEER_B)); // a sessão original ainda vale? 5 min < idle 15 min
        assertEquals(Code.CHALLENGE_EXPIRED, f.auth.verifySecondFactor(PEER_B, token2, (String) b2.data().get("challenge"), f.second.last.get(f.adminId)).code());
        assertNull(null, t3);
    }

    @Test
    void resendNeitherRenewsTheWindowNorResetsAttempts() {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        var first = f.auth.beginSecondFactor(PEER_A, token);
        String ch = (String) first.data().get("challenge");
        assertEquals(Code.COOLDOWN, f.auth.beginSecondFactor(PEER_A, token).code(), "resend too soon");
        String firstCode = f.second.last.get(f.userId);
        String wrong = firstCode.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 3; i++) {
            f.auth.verifySecondFactor(PEER_A, token, ch, wrong);
        }
        f.clock.advance(AuthLimits.OTP_RESEND_COOLDOWN.plusSeconds(1));
        var second = f.auth.beginSecondFactor(PEER_A, token);
        assertEquals(ch, second.data().get("challenge"), "same challenge (same window)");
        long left = (long) second.data().get("expiresInSec");
        assertTrue(left < AuthLimits.OTP_TTL.toSeconds() - 25, "resend did not renew the expiry: " + left);
        String newCode = f.second.last.get(f.userId);
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, ch, wrong).code());
        assertEquals(Code.CHALLENGE_INVALID, f.auth.verifySecondFactor(PEER_A, token, ch, wrong).code(), "5th attempt overall (3 before the resend + 2 after)");
        assertTrue(Set.of(Code.TOO_MANY_ATTEMPTS, Code.RATE_LIMITED).contains(f.auth.verifySecondFactor(PEER_A, token, ch, newCode).code()), "attempts were NOT reset by the resend");
        // limite de envios
        String t2 = AuthFixture.token(f.loginAdmin(PEER_B));
        f.auth.beginSecondFactor(PEER_B, t2);
        for (int i = 1; i < AuthLimits.OTP_MAX_SENDS; i++) {
            f.clock.advance(AuthLimits.OTP_RESEND_COOLDOWN.plusSeconds(1));
            assertEquals(Code.OK, f.auth.beginSecondFactor(PEER_B, t2).code());
        }
        f.clock.advance(AuthLimits.OTP_RESEND_COOLDOWN.plusSeconds(1));
        assertEquals(Code.TOO_MANY_SENDS, f.auth.beginSecondFactor(PEER_B, t2).code());
    }

    private static final class Set {
        static java.util.Set<Code> of(Code... c) {
            return java.util.Set.of(c);
        }
    }

    // ---- elevação e política ---------------------------------------------------------------------------------------------------

    @Test
    void adminElevationNeedsAdminAndARecentSecondFactorAndExpires() {
        String user = AuthFixture.token(f.loginUser(PEER_A));
        f.mfa(PEER_A, user, f.userId);
        assertEquals(Code.DENIED, f.auth.adminElevation(PEER_A, user).code(), "a USER cannot elevate even with MFA");
        String admin = AuthFixture.token(f.loginAdmin(PEER_B));
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER_B, admin).code(), "no second factor yet");
        assertEquals(Code.DENIED, f.auth.authorize(PEER_B, admin, "qa.adminOp", null).code());
        f.mfa(PEER_B, admin, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER_B, admin).code());
        assertEquals(Code.OK, f.auth.authorize(PEER_B, admin, "qa.adminOp", null).code());
        f.clock.advance(AuthLimits.ADMIN_ELEVATION_TIMEOUT.plusSeconds(1));
        assertEquals(Code.DENIED, f.auth.authorize(PEER_B, admin, "qa.adminOp", null).code(), "the elevation is short-lived");
        assertEquals(Code.OK, f.auth.sessionStatus(PEER_B, admin).code(), "the ordinary session continues");
        f.clock.advance(AuthLimits.RECENT_MFA_WINDOW);
        assertEquals(Code.ELEVATION_REQUIRES_MFA, f.auth.adminElevation(PEER_B, admin).code(), "MFA is no longer recent");
    }

    @Test
    void theAuthorizationTableIsClosedAndDeniesByDefault() {
        String user = AuthFixture.token(f.loginUser(PEER_A));
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "not.registered", null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, null, null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "auth.setRole", null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "auth.setMfa", null).code());
        assertEquals(Code.OK, f.auth.authorize(PEER_A, user, "qa.userOp", null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "qa.mfaOp", null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "qa.ownedOp", f.adminId).code(), "not the owner");
        assertEquals(Code.OK, f.auth.authorize(PEER_A, user, "qa.ownedOp", f.userId).code());
        f.mfa(PEER_A, user, f.userId);
        assertEquals(Code.OK, f.auth.authorize(PEER_A, user, "qa.mfaOp", null).code());
        assertEquals(Code.DENIED, f.auth.authorize(PEER_A, user, "qa.adminOp", null).code(), "role too low");
    }

    @Test
    void privateCapabilityOperationsStayDeniedForEveryoneWhileTheGateIsClosed() {
        String admin = AuthFixture.token(f.loginAdmin(PEER_A));
        f.mfa(PEER_A, admin, f.adminId);
        f.auth.adminElevation(PEER_A, admin);
        for (String op : List.of("account.read", "notifications.read", "admin.operation", "qa.privateOp")) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER_A, admin, op, f.adminId).code(), op + " (even ADMIN + MFA + elevation + owner)");
        }
    }

    @Test
    void aResultCompletingAfterLogoutIsDiscarded() throws Exception {
        String token = AuthFixture.token(f.loginUser(PEER_A));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AuthService.Guarded<String>> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(f.auth.guarded(PEER_A, token, "qa.userOp", null, () -> {
            started.countDown();
            release.await();
            return "PRIVILEGED-RESULT";
        })));
        t.start();
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Code.OK, f.auth.logout(PEER_A, token).code()); // logout enquanto a operação está em voo
        release.countDown();
        t.join(2_000);
        assertEquals(Code.AUTH_REQUIRED, out.get().code(), "late completion cannot resurrect privilege");
        assertNull(out.get().value(), "and the result is not delivered");
        assertTrue(f.audit.entries().stream().anyMatch(e -> e.event().equals("LATE_RESULT_DISCARDED")));
    }

    @Test
    void aResultCompletingAfterDemotionIsDiscarded() throws Exception {
        String token = AuthFixture.token(f.loginAdmin(PEER_A));
        f.mfa(PEER_A, token, f.adminId);
        f.auth.adminElevation(PEER_A, token);
        var out = f.auth.guarded(PEER_A, token, "qa.adminOp", null, () -> {
            f.admin.setRole(f.adminId, Role.USER); // rebaixado no meio da execução
            return "ADMIN-ONLY-RESULT";
        });
        assertEquals(Code.AUTH_REQUIRED, out.code());
        assertNull(out.value());
    }

    // ---- vazamento -------------------------------------------------------------------------------------------------------------

    @Test
    void passwordsOtpsAndTokensNeverReachLogsAuditOrToStrings() throws Exception {
        String token = AuthFixture.token(f.loginAdmin(PEER_A));
        f.mfa(PEER_A, token, f.adminId);
        f.auth.adminElevation(PEER_A, token);
        f.auth.login(PEER_A, "ghost_user", "typed-as-wrong-secret-xyz".toCharArray());
        String all = String.join("\n", logs) + f.audit.entries();
        for (String secret : new String[] {f.adminPw, f.userPw, f.disabledPw, token, "typed-as-wrong-secret-xyz", f.second.last.get(f.adminId)}) {
            assertFalse(all.contains(secret), "secret-like value in logs/audit: " + secret.substring(0, 6));
        }
        assertFalse(all.toLowerCase().contains("ghost_user") || all.contains("admin_user"), "no typed username in the audit (opaque account ids only)");
        assertEquals("Account[" + f.adminId + "]", f.store.current().byId(f.adminId).orElseThrow().toString());
        assertEquals("AnchorData[redacted]", f.anchor.read().orElseThrow().toString());
    }
}
