package panel.homeview;

import java.util.ArrayList;
import java.util.List;
import panel.tradeview.DeskModel;

/**
 * Service cards of the Home (B01). The availability label states what the service can do <i>today</i>; a card is never "ready" because the
 * design lists it. No card starts a trade, a deposit or a wallet creation.
 */
public final class ServiceCards {
    private ServiceCards() {
    }

    public enum Availability { AVAILABLE, READ_ONLY, UNAVAILABLE, PLANNED }

    /**
     * @param reasonKey i18n key explaining UNAVAILABLE/PLANNED (null otherwise)
     * @param route     where the card goes; null when the card is not actionable
     * @param restricted opens only behind the real admin verification (the router gate decides, not the card)
     */
    public record Card(String id, String titleKey, String descKey, Availability availability, String reasonKey, String route, boolean restricted) {
        public boolean actionable() {
            return route != null && (availability == Availability.AVAILABLE || availability == Availability.READ_ONLY);
        }
    }

    /**
     * @param feed             market data subscription state (same classification as the Terminal)
     * @param nodeReachable    the BYX test node answers (ONLINE/SYNCING/DEGRADED/STALE all count as reachable but not as healthy elsewhere)
     * @param walletVerified   the network identity is VERIFIED, i.e. a wallet could be read at all
     * @param researchVisible  this session is an admin session, so a Research route exists
     */
    public record Inputs(DeskModel.Feed feed, boolean nodeReachable, boolean walletVerified, boolean researchVisible) {
    }

    public static List<Card> build(Inputs in) {
        boolean feedUp = in.feed().showsMarketData();
        String feedReason = in.feed() == DeskModel.Feed.WAITING ? "mk.connecting" : "mk.notConnected";
        List<Card> cards = new ArrayList<>();
        cards.add(new Card("markets", "svc.markets", "svc.markets.d", feedUp ? Availability.READ_ONLY : Availability.UNAVAILABLE,
                feedUp ? null : feedReason, "t-markets", false));
        cards.add(new Card("desk", "svc.desk", "svc.desk.d", feedUp ? Availability.READ_ONLY : Availability.UNAVAILABLE,
                feedUp ? null : feedReason, "t-desk", false));
        cards.add(new Card("network", "svc.network", "svc.network.d", in.nodeReachable() ? Availability.READ_ONLY : Availability.UNAVAILABLE,
                in.nodeReachable() ? null : "svc.reason.node", "t-byx", false));
        cards.add(new Card("wallet", "svc.wallet", "svc.wallet.d", in.walletVerified() ? Availability.READ_ONLY : Availability.UNAVAILABLE,
                in.walletVerified() ? null : "svc.wallet.why", "t-wallet", false));
        cards.add(new Card("benefits", "svc.benefits", "svc.benefits.d", Availability.READ_ONLY, null, "t-benefits", false));
        cards.add(new Card("help", "svc.help", "svc.help.d", Availability.AVAILABLE, null, "h-help", false));
        if (in.researchVisible()) {
            cards.add(new Card("research", "svc.research", "svc.research.d", Availability.AVAILABLE, null, "overview", true));
        }
        return List.copyOf(cards);
    }
}
