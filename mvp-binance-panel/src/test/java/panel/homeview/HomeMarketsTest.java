package panel.homeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.homeview.HomeMarkets.Catalog;
import panel.homeview.HomeMarkets.Instrument;
import panel.homeview.HomeMarkets.Kind;
import panel.homeview.HomeMarkets.Model;
import panel.homeview.HomeMarkets.Quote;
import panel.homeview.HomeMarkets.State;
import panel.tradeview.DeskModel.Feed;

/** B01: every required market state, the catalog/subscription split and "missing is not zero". Pure model, no JavaFX. */
class HomeMarketsTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instrument PERP = new Instrument("ETHUSDT", Kind.PERPETUAL, "Binance USD-M Futures", "USDT");
    private static final Instrument SPOT = new Instrument("ETHUSDT", Kind.SPOT, "Binance Spot", "USDT");
    private static final Instrument OTHER = new Instrument("BTCUSDT", Kind.PERPETUAL, "Binance USD-M Futures", "USDT");

    private static Quote full(Instant at) {
        return new Quote(3000.5, -1.25, 123456.0, at);
    }

    private static Model build(Catalog c, Quote q, Feed f, String query) {
        return HomeMarkets.build(c, i -> q, f, false, query, NOW);
    }

    @Test
    void productionCatalogIsExactlyTheOneSupportedPerpetual() {
        var loaded = (Catalog.Loaded) HomeMarkets.productionCatalog();
        assertEquals(List.of(PERP), loaded.instruments());
        assertFalse(loaded.instruments().stream().anyMatch(i -> i.kind() == Kind.SPOT), "ETH spot is not a supported instrument");
    }

    @Test
    void loadingCatalogIsLoading() {
        assertEquals(State.LOADING, build(new Catalog.Loading(), null, Feed.LIVE, "").state());
    }

    @Test
    void failedCatalogIsErrorNeverEmpty() {
        Model m = build(new Catalog.Failed("timeout"), null, Feed.LIVE, "");
        assertEquals(State.ERROR, m.state());
        assertTrue(m.rows().isEmpty());
        assertEquals(State.EMPTY, build(new Catalog.Loaded(List.of()), null, Feed.LIVE, "").state(), "an empty catalog is a different state");
    }

    @Test
    void liveFullDataIsReady() {
        Model m = build(new Catalog.Loaded(List.of(PERP)), full(NOW.minusSeconds(1)), Feed.LIVE, "");
        assertEquals(State.READY, m.state());
        assertTrue(m.single(), "single-instrument catalog is called out");
        assertEquals(Duration.ofSeconds(1), m.age());
    }

    @Test
    void missingNumbersAreNullAndMarkTheStatePartialNeverZero() {
        Quote q = new Quote(3000.5, null, null, NOW);
        Model m = build(new Catalog.Loaded(List.of(PERP)), q, Feed.LIVE, "");
        assertEquals(State.PARTIAL_DATA, m.state());
        assertEquals(1, m.partialRows());
        assertNull(m.rows().get(0).quote().change24hPct());
        assertNull(m.rows().get(0).quote().volume24hQuote());
    }

    @Test
    void staleAndReconnectingFeedsNeverLookCurrent() {
        for (Feed f : new Feed[] {Feed.STALE, Feed.RECONNECTING, Feed.DEGRADED}) {
            Model m = build(new Catalog.Loaded(List.of(PERP)), full(NOW.minusSeconds(120)), f, "");
            assertEquals(State.STALE, m.state(), f.name());
            assertEquals(Duration.ofSeconds(120), m.age());
        }
    }

    @Test
    void offlineFeedKeepsRowsButSaysOffline() {
        Model none = build(new Catalog.Loaded(List.of(PERP)), null, Feed.DISCONNECTED, "");
        assertEquals(State.OFFLINE, none.state());
        assertEquals(1, none.rows().size());
        assertTrue(none.rows().get(0).hasMissingValues());
        assertEquals(State.OFFLINE, build(new Catalog.Loaded(List.of(PERP)), null, Feed.NO_FEED, "").state());
        assertEquals("NOT_CONFIGURED", build(new Catalog.Loaded(List.of(PERP)), null, Feed.NO_FEED, "").detail());
    }

    @Test
    void waitingForTheFeedIsLoadingUnlessOldDataExists() {
        assertEquals(State.LOADING, build(new Catalog.Loaded(List.of(PERP)), null, Feed.WAITING, "").state());
        assertEquals(State.STALE, build(new Catalog.Loaded(List.of(PERP)), full(NOW.minusSeconds(5)), Feed.WAITING, "").state());
    }

    @Test
    void feedErrorIsAnErrorButTheKnownInstrumentsStayListed() {
        Model m = build(new Catalog.Loaded(List.of(PERP)), null, Feed.ERROR, "");
        assertEquals(State.ERROR, m.state());
        assertEquals(1, m.rows().size());
    }

    @Test
    void searchFiltersOnlyTheKnownCatalogAndUnsupportedSearchSaysSo() {
        Catalog two = new Catalog.Loaded(List.of(PERP, OTHER));
        Model eth = build(two, full(NOW), Feed.LIVE, "eth");
        assertEquals(1, eth.rows().size());
        assertEquals("ETHUSDT", eth.rows().get(0).instrument().symbol());
        Model btcSpot = build(new Catalog.Loaded(List.of(PERP)), full(NOW), Feed.LIVE, "BTC spot");
        assertEquals(State.UNSUPPORTED_SEARCH, btcSpot.state());
        assertEquals(List.of(PERP), btcSpot.suggestions());
        assertEquals("BTC spot", btcSpot.query());
    }

    @Test
    void perpetualAndSpotAreDistinctInstruments() {
        assertFalse(PERP.equals(SPOT));
        assertTrue(PERP.terminalSupported());
        assertFalse(SPOT.terminalSupported(), "the Terminal shows perpetual futures only");
        assertFalse(OTHER.terminalSupported(), "only ETHUSDT is wired to the Terminal feed");
    }

    @Test
    void mockFeedIsFlagged() {
        Model m = HomeMarkets.build(new Catalog.Loaded(List.of(PERP)), i -> full(NOW), Feed.MOCK, true, "", NOW);
        assertTrue(m.mock());
    }
}
