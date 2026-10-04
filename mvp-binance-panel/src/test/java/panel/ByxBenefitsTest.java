package panel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import panel.service.ByxBenefitsService;

class ByxBenefitsTest {
    final ByxBenefitsService benefits = new ByxBenefitsService();
    @Test void noWalletNeededAndPaymentsAreIndependent() {
        assertTrue(benefits.assess(null).normalAccess());
        assertTrue(benefits.payments().methods().stream().anyMatch(p -> !p.requiresByx()));
        assertTrue(benefits.payments().methods().stream().noneMatch(p -> p.connected()));
    }
    @Test void observedWalletDoesNotProveControlOrGrantAnything() {
        for (String address : new String[]{null, "", "byx1observed", "byx1richaccount"}) {
            var result = benefits.assess(address);
            assertEquals("VERIFICATION_REQUIRED", result.controlProof());
            assertFalse(result.benefitGranted()); assertFalse(result.adminGranted());
            assertFalse(result.researchUnlocked()); assertFalse(result.strategyExecutionEnabled());
        }
    }
    @Test void pricesLimitsAndSubsidiesRemainUndefined() {
        assertTrue(benefits.appDiscounts().rate().isEmpty());
        assertTrue(benefits.networkFees().subsidyUbyx().isEmpty());
        assertTrue(benefits.usage().limits().normalAccess());
        assertFalse(benefits.usage().limits().byxBenefitGranted());
        assertTrue(benefits.usage().limits().extraResourceUnits().isEmpty());
    }
    @Test void benefitsCannotElevateAnAuthenticatedUser() {
        var f = AuthFixture.ready(); f.seedUser(); f.auth.login("alice", "temporary-pass-1".toCharArray());
        benefits.assess("byx1observed");
        assertFalse(f.sessions.user().orElseThrow().user().admin());
        assertTrue(f.sessions.admin().isEmpty());
        assertThrows(panel.security.AccessDeniedException.class, f.access::requireAdmin);
    }
}
