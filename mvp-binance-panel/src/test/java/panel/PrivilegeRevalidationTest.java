package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import panel.auth.AccessDecision;
import panel.auth.AdminAccessService;
import panel.auth.OtpService;
import panel.auth.SessionManager;
import panel.security.AccessDeniedException;
import panel.security.Role;
import panel.security.SecurityConfig;
import panel.user.User;
import panel.user.UserRepository;
import panel.user.UserStatus;

/**
 * L7: a validade do privilégio é decidida contra o repositório (a fonte), não contra a cópia da sessão. Contas e banco temporários
 * em memória; nada aqui toca a conta ou o banco reais do usuário.
 */
class PrivilegeRevalidationTest {
    private static final char[] PW = "correct-horse-1".toCharArray();

    private static AuthFixture elevatedAdmin() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", PW);
        f.authorize();
        assertTrue(f.access.hasValidAdminSession());
        return f;
    }

    private static User boss(AuthFixture f) {
        return f.users.findByUsernameOrEmail("boss").orElseThrow();
    }

    private static User with(User u, Role role, UserStatus status) {
        return new User(u.id(), u.username(), u.email(), u.passwordHash(), role, status, u.phone(), u.emailVerified(), u.phoneVerified(), u.mustChangePassword(),
                u.createdAt(), u.updatedAt(), u.lastLoginAt());
    }

    @Test
    void disablingTheAccountInvalidatesAllAccessOnTheNextProtectedCall() {
        AuthFixture f = elevatedAdmin();
        f.users.update(with(boss(f), Role.ADMIN, UserStatus.DISABLED)); // desativada por fora desta sessão
        assertFalse(f.access.hasValidAdminSession());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertTrue(f.sessions.user().isEmpty(), "a disabled account loses even the normal session");
        assertEquals(AccessDecision.SESSION_EXPIRED, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.devices::list);
    }

    @Test
    void demotionRemovesAdminPrivilegeAndTheElevationForGood() {
        AuthFixture f = elevatedAdmin();
        f.users.update(with(boss(f), Role.USER, UserStatus.ACTIVE));
        assertFalse(f.access.hasValidAdminSession());
        assertTrue(f.sessions.admin().isEmpty(), "the elevation itself is revoked, not just hidden");
        assertTrue(f.sessions.user().isPresent() && !f.sessions.user().get().user().admin(), "the user stays signed in as a non-admin");
        assertEquals(AccessDecision.FORBIDDEN_NOT_ADMIN, f.access.evaluate());
        assertThrows(AccessDeniedException.class, f.access::requireAdmin);
        assertThrows(AccessDeniedException.class, f.devices::list);
        assertFalse(f.access.tryTrustedDevice());
        f.users.update(with(boss(f), Role.ADMIN, UserStatus.ACTIVE)); // promovida de novo: a elevação antiga NÃO volta
        assertFalse(f.access.hasValidAdminSession(), "re-promotion never resurrects a revoked elevation");
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
    }

    @Test
    void changingThePasswordEndsTheElevationButNotTheSession() {
        AuthFixture f = elevatedAdmin();
        f.userService.changeOwnPassword(boss(f).id(), PW, "another-horse-22".toCharArray());
        assertFalse(f.access.hasValidAdminSession(), "policy: a password change requires administrative re-verification");
        assertTrue(f.sessions.user().isPresent(), "the normal session continues after the owner's own change");
        assertEquals(AccessDecision.REQUIRES_2FA, f.access.evaluate());
        f.authorize(); // reautenticar restaura
        assertTrue(f.access.hasValidAdminSession());
    }

    @Test
    void anAdministratorPasswordResetOfTheSignedInAccountEndsTheElevation() {
        AuthFixture f = elevatedAdmin();
        f.userService.resetPassword(boss(f).id(), "temporary-pass-9".toCharArray());
        assertFalse(f.access.hasValidAdminSession());
        assertTrue(f.sessions.admin().isEmpty());
    }

    @Test
    void anOldChallengeCannotRestorePrivilegeAfterAChange() {
        for (String change : new String[] {"password", "demotion", "disabled"}) {
            AuthFixture f = AuthFixture.ready();
            f.seedAdmin();
            f.auth.login("boss", PW);
            f.clock.advance(Duration.ofSeconds(31));
            var flow = f.access.startTwoFactor();
            flow.sendEmailCode();
            assertEquals(OtpService.Result.OK, flow.verifyEmail(f.otpProvider.lastCode()));
            f.clock.advance(Duration.ofSeconds(31));
            flow.sendSmsCode();
            String smsCode = f.otpProvider.lastCode();
            switch (change) {
                case "password" -> f.userService.changeOwnPassword(boss(f).id(), PW, "another-horse-22".toCharArray());
                case "demotion" -> f.users.update(with(boss(f), Role.USER, UserStatus.ACTIVE));
                default -> f.users.update(with(boss(f), Role.ADMIN, UserStatus.DISABLED));
            }
            assertEquals(OtpService.Result.EXPIRED, flow.verifySms(smsCode), change + ": the in-flight challenge is dead");
            assertFalse(f.access.hasValidAdminSession(), change + ": no privilege came back");
            assertTrue(f.sessions.admin().isEmpty(), change);
        }
    }

    @Test
    void anUnreadableAccountSourceDeniesInsteadOfTrustingTheSessionCopy() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        f.auth.login("boss", PW);
        AtomicBoolean broken = new AtomicBoolean();
        UserRepository flaky = (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(), new Class<?>[] {UserRepository.class}, (p, m, a) -> {
            if (broken.get()) {
                throw new IllegalStateException("db down");
            }
            try {
                return m.invoke(f.users, a);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        AdminAccessService access = new AdminAccessService(f.sessions, flaky, new SecurityConfig(30), new OtpService(f.clock), f.otpProvider, f.otpProvider, f.devices,
                f.audit, f.clock);
        f.clock.advance(Duration.ofSeconds(31));
        var flow = access.startTwoFactor();
        flow.sendEmailCode();
        flow.verifyEmail(f.otpProvider.lastCode());
        f.clock.advance(Duration.ofSeconds(31));
        flow.sendSmsCode();
        flow.verifySms(f.otpProvider.lastCode());
        assertTrue(access.hasValidAdminSession());
        broken.set(true);
        assertFalse(access.hasValidAdminSession(), "fail closed when the source can not be read");
        assertThrows(AccessDeniedException.class, access::requireAdmin);
        assertTrue(f.sessions.user().isPresent(), "an outage does not log the user out");
        broken.set(false);
        assertTrue(access.hasValidAdminSession(), "and the elevation was not destroyed by the outage");
    }
}
