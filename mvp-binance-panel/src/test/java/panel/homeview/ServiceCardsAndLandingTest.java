package panel.homeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.homeview.ServiceCards.Availability;
import panel.tradeview.DeskModel.Feed;

class ServiceCardsAndLandingTest {
    private static ServiceCards.Card card(List<ServiceCards.Card> l, String id) {
        return l.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void walletIsUnavailableUntilTheNetworkIdentityIsVerified() {
        var cards = ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, false, false));
        assertEquals(Availability.UNAVAILABLE, card(cards, "wallet").availability());
        assertEquals("svc.wallet.why", card(cards, "wallet").reasonKey());
        assertFalse(card(cards, "wallet").actionable());
        assertEquals(Availability.READ_ONLY, card(ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, true, false)), "wallet").availability());
    }

    @Test
    void marketServicesFollowTheFeedAndNothingIsCalledReadyWhileConnecting() {
        assertEquals(Availability.READ_ONLY, card(ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, false, false)), "markets").availability());
        var down = ServiceCards.build(new ServiceCards.Inputs(Feed.DISCONNECTED, false, false, false));
        assertEquals(Availability.UNAVAILABLE, card(down, "markets").availability());
        assertEquals(Availability.UNAVAILABLE, card(down, "desk").availability());
        assertEquals(Availability.UNAVAILABLE, card(down, "network").availability());
        var connecting = ServiceCards.build(new ServiceCards.Inputs(Feed.WAITING, true, false, false));
        assertEquals("mk.connecting", card(connecting, "markets").reasonKey());
    }

    @Test
    void researchCardExistsOnlyForAdminSessionsAndStaysRestricted() {
        assertTrue(ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, true, false)).stream().noneMatch(c -> c.id().equals("research")));
        var admin = ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, true, true));
        assertTrue(card(admin, "research").restricted());
        assertEquals("overview", card(admin, "research").route(), "the card only requests the route; the router gate verifies");
    }

    @Test
    void noCardIsATradeDepositOrWalletCreationAction() {
        for (var c : ServiceCards.build(new ServiceCards.Inputs(Feed.LIVE, true, true, true))) {
            assertFalse(c.id().matches("trade|deposit|buy|sell|pay|create-wallet"), c.id());
        }
    }

    @Test
    void landingDefaultsToHomeAndIsScopedPerAccount() {
        LandingPreference p = new LandingPreference();
        String a = LandingPreference.key(1);
        String b = LandingPreference.key(2);
        assertEquals(LandingPreference.Start.HOME, p.startFor(a));
        p.choose(a, LandingPreference.Start.TERMINAL);
        assertEquals("t-desk", p.route(a, id -> true));
        assertEquals("t-home", p.route(b, id -> true), "another account is unaffected");
        assertEquals("t-home", p.route(a, id -> false), "Terminal missing for the session falls back to Home");
        assertFalse(p.persistent(), "preference persistence is not authorized in this build");
    }

    @Test
    void welcomeStateIsPerAccountAndReplayIsExplicit() {
        LandingPreference p = new LandingPreference();
        String a = LandingPreference.key(1);
        String b = LandingPreference.key(2);
        assertFalse(p.welcomeSeen(a));
        p.markWelcomeSeen(a);
        assertTrue(p.welcomeSeen(a));
        assertFalse(p.welcomeSeen(b));
        p.forgetWelcome(a);
        assertFalse(p.welcomeSeen(a));
    }
}
