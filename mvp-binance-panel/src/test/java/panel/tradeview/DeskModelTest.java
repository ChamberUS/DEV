package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Level;
import panel.tradeview.DeskModel.CellState;
import panel.tradeview.DeskModel.Feed;

class DeskModelTest {
    private static final java.time.Instant NOW = DeskFixtures.NOW;

    @Test
    void feedStatesAreNeverInventedAndUnknownIsNeverLive() {
        assertEquals(Feed.NO_FEED, DeskModel.feed(DeskFixtures.noFeed(), NOW));
        assertEquals(Feed.WAITING, DeskModel.feed(DeskFixtures.waiting(), NOW));
        assertEquals(Feed.LIVE, DeskModel.feed(DeskFixtures.live(1), NOW));
        assertEquals(Feed.STALE, DeskModel.feed(DeskFixtures.stale(1), NOW));
        assertEquals(Feed.DEGRADED, DeskModel.feed(DeskFixtures.degraded(1), NOW));
        assertEquals(Feed.DISCONNECTED, DeskModel.feed(DeskFixtures.disconnected(1), NOW));
        assertEquals(Feed.ERROR, DeskModel.feed(DeskFixtures.error(), NOW));
        assertEquals(Feed.MOCK, DeskModel.feed(DeskFixtures.mock(), NOW));
        TraderSnapshot t = DeskFixtures.live(1);
        t.feed = "SOMETHING_NEW";
        assertEquals(Feed.UNAVAILABLE, DeskModel.feed(t, NOW), "an unknown feed state is never live");
        t.feed = null;
        assertEquals(Feed.WAITING, DeskModel.feed(t, NOW));
        t = DeskFixtures.noFeed();
        t.feed = "MOCK"; // MOCK fora do modo mock não é um feed real
        assertEquals(Feed.UNAVAILABLE, DeskModel.feed(t, NOW));
    }

    @Test
    void aLiveFeedWithAKnownOldTimestampBecomesStale() {
        TraderSnapshot t = DeskFixtures.live(1);
        assertEquals(Feed.LIVE, DeskModel.feed(t, NOW.plusSeconds(14)));
        assertEquals(Feed.STALE, DeskModel.feed(t, NOW.plusSeconds(16)));
        t.feedUpdatedAt = null; // sem horário não há como envelhecer: nada é inventado
        assertEquals(Feed.LIVE, DeskModel.feed(t, NOW.plusSeconds(3600)));
    }

    @Test
    void onlyDataBearingStatesShowMarketValues() {
        for (Feed f : Feed.values()) {
            boolean shows = f == Feed.LIVE || f == Feed.MOCK || f == Feed.DEGRADED || f == Feed.STALE || f == Feed.RECONNECTING;
            assertEquals(shows, f.showsMarketData(), f.name());
        }
        assertTrue(Feed.STALE.looksStale());
        assertFalse(Feed.LIVE.looksStale());
        assertTrue(Feed.NO_FEED.expectedUnavailable, "no feed is the expected state of this build (neutral dot)");
        assertFalse(Feed.DISCONNECTED.expectedUnavailable, "a drop is never neutral");
    }

    @Test
    void metricsSupportReadyNaStaleAndUnavailableWithReasons() {
        List<DeskModel.Cell> none = DeskModel.metrics(DeskFixtures.noFeed(), Feed.NO_FEED, NOW);
        assertEquals(List.of("Equity", "Daily PnL", "Exposure", "Drawdown", "Data age"), none.stream().map(DeskModel.Cell::title).toList());
        assertEquals(CellState.NA, none.get(0).state());
        assertEquals("N/A", none.get(0).value());
        assertEquals("No account", none.get(0).reason());
        assertEquals("No positions", none.get(2).reason());
        assertEquals(CellState.UNAVAILABLE, none.get(4).state());
        assertEquals("—", none.get(4).value());
        assertEquals("No feed", none.get(4).reason());

        TraderSnapshot acct = DeskFixtures.liveWithAccount(1);
        List<DeskModel.Cell> ready = DeskModel.metrics(acct, Feed.LIVE, NOW.plusSeconds(2));
        assertEquals(CellState.READY, ready.get(0).state());
        assertEquals("10,212.40", ready.get(0).value());
        assertEquals("+85.20", ready.get(1).value());
        assertEquals(CellState.READY, ready.get(4).state());
        assertEquals("2.0 s", ready.get(4).value());

        acct.feed = "STALE";
        List<DeskModel.Cell> stale = DeskModel.metrics(acct, Feed.STALE, NOW.plusSeconds(95));
        assertEquals(CellState.STALE, stale.get(0).state());
        assertEquals("10,212.40", stale.get(0).value(), "stale keeps the last known number");
        assertEquals(CellState.STALE, stale.get(4).state());
        assertTrue(stale.get(4).reason().startsWith("Last update"));

        acct.equity = null;
        assertEquals("Not reported", DeskModel.metrics(acct, Feed.LIVE, NOW).get(0).reason(), "an account exists but did not report");
    }

    @Test
    void orderBookKeepsRealDataOnly() {
        List<Level> asks = List.of(new Level(100.3, 1), new Level(100.1, 2), new Level(100.2, 3));
        List<Level> bids = List.of(new Level(99.9, 1), new Level(99.8, 4), new Level(99.7, 1));
        DeskModel.Book b = DeskModel.book(asks, bids, 3);
        assertEquals(List.of(100.3, 100.2, 100.1), b.asks().stream().map(DeskModel.BookRow::price).toList(), "best ask next to the mid line");
        assertEquals(List.of(99.9, 99.8, 99.7), b.bids().stream().map(DeskModel.BookRow::price).toList());
        assertEquals(100.0, b.mid(), 1e-9);
        assertEquals(0.2, b.spread(), 1e-9);
        // total acumulado a partir do melhor nível; profundidade relativa ao maior total exibido
        assertEquals(List.of(6.0, 5.0, 2.0), b.asks().stream().map(DeskModel.BookRow::total).toList());
        assertEquals(1.0, b.asks().getFirst().depth(), 1e-9);
        assertEquals(1.0, b.bids().getLast().depth(), 1e-9);
        assertEquals(1.0 / 6.0, b.bids().getFirst().depth(), 1e-9);
        assertEquals(2, DeskModel.book(asks, bids, 2).asks().size(), "level count follows the breakpoint");
        DeskModel.Book none = DeskModel.book(List.of(), List.of(), 10);
        assertTrue(none.empty());
        assertNull(none.mid());
        assertNull(none.spread());
        assertNull(DeskModel.book(asks, List.of(), 3).mid(), "one empty side never invents a mid");
    }

    @Test
    void recentTradesReadTheSharedTradeRowsAndSkipNothingMissing() {
        TraderSnapshot t = DeskFixtures.live(3);
        List<DeskModel.Trade> trades = DeskModel.trades(t.tradeRows, 4);
        assertEquals(4, trades.size());
        assertEquals(t.tradeRows.getFirst()[4], trades.getFirst().price());
        assertEquals(t.tradeRows.getFirst()[3], trades.getFirst().size());
        assertEquals(t.tradeRows.getFirst()[0], trades.getFirst().time());
        assertTrue(trades.getFirst().sell() || trades.getFirst().buy());
        assertEquals("N/A", DeskModel.trades(List.<String[]>of(new String[] {"12:00:00"}), 3).getFirst().price());
        assertTrue(DeskModel.trades(List.of(), 9).isEmpty());
    }

    @Test
    void botStateIsOnlyWhatTheAppDeclares() {
        TraderSnapshot t = DeskFixtures.noFeed();
        assertEquals(DeskModel.Bot.MONITORING, DeskModel.bot(t));
        t.botState = "IDLE";
        assertEquals(DeskModel.Bot.IDLE, DeskModel.bot(t));
        t.botState = "ERROR · crashed";
        assertEquals(DeskModel.Bot.ERROR, DeskModel.bot(t));
        t.botState = null;
        assertEquals(DeskModel.Bot.UNAVAILABLE, DeskModel.bot(t));
        t.botState = "TRADING LIVE";
        assertEquals(DeskModel.Bot.UNAVAILABLE, DeskModel.bot(t), "there is no active-trading state to show");
        assertEquals("Research · Live OFF", DeskModel.botStrip(DeskFixtures.noFeed()));
        assertEquals("Off", DeskModel.liveTrading(DeskFixtures.noFeed()));
    }

    @Test
    void headerTextComesFromTheSnapshotOnly() {
        assertEquals("Binance USD-M Futures", DeskModel.venue("USD-M-FUTURES"));
        assertEquals("Binance USD-M Futures", DeskModel.venue(null));
        assertEquals("PERP", DeskModel.venue("PERP"));
        assertTrue(DeskModel.perpetual("USD-M-FUTURES"));
        assertFalse(DeskModel.perpetual("SPOT"));
        assertEquals("24h N/A", DeskModel.change24h(DeskFixtures.noFeed()));
        assertEquals("24h +1.84%", DeskModel.change24h(DeskFixtures.live(1)));
        assertEquals("N/A", DeskModel.volume(null));
        assertEquals("182.4K", DeskModel.volume(182_400.0));
        assertEquals("<1 s", DeskModel.age(Duration.ofMillis(400)));
        assertEquals("12 s", DeskModel.age(Duration.ofSeconds(12)));
        assertEquals("1m 35s", DeskModel.age(Duration.ofSeconds(95)));
    }

    @Test
    void fingerprintIgnoresIdentityAndNoticesEveryDrawnField() {
        assertEquals(DeskModel.fingerprint(DeskFixtures.live(5)), DeskModel.fingerprint(DeskFixtures.live(5)), "a new but equal snapshot is the same");
        assertTrue(DeskModel.fingerprint(DeskFixtures.live(5)) != DeskModel.fingerprint(DeskFixtures.live(6)));
        TraderSnapshot a = DeskFixtures.live(5);
        int before = DeskModel.fingerprint(a);
        a.bids.set(0, new Level(a.bids.getFirst().price(), a.bids.getFirst().size() + 0.001));
        assertTrue(before != DeskModel.fingerprint(a), "a changed level size is a change");
        a = DeskFixtures.live(5);
        a.tradeRows.getFirst()[3] = "9.999";
        assertTrue(before != DeskModel.fingerprint(a), "a changed row cell is a change");
    }
}
