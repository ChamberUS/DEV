package panel.byxview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.byxview.BenefitsState.CapStatus;
import panel.byxview.BenefitsState.Inputs;
import panel.byxview.BenefitsState.Overall;
import panel.byxview.BenefitsState.WalletRead;
import panel.model.Entitlement;

/** B04: every real condition maps to its own state; technical conditions are never plan limits; nothing grants anything. */
class BenefitsStateTest {
    private static final WalletRead LINKED = new WalletRead.Linked("byx1abc");

    private static Inputs in(WalletRead w, boolean reach, boolean verified, BigInteger bal, String tier, List<Entitlement> ents, String reason, boolean session) {
        return new Inputs(session, reason, w, reach, verified, bal, tier, ents, reason == null);
    }

    private static boolean noneAvailable(BenefitsState.Model m) {
        return m.capabilities().stream().noneMatch(c -> c.status() == CapStatus.AVAILABLE);
    }

    @Test
    void authorizationRequiredIsItsOwnStateNotAPlanLimit() {
        var m = BenefitsState.resolve(in(null, true, false, null, null, List.of(), "SERVER_AUTHORIZATION_REQUIRED", true));
        assertEquals(Overall.UNAUTHORIZED, m.overall());
        assertEquals("SERVER_AUTHORIZATION_REQUIRED", m.code());
        assertEquals(BenefitsState.Authorization.NOT_AUTHORIZED, m.authorization());
        assertNull(m.tier(), "no tier is claimed without an authorized read");
        assertNull(m.balanceText());
        assertTrue(m.capabilities().stream().allMatch(c -> c.status() == CapStatus.CANNOT_CHECK), "unknown, never 'locked'");
        assertTrue(noneAvailable(m));
    }

    @Test
    void deniedWalletReadAndMissingSessionAreUnauthorizedToo() {
        assertEquals(Overall.UNAUTHORIZED, BenefitsState.resolve(in(new WalletRead.Denied(), true, false, null, null, List.of(), null, true)).overall());
        assertEquals(Overall.UNAUTHORIZED, BenefitsState.resolve(in(new WalletRead.None(), true, false, null, null, List.of(), null, false)).overall());
    }

    @Test
    void walletServiceOutageIsNotAnUpgradePrompt() {
        var m = BenefitsState.resolve(in(new WalletRead.Unavailable(), true, false, null, null, List.of(), null, true));
        assertEquals(Overall.WALLET_UNAVAILABLE, m.overall());
        assertEquals("WALLET_SERVICE_UNAVAILABLE", m.code());
        assertEquals(BenefitsState.Authorization.ACCEPTED, m.authorization());
        assertTrue(m.capabilities().stream().allMatch(c -> c.status() == CapStatus.CANNOT_CHECK));
        assertNull(m.tier());
    }

    @Test
    void noLinkedWalletIsDistinctAndOnlyShowsTheBaselineWhilePolicyIsOperational() {
        var m = BenefitsState.resolve(in(new WalletRead.None(), true, false, null, null, List.of(), null, true));
        assertEquals(Overall.NO_WALLET, m.overall());
        assertEquals("WALLET_NOT_LINKED", m.code());
        assertEquals(BenefitsState.Balance.NEEDS_WALLET, m.balance());
        assertNull(m.balanceText(), "no wallet is not a zero balance");
        assertEquals("FREE", m.tier());
        assertTrue(m.tierIsBaseline());
        assertTrue(m.capabilities().stream().allMatch(c -> c.status() == CapStatus.NEEDS_WALLET));
        assertTrue(noneAvailable(m));
    }

    @Test
    void offlineNodeCannotConfirmAnyValue() {
        var m = BenefitsState.resolve(in(LINKED, false, true, BigInteger.valueOf(5_000_000), "HOLDER", List.of(), null, true));
        assertEquals(Overall.OFFLINE, m.overall());
        assertEquals("NETWORK_OFFLINE", m.code());
        assertEquals(BenefitsState.Balance.CANNOT_REFRESH, m.balance());
        assertNull(m.balanceText(), "a value that cannot be confirmed is not shown as current");
        assertNull(m.tier());
        assertTrue(noneAvailable(m));
    }

    @Test
    void anUnreturnedBalanceIsUnknownNeverZero() {
        var m = BenefitsState.resolve(in(LINKED, true, true, null, "FREE", List.of(), null, true));
        assertEquals(Overall.BALANCE_UNKNOWN, m.overall());
        assertEquals("BALANCE_UNREADABLE", m.code());
        assertNull(m.balanceText());
        assertNull(m.tier());
        var unverified = BenefitsState.resolve(in(LINKED, true, false, BigInteger.valueOf(10), "HOLDER", List.of(), null, true));
        assertEquals(Overall.BALANCE_UNKNOWN, unverified.overall(), "an unverified wallet's balance is not trusted");
    }

    @Test
    void aRealZeroIsShownAsAZeroAndIsDifferentFromUnknown() {
        var zero = BenefitsState.resolve(in(LINKED, true, true, BigInteger.ZERO, "FREE", List.of(), null, true));
        assertEquals(Overall.KNOWN, zero.overall());
        assertEquals("0.000000", zero.balanceText());
        assertNotNull(zero.tier());
    }

    @Test
    void knownStateUsesOnlyRealEntitlements() {
        var ents = List.of(
                new Entitlement("extended_history", "Extended History — x", "HOLDER", true, "BYX_BALANCE", null, Entitlement.Status.UNLOCKED),
                new Entitlement("advanced_analytics", "Advanced Analytics — x", "PLUS", false, "BYX_BALANCE", null, Entitlement.Status.LOCKED),
                new Entitlement("premium_research_tools", "Premium Research Tools", "PRO", false, "BYX_BALANCE", null, Entitlement.Status.UNAVAILABLE));
        var m = BenefitsState.resolve(in(LINKED, true, true, BigInteger.valueOf(1_250_000_000L), "HOLDER", ents, null, true));
        assertEquals(Overall.KNOWN, m.overall());
        assertEquals("OK", m.code());
        assertEquals("1250.000000", m.balanceText());
        assertEquals(CapStatus.AVAILABLE, m.capabilities().get(0).status());
        assertEquals(CapStatus.NOT_REACHED, m.capabilities().get(1).status());
        assertEquals(CapStatus.CANNOT_CHECK, m.capabilities().get(2).status());
        assertEquals("Extended History", m.capabilities().get(0).name());
        assertFalse(m.tierIsBaseline());
    }

    @Test
    void noStateOutsideKnownEverGrantsACapabilityAndNothingModelsARole() {
        List<Inputs> all = List.of(
                in(null, true, false, null, null, List.of(), "SERVER_AUTHORIZATION_REQUIRED", true),
                in(new WalletRead.Unavailable(), true, false, null, null, List.of(), null, true),
                in(new WalletRead.None(), true, false, null, null, List.of(), null, true),
                in(LINKED, false, true, BigInteger.TEN, "PRO", List.of(), null, true),
                in(LINKED, true, true, null, "PRO", List.of(), null, true));
        for (Inputs i : all) {
            var m = BenefitsState.resolve(i);
            assertTrue(noneAvailable(m), m.overall().name());
        }
        // what BYX never grants, at any tier (constant policy statement)
        assertEquals(List.of("Admin access", "Validation", "Final holdout", "Unapproved live trading"), BenefitsModel.NEVER);
        var fields = BenefitsState.Model.class.getRecordComponents();
        for (var f : fields) {
            assertFalse(f.getName().toLowerCase().contains("role") || f.getName().toLowerCase().contains("admin"), f.getName());
        }
    }

    @Test
    void theSixWireCodesAreExactlyTheDesignedOnes() {
        assertEquals(List.of("WALLET_NOT_LINKED", "WALLET_SERVICE_UNAVAILABLE", "BALANCE_UNREADABLE", "NETWORK_OFFLINE", "SERVER_AUTHORIZATION_REQUIRED", "OK"),
                java.util.Arrays.stream(Overall.values()).map(o -> o.code).toList());
        assertNotNull(Instant.now());
    }
}
