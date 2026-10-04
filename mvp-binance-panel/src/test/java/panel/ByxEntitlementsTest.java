package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import panel.service.EntitlementService;
import panel.model.Entitlement;
import panel.security.AccessDeniedException;

class ByxEntitlementsTest {
    final ByxWalletOwnershipTest fixture = new ByxWalletOwnershipTest();
    EntitlementService entitlements;
    String address;
    @BeforeEach void setup() throws Exception {
        fixture.setup();
        address = fixture.keys.address();
        fixture.identity.verify(fixture.valid());
        entitlements = new EntitlementService(fixture.benefits);
    }
    @AfterEach void close() { fixture.close(); }
    void balance(String amount) throws Exception {
        fixture.amount = new BigInteger(amount);
        fixture.benefits.refresh(address).get();
    }
    @Test void tiersUnlockOnlyTheirApplicationCapabilities() throws Exception {
        var amounts = List.of("99999999", "100000000", "1000000000", "10000000000");
        var tiers = List.of("FREE", "HOLDER", "PLUS", "PRO");
        var counts = List.of(0L, 1L, 3L, 4L);
        for (int i = 0; i < amounts.size(); i++) {
            balance(amounts.get(i));
            assertEquals(tiers.get(i), fixture.benefits.snapshot(address).tier());
            assertEquals(counts.get(i), entitlements.snapshot(address).stream().filter(Entitlement::enabled).count());
        }
    }
    @Test void realExtendedHistoryGateRejectsFreeAndAcceptsHolder() throws Exception {
        balance("99999999");
        assertThrows(AccessDeniedException.class, () -> entitlements.extendedHistory(address));
        balance("100000000");
        assertEquals(new BigInteger("100000000"), entitlements.extendedHistory(address).get(0).balanceUbyx());
        assertEquals(1, entitlements.extendedHistory(address).size());
        fixture.auth.clock.advance(Duration.ofSeconds(1));
        balance("100000001");
        assertEquals(2, entitlements.extendedHistory(address).size());
    }
    @Test void balanceUpgradeAndDowngradeImmediatelyChangeGate() throws Exception {
        balance("100000000"); assertFalse(entitlements.allows(address, "advanced_analytics"));
        balance("1000000000"); assertTrue(entitlements.allows(address, "advanced_analytics"));
        balance("99999999"); assertFalse(entitlements.allows(address, "extended_history"));
        assertThrows(AccessDeniedException.class, () -> entitlements.extendedHistory(address));
    }
    @Test void watchOnlyRevokedAndLogoutCannotUnlock() throws Exception {
        balance("10000000000");
        var other = new ByxWalletOwnershipTest.TestKeyPair().address();
        assertFalse(entitlements.allows(other, "extended_history"));
        assertEquals("FREE", fixture.benefits.snapshot(other).tier());
        fixture.identity.revoke(address);
        assertEquals("FREE", fixture.benefits.snapshot(address).tier());
        assertFalse(entitlements.allows(address, "extended_history"));
        fixture.auth.sessions.logout();
        assertThrows(AccessDeniedException.class, () -> entitlements.extendedHistory(address));
    }
    @Test void offlineStaleAndOldCacheFailClosed() throws Exception {
        balance("100000000"); fixture.offline = true;
        balance("10000000000");
        assertEquals("FREE", fixture.benefits.snapshot(address).tier());
        assertTrue(entitlements.snapshot(address).stream().allMatch(e -> e.status() == Entitlement.Status.UNAVAILABLE));
        fixture.offline = false; fixture.stale = true; balance("10000000000");
        assertFalse(entitlements.allows(address, "premium_research_tools"));
        fixture.stale = false; balance("10000000000");
        fixture.auth.clock.advance(Duration.ofSeconds(60));
        assertThrows(AccessDeniedException.class, () -> entitlements.extendedHistory(address));
    }
    @Test void exactProgressAtOneUbyxAndMaximumTier() throws Exception {
        balance("999999999");
        var progress = entitlements.progress(address);
        assertEquals("PLUS", progress.nextTier());
        assertEquals(BigInteger.ONE, progress.remainingUbyx());
        balance("10000000000");
        assertEquals("MAXIMUM", entitlements.progress(address).nextTier());
        assertNull(entitlements.progress(address).remainingUbyx());
    }
    @Test void tierNeverGrantsSecurityOrExecutionCapabilities() throws Exception {
        balance("10000000000");
        for (String capability : List.of("ADMIN", "research_admin", "VALIDATION", "FINAL_HOLDOUT", "live_trading", "strategy_execution", "unknown"))
            assertFalse(entitlements.allows(address, capability));
        assertTrue(fixture.auth.sessions.admin().isEmpty());
        assertThrows(AccessDeniedException.class, fixture.auth.access::requireAdmin);
        var policy = fixture.benefits.assess(address);
        assertFalse(policy.adminGranted()); assertFalse(policy.researchUnlocked()); assertFalse(policy.strategyExecutionEnabled());
    }
}
