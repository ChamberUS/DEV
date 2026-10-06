package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import panel.adapter.ResearchModeTradingProvider;
import panel.localservice.MarketData;
import panel.localservice.MarketData.Link;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Candle;
import panel.model.TraderSnapshot.Level;
import panel.model.TraderSnapshot.MarketTrade;
import panel.util.Fmt;

/** O Desk alimentado pelo caminho REAL (MarketData do serviço local → provider → TraderSnapshot → telas): nenhum valor de fixture de tela. */
class DeskMarketIntegrationTest {
    private static final Instant NOW = DeskFixtures.NOW;
    private static final long T0 = NOW.toEpochMilli() / 60_000 * 60_000 - 59 * 60_000;

    private static MarketData data(Link link, String feed, String book, Instant updated) {
        List<Candle> candles = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double o = 2700 + i * 0.1;
            candles.add(new Candle(o, o + 1, o - 1, o + 0.5, T0 + i * 60_000L));
        }
        boolean live = "LIVE".equals(book);
        return new MarketData(link, feed, "ok", book, updated, 2699.5, 2698.9, 2700.1, new MarketData.Ticker(-0.114, 2734.0, 2676.97, 2346638.29, 6356094948.14),
                live ? List.of(new Level(2699.5, 2.0), new Level(2699.0, 1.0), new Level(2698.5, 3.0)) : List.of(),
                live ? List.of(new Level(2699.6, 3.0), new Level(2700.0, 4.0), new Level(2700.5, 1.0)) : List.of(), candles,
                List.of(new MarketTrade(NOW.minusSeconds(1), 2699.55, 0.5, true), new MarketTrade(NOW.minusSeconds(2), 2699.45, 1.25, false)));
    }

    private static TraderSnapshot snap(MarketData m) {
        TraderSnapshot t = new TraderSnapshot();
        t.feed = "NOT_CONFIGURED";
        t.symbol = "BTCUSDT"; // o que a pesquisa diria: o mercado público manda no símbolo
        t.tradeRows.add(new String[] {"12:00:00", "ETHUSDT", "BUY", "9.999", "1.11", "0.01"}); // fill de CONTA: nunca vai para Recent Trades
        ResearchModeTradingProvider.applyMarket(t, m);
        return t;
    }

    @Test
    void liveFeedDrivesHeaderMetricsChartBookAndRecentTrades() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = snap(data(Link.STREAMING, "LIVE", "LIVE", NOW.minusSeconds(1)));
            DeskHarness d = DeskHarness.open(1440, 900, t);
            var h = d.desk.header();
            assertEquals("Live", h.statusLabel().getText());
            assertEquals(Fmt.price(2699.5), h.priceLabel().getText(), "header price is the last TRADED price");
            assertFalse(h.priceLabel().getStyleClass().contains("stale"));
            assertTrue(h.statsShown());
            String text = String.join(" | ", DeskNodes.visibleTexts(d.desk));
            assertTrue(text.contains("ETHUSDT"), "symbol comes from the public market");
            assertFalse(text.contains("BTCUSDT"));
            assertTrue(text.contains(Fmt.price(2698.9)), "mark price is shown, as a different number from the last price");
            assertTrue(text.contains("24h -0.114%") || text.contains("24h " + Fmt.signed(-0.114, "%")), text);
            assertTrue(text.contains(Fmt.price(2734.0)) && text.contains(Fmt.price(2676.97)), "24h high and low");
            assertTrue(text.contains(DeskModel.volume(6356094948.14)), "24h volume (USDT, rolling window)");
            assertFalse(text.contains(DeskModel.volume(2346638.29)), "the ETH (base) volume is never shown under the USDT label");
            assertTrue(text.toLowerCase().contains("24h volume (usdt)"), "the 24h label says it is a rolling 24h USDT volume");
            // chart: candles reais com eixo de tempo REAL
            assertTrue(d.desk.chart().showingCandles());
            var axis = DeskNodes.all(d.desk.chart(), Label.class).stream().filter(l -> l.getStyleClass().contains("byx-desk-axis")).map(Label::getText).toList();
            axis = axis.subList(axis.size() - 6, axis.size()); // os 6 rótulos do eixo de tempo (depois dos traços do eixo de preço)
            assertFalse(axis.contains("--:--"), "real timestamps replace the placeholder axis: " + axis);
            String first = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(T0));
            assertEquals(first, axis.get(0));
            // book
            assertTrue(d.desk.book().askRows().stream().noneMatch(GridRow::skeleton));
            assertEquals(Fmt.price(2699.6), d.desk.book().askRows().getLast().cell(0).getText());
            // Recent Trades = aggTrade público (nunca fills de conta)
            var rows = d.desk.trades().rows();
            assertEquals(Fmt.price(2699.55), rows.get(0).cell(0).getText());
            assertEquals("0.500", rows.get(0).cell(1).getText());
            assertEquals(Fmt.price(2699.45), rows.get(1).cell(0).getText());
            assertTrue(rows.stream().noneMatch(r -> "1.11".equals(r.cell(0).getText()) || "9.999".equals(r.cell(1).getText())), "account fills stay in the Trades tab");
            d.close();
        });
    }

    @Test
    void resyncingBookIsNeverShownAsValidWhileTheRestKeepsFlowing() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, snap(data(Link.STREAMING, "LIVE", "RESYNCING", NOW.minusSeconds(1))));
            assertEquals("Live", d.desk.header().statusLabel().getText());
            assertTrue(d.desk.book().askRows().stream().allMatch(r -> r.cell(0).getText().isEmpty()), "no levels while resynchronizing");
            assertTrue(d.desk.book().messageLabel().isVisible());
            assertEquals("Order book resynchronizing…", d.desk.book().messageLabel().getText());
            assertEquals(Fmt.price(2699.55), d.desk.trades().rows().get(0).cell(0).getText(), "trades keep flowing");
            d.close();
        });
    }

    @Test
    void lostLocalLinkKeepsTheLastValuesMarkedStaleNotLive() throws Exception {
        DeskHarness.fx(() -> {
            MarketData live = data(Link.STREAMING, "LIVE", "LIVE", NOW.minusSeconds(40));
            TraderSnapshot t = snap(live.withLink(Link.LOST));
            assertEquals("RECONNECTING", t.feed);
            DeskHarness d = DeskHarness.open(1440, 900, t);
            var h = d.desk.header();
            assertEquals("Reconnecting", h.statusLabel().getText());
            assertEquals(Fmt.price(2699.5), h.priceLabel().getText(), "last known value stays visible");
            assertTrue(h.priceLabel().getStyleClass().contains("stale"));
            assertTrue(d.desk.chart().overlay().getText().startsWith("STALE · LAST UPDATE"));
            d.close();
        });
    }

    @Test
    void liveFeedWithOldDataBecomesStaleByTheClock() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, snap(data(Link.STREAMING, "LIVE", "LIVE", NOW.minusSeconds(60))));
            assertEquals("STALE", d.desk.header().statusLabel().getText(), "a LIVE label with a 60 s old feed time is STALE");
            d.close();
        });
    }

    @Test
    void serviceUnreachableWithoutDataShowsNothingInvented() throws Exception {
        DeskHarness.fx(() -> {
            MarketData lost = MarketData.idle().withLink(Link.LOST);
            TraderSnapshot t = snap(lost);
            assertEquals("DISCONNECTED", t.feed);
            DeskHarness d = DeskHarness.open(1440, 900, t);
            assertEquals("—", d.desk.header().priceLabel().getText());
            assertEquals("Disconnected", d.desk.header().statusLabel().getText());
            assertNull(d.desk.chart().chart());
            d.close();
        });
    }

    @Test
    void connectingAndUnsupportedStatesMapToHonestFeeds() {
        assertEquals("NOT_CONFIGURED", ResearchModeTradingProvider.feedName(MarketData.idle()));
        assertEquals("CONNECTING", ResearchModeTradingProvider.feedName(MarketData.idle().withLink(Link.CONNECTING)));
        assertEquals("UNAVAILABLE", ResearchModeTradingProvider.feedName(MarketData.idle().withLink(Link.UNSUPPORTED)));
        for (String f : List.of("CONNECTING", "LIVE", "STALE", "RECONNECTING", "DISCONNECTED", "ERROR")) {
            assertEquals(f, ResearchModeTradingProvider.feedName(data(Link.STREAMING, f, "LIVE", NOW)), "the service's feed state is shown as is");
        }
        TraderSnapshot none = new TraderSnapshot();
        none.feed = "NOT_CONFIGURED";
        ResearchModeTradingProvider.applyMarket(none, MarketData.idle());
        assertEquals("NOT_CONFIGURED", none.feed, "idle client leaves the existing 'nothing configured' state");
        assertNull(none.price);
        assertTrue(none.marketTrades.isEmpty());
    }

    @Test
    void lastTradedPriceAndMarkPriceStayDistinct() {
        TraderSnapshot t = snap(data(Link.STREAMING, "LIVE", "LIVE", NOW));
        assertEquals(2699.5, t.price);
        assertEquals(2698.9, t.markPrice);
        assertNotEquals(t.price, t.markPrice);
        assertEquals(6356094948.14, t.volume24h, "rolling 24h quote volume");
    }
}
