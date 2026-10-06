package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.auth.AccessDecision;
import panel.auth.AuthService;
import panel.auth.CooldownException;
import panel.auth.TwoFactorFlow;
import panel.auth.TwoFactorNotConfiguredException;
import panel.auth.TwoFactorResult;
import panel.localservice.AuthorityGateway;
import panel.security.AccessDeniedException;
import panel.security.Role;
import panel.user.UserService;

/** O painel depois do cutover: só APRESENTA o que o serviço decide. Tudo aqui roda contra o dublê da autoridade (as regras reais estão nos testes do serviço). */
class AuthorityCutoverTest {
    private static final char[] BOSS = "correct-horse-1".toCharArray();

    private static char[] pw() {
        return BOSS.clone();
    }

    private AuthFixture admin() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", pw());
        return f;
    }

    // ---- login: o painel não verifica nada, só mapeia respostas ------------------------------------------------------------------------------

    @Test
    void loginMapsServiceCodesAndNeverTreatsUnavailabilityAsAWrongPassword() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        for (String who : new String[] {"boss", "ghost", "alice"}) {
            var e = assertThrows(AuthService.LoginException.class, () -> f.auth.login(who, "wrong-password-xx".toCharArray()), who);
            assertEquals(AuthService.Failure.RATE_LIMITED == e.failure ? AuthService.Failure.RATE_LIMITED : AuthService.Failure.INVALID_CREDENTIALS, e.failure);
        }
        // o limitador é do SERVIÇO: depois de 4 falhas o serviço responde RATE_LIMITED com o tempo restante
        assertThrows(AuthService.LoginException.class, () -> f.auth.login("boss", "wrong-password-xx".toCharArray())); // 4ª falha
        var limited = assertThrows(AuthService.LoginException.class, () -> f.auth.login("boss", "wrong-password-xx".toCharArray()));
        assertEquals(AuthService.Failure.RATE_LIMITED, limited.failure);
        assertTrue(limited.retryAfter.toSeconds() >= 1);
        f.clock.advance(Duration.ofSeconds(45));
        f.authority.unavailable = true;
        assertThrows(IllegalStateException.class, () -> f.auth.login("boss", pw()), "service down: unavailable, never 'invalid credentials'");
        f.authority.unavailable = false;
        var u = f.auth.login("boss", pw());
        assertEquals(Role.ADMIN, u.role());
        assertEquals("", u.passwordHash(), "the panel never holds a verifier");
        assertEquals("correct-horse-1".length(), pw().length);
        assertTrue(f.sessions.user().isPresent());
    }

    @Test
    void thePanelCannotChooseItsRoleAndAUserSessionIsNotAdmin() {
        AuthFixture f = AuthFixture.ready();
        f.seedUser();
        f.auth.login("alice", "temporary-pass-1".toCharArray());
        assertEquals(Role.USER, f.sessions.user().orElseThrow().user().role());
        assertEquals(AccessDecision.FORBIDDEN_NOT_ADMIN, f.access.evaluate());
        assertFalse(f.access.hasValidAdminSession());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        // nenhum método do gateway recebe papel, userId, admin ou mfa
        for (var m : AuthorityGateway.class.getMethods()) {
            for (var p : m.getParameters()) {
                String n = p.getName().toLowerCase();
                assertFalse(n.contains("role") || n.contains("userid") || n.contains("admin") || n.contains("mfa"), m.getName() + " takes " + n);
            }
        }
    }

    @Test
    void theServiceRevokingTheSessionEndsTheLocalRepresentation() {
        AuthFixture f = admin();
        f.authorize();
        assertTrue(f.access.hasValidAdminSession());
        f.authority.demote("boss"); // o serviço rebaixa a conta
        assertEquals(AccessDecision.FORBIDDEN_NOT_ADMIN, f.access.evaluate(), "the next read reflects the demotion");
        assertFalse(f.access.hasValidAdminSession(), "the elevation vanished with the role");
        f.authority.add("carol", "carol@example.test", null, "carol-pass-1234", Role.USER, false);
        f.auth.login("carol", "carol-pass-1234".toCharArray());
        f.authority.disable("carol");
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
        assertTrue(f.sessions.user().isEmpty(), "disabled account: the panel is logged out");
    }

    @Test
    void aServiceRestartLogsThePanelOutAndNothingRebuildsTheSession() {
        AuthFixture f = admin();
        f.authorize();
        f.authority.restartService();
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
        assertTrue(f.sessions.user().isEmpty());
        assertFalse(f.access.hasValidAdminSession());
        assertFalse(f.authority.hasSession());
    }

    @Test
    void logoutRevokesInTheServiceAndForgetsEverythingLocally() {
        AuthFixture f = admin();
        f.authorize();
        f.auth.logout();
        assertTrue(f.authority.calls.contains("logout"));
        assertTrue(f.sessions.user().isEmpty());
        assertFalse(f.access.hasValidAdminSession());
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
    }

    @Test
    void ifTheServiceIsNotUpYetLoginAsksTheBundleLauncherToStartItAndRetriesOnceWithTheSamePassword() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        int[] started = {0};
        AuthorityGateway flaky = new AuthorityGateway() {
            boolean up;

            @Override public Reply login(String id, char[] p) {
                if (!up) {
                    java.util.Arrays.fill(p, '\0'); // como o cliente real: a senha da 1ª tentativa é zerada
                    return new Reply(false, "not_started", null);
                }
                return f.authority.login(id, p);
            }
            @Override public boolean ensureService() { started[0]++; up = true; return true; }
            @Override public Reply sessionStatus() { return f.authority.sessionStatus(); }
            @Override public Reply beginSecondFactor() { return f.authority.beginSecondFactor(); }
            @Override public Reply verifySecondFactor(String c, String code) { return f.authority.verifySecondFactor(c, code); }
            @Override public Reply sendSecondFactorSms() { return f.authority.sendSecondFactorSms(); }
            @Override public Reply verifySecondFactorSms(String c) { return f.authority.verifySecondFactorSms(c); }
            @Override public Reply adminElevation() { return f.authority.adminElevation(); }
            @Override public Reply changePassword(char[] a, char[] b) { return f.authority.changePassword(a, b); }
            @Override public Reply enrollTrustedDevice() { return f.authority.enrollTrustedDevice(); }
            @Override public Reply listTrustedDevices() { return f.authority.listTrustedDevices(); }
            @Override public Reply revokeTrustedDevice(String id) { return f.authority.revokeTrustedDevice(id); }
            @Override public Reply logout() { return f.authority.logout(); }
            @Override public boolean hasSession() { return f.authority.hasSession(); }
            @Override public void forgetSession() { f.authority.forgetSession(); }
        };
        var svc = new AuthService(flaky, f.sessions, f.clock);
        assertEquals("boss", svc.login("boss", pw()).username());
        assertEquals(1, started[0]);
    }

    // ---- segundo fator de dois estágios -----------------------------------------------------------------------------------------------------

    @Test
    void theSecondFactorHasTwoStagesDecidedByTheServiceAndTheCodeNeverTravelsThroughThePanel() {
        AuthFixture f = admin();
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
        TwoFactorFlow flow = f.access.startTwoFactor();
        flow.sendEmailCode();
        assertEquals(TwoFactorResult.INVALID, flow.verifyEmail("000000".equals(f.authority.lastEmailCode()) ? "000001" : "000000"));
        assertEquals(TwoFactorResult.OK, flow.verifyEmail(f.authority.lastEmailCode()));
        assertFalse(f.access.hasValidAdminSession(), "e-mail alone grants nothing");
        assertEquals(TwoFactorResult.NO_CHALLENGE, flow.verifySms("123456"), "SMS before it was sent");
        flow.sendSmsCode();
        assertEquals(TwoFactorResult.INVALID, flow.verifySms("000000".equals(f.authority.lastSmsCode()) ? "000001" : "000000"));
        assertEquals(TwoFactorResult.OK, flow.verifySms(f.authority.lastSmsCode()));
        assertTrue(flow.complete());
        assertTrue(f.access.hasValidAdminSession(), "the elevation was granted by the SERVICE after both stages");
        for (var m : TwoFactorFlow.class.getMethods()) {
            assertFalse(m.getReturnType().equals(String.class) && m.getName().toLowerCase().contains("code"), m.getName() + " must not expose a code");
        }
        assertEquals(0, java.util.Arrays.stream(TwoFactorFlow.class.getDeclaredFields()).filter(x -> x.getName().toLowerCase().contains("code")).count(), "the flow keeps no code field");
    }

    @Test
    void notConfiguredAndCooldownAreMappedFromTheService() {
        AuthFixture f = new AuthFixture(false);
        f.seedAdmin();
        f.auth.login("boss", pw());
        var flow = f.access.startTwoFactor();
        assertThrows(TwoFactorNotConfiguredException.class, flow::sendEmailCode);
        assertFalse(f.access.twoFactorConfigured() && !f.authority.configured);
        AuthFixture g = admin();
        var flow2 = g.access.startTwoFactor();
        flow2.sendEmailCode();
        assertTrue(flow2.resendSeconds(false) >= 1, "resend wait is the service's, not a panel rule");
    }

    // ---- dispositivo confiável (do serviço) e janela de segurança --------------------------------------------------------------------------------

    @Test
    void trustedDevicesLiveInTheServiceButNeverSubstituteMfaForElevation() {
        AuthFixture f = admin();
        TwoFactorFlow flow = f.access.startTwoFactor();
        flow.sendEmailCode();
        flow.verifyEmail(f.authority.lastEmailCode());
        flow.sendSmsCode();
        flow.verifySms(f.authority.lastSmsCode());
        flow.finish(true); // pede ao serviço para lembrar este Mac
        assertEquals(1, f.devices.list().size());
        f.auth.logout();
        f.auth.login("boss", pw());
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
        assertFalse(f.access.tryTrustedDevice(), "trusted device without a recent OTP cannot elevate");
        assertFalse(f.access.hasValidAdminSession());
        f.authorize();
        var id = f.devices.list().get(0).id();
        f.devices.revoke(id);
        assertFalse(f.access.hasValidAdminSession(), "revoking ends the elevation");
        f.auth.logout();
        f.auth.login("boss", pw());
        assertFalse(f.access.tryTrustedDevice());
    }

    @Test
    void duringTheCutoverSafetyWindowTheServiceRefusesEnrolmentAndPasswordChange() {
        AuthFixture f = admin();
        f.authority.frozen = true;
        TwoFactorFlow flow = f.access.startTwoFactor();
        flow.sendEmailCode();
        flow.verifyEmail(f.authority.lastEmailCode());
        flow.sendSmsCode();
        flow.verifySms(f.authority.lastSmsCode());
        var e = assertThrows(AccessDeniedException.class, () -> flow.finish(true));
        assertTrue(e.getMessage().contains("cutover"), e.getMessage());
        var p = assertThrows(AccessDeniedException.class, () -> f.userService.changeOwnPassword(1, pw(), "a-brand-new-pass-9".toCharArray()));
        assertTrue(p.getMessage().contains("cutover"), p.getMessage());
        assertTrue(f.access.hasValidAdminSession(), "login/2FA still work in the window");
    }

    // ---- troca de senha e administração de contas ----------------------------------------------------------------------------------------------

    @Test
    void ownPasswordChangeGoesToTheServiceAndMapsItsAnswers() {
        AuthFixture f = admin();
        var wrong = assertThrows(IllegalArgumentException.class, () -> f.userService.changeOwnPassword(1, "not-the-password".toCharArray(), "a-brand-new-pass-9".toCharArray()));
        assertEquals("Current password is incorrect.", wrong.getMessage());
        assertThrows(IllegalArgumentException.class, () -> f.userService.changeOwnPassword(1, pw(), "short".toCharArray()), "policy checked before anything is sent");
        long callsBefore = f.authority.calls.stream().filter(c -> c.equals("changePassword")).count();
        f.userService.changeOwnPassword(1, pw(), "a-brand-new-pass-9".toCharArray());
        assertEquals(callsBefore + 1, f.authority.calls.stream().filter(c -> c.equals("changePassword")).count());
        f.auth.logout();
        assertThrows(AuthService.LoginException.class, () -> f.auth.login("boss", pw()), "the old password no longer works in the authority");
        assertEquals("boss", f.auth.login("boss", "a-brand-new-pass-9".toCharArray()).username());
    }

    @Test
    void accountAdministrationIsUnavailableAndNeverTouchesAnyStore() {
        AuthFixture f = admin();
        f.authorize();
        int calls = f.authority.calls.size();
        for (Runnable op : List.<Runnable>of(() -> f.userService.createUser("x", "x@example.test", "temporary-pass-1".toCharArray(), null, Role.USER), f.userService::listUsers,
                () -> f.userService.setStatus(2, panel.user.UserStatus.DISABLED), () -> f.userService.changeRole(2, Role.ADMIN), () -> f.userService.resetPassword(2, "temporary-pass-1".toCharArray()),
                () -> f.userService.changeOwnContact(1, pw(), "new@example.test", "+5511999990000"), () -> f.userService.createInitialAdmin("x", "x@example.test", pw(), null))) {
            var e = assertThrows(AccessDeniedException.class, op::run);
            assertTrue(e.getMessage().contains("unavailable") || e.getMessage().contains("no longer available"), e.getMessage());
        }
        assertEquals(calls, f.authority.calls.size(), "none of them reached the authority");
        assertTrue(UserService.UNAVAILABLE.contains("cutover"));
    }

    @Test
    void thePanelHoldsNoPasswordNorTokenInItsPresentationObjects() {
        AuthFixture f = admin();
        String text = f.sessions.user().orElseThrow() + " " + f.auth + " " + f.access + " " + f.authority.hasSession();
        assertFalse(text.contains("correct-horse-1") || text.contains("T".repeat(43)));
    }
}
