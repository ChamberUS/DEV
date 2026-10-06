package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.auth.*;
import panel.model.Settings;
import panel.security.*;
import panel.service.*;

class PreCutoverSecurityTest {
    @Test void ordinaryActivityNeverCallsElevationOrChangesItsDeadline() {
        var fixture = AuthFixture.ready(); fixture.seedAdmin();
        fixture.auth.login("boss", "correct-horse-1".toCharArray()); fixture.authorize();
        long deadline = fixture.access.adminSession().orElseThrow().expiresAt().toEpochMilli();
        int calls = fixture.authority.elevationCalls;
        for (int i = 0; i < 4; i++) {
            fixture.clock.advance(Duration.ofMinutes(1)); fixture.access.touch(); fixture.access.refresh();
            assertEquals(deadline, fixture.access.adminSession().orElseThrow().expiresAt().toEpochMilli());
        }
        fixture.clock.advance(Duration.ofMinutes(1)); fixture.access.refresh();
        assertFalse(fixture.access.hasValidAdminSession()); assertEquals(calls, fixture.authority.elevationCalls);
    }
    @Test void forgedRoleElevationAndMfaPresentationNeverAuthorize() {
        var fixture = AuthFixture.ready(); fixture.seedUser();
        fixture.auth.login("alice", "temporary-pass-1".toCharArray());
        var user = fixture.sessions.user().orElseThrow().user();
        var forged = new panel.user.User(user.id(), user.username(), user.email(), "", Role.ADMIN,
                user.status(), user.phone(), true, true, false, user.createdAt(), user.updatedAt(), user.lastLoginAt());
        fixture.sessions.updateUser(forged);
        fixture.sessions.grantAdmin(new AdminSession(fixture.clock.instant(), AuthMethod.TWO_FACTOR, Duration.ofHours(8)));
        assertTrue(fixture.access.hasValidAdminSession(), "forged presentation alone looks elevated");
        assertThrows(AccessDeniedException.class, fixture.access::requireAdmin);
        assertEquals(Role.USER, fixture.sessions.user().orElseThrow().user().role());
        assertFalse(fixture.access.hasValidAdminSession());
    }
    @Test void sensitiveLocalOperationsFailBeforeDependenciesOrEffects() throws Exception {
        var fixture = AuthFixture.ready(); fixture.seedAdmin();
        fixture.auth.login("boss", "correct-horse-1".toCharArray());
        fixture.sessions.grantAdmin(new AdminSession(fixture.clock.instant(), AuthMethod.TWO_FACTOR, Duration.ofHours(8)));
        var wallet = new ByxWalletIdentityService(fixture.sessions, null, () -> null, fixture.clock);
        var payment = new ByxPaymentService(fixture.sessions, null, null, null, fixture.clock, () -> null);
        var gas = new GasSponsorshipService(fixture.sessions, null, null, null, null, () -> null, fixture.clock);
        var jobs = new JobManager(null, () -> null, () -> {}, () -> {});
        List<org.junit.jupiter.api.function.Executable> operations = List.of(
                () -> wallet.challenge("irrelevant"), () -> wallet.verify(null), () -> wallet.revoke("irrelevant"),
                () -> payment.create("irrelevant"), () -> payment.confirm("id", "hash"),
                () -> gas.request("irrelevant"), () -> gas.refresh("irrelevant"), () -> gas.revoke("irrelevant"),
                () -> new TreasuryService(null, null, null, null, () -> null, fixture.clock).refresh(),
                () -> jobs.submit(null, null), () -> jobs.cancel(null),
                () -> new SecurityAuditService(null, fixture.clock).record(null, null, null));
        for (var op : operations) assertTrue(assertThrows(AccessDeniedException.class, op).getMessage().startsWith(ServerAuthorization.REQUIRED));
        assertTrue(assertThrows(java.io.IOException.class, () -> new Settings().save()).getMessage().startsWith(ServerAuthorization.REQUIRED));
    }
    @Test void forgedElevationWithoutServerMfaIsDeniedEvenForAdminRole() {
        var fixture = AuthFixture.ready(); fixture.seedAdmin();
        fixture.auth.login("boss", "correct-horse-1".toCharArray());
        fixture.sessions.grantAdmin(new AdminSession(fixture.clock.instant(), AuthMethod.TWO_FACTOR, Duration.ofHours(8)));
        assertThrows(AccessDeniedException.class, fixture.access::requireAdmin);
        assertFalse(fixture.access.hasValidAdminSession());
    }
}
