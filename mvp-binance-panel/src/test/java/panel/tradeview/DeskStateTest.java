package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.design.StatusState;
import panel.model.TraderSnapshot;
import panel.tradeview.BlotterPanel.Tab;
import panel.tradeview.BlotterPanel.TabState;
import panel.ui.trader.CandleChart;
import panel.util.Fmt;

/** Estados reais do Desk: nenhum dado de demonstração, sem feed nada é desenhado, STALE nunca parece vivo. */
class DeskStateTest {
    private static String all(DeskHarness d) {
        return String.join(" | ", DeskNodes.visibleTexts(d.desk));
    }

    @Test
    void noFeedShowsWaitingTextNaAndNoSimulatedValues() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.noFeed());
            var h = d.desk.header();
            assertEquals("—", h.priceLabel().getText());
            assertEquals(DeskModel.WAITING_TEXT, h.statusLabel().getText());
            assertEquals(StatusState.UNAVAILABLE, h.dot().state());
            assertTrue(h.dot().expected(), "no feed is the expected state: neutral dot, not red");
            assertEquals("LIVE OFF", ((javafx.scene.control.Labeled) d.desk.lookup("#desk-live-badge")).getText());
            var m = d.desk.metrics().cells();
            for (int i = 0; i < 4; i++) {
                assertEquals("N/A", m.get(i).value().getText(), "metric " + i);
            }
            assertEquals("No account", m.get(0).reason().getText());
            assertEquals("No positions", m.get(2).reason().getText());
            assertEquals("—", m.get(4).value().getText());
            assertEquals("No feed", m.get(4).reason().getText());
            assertNull(d.desk.chart().chart(), "no CandleChart is created without candles");
            assertEquals(DeskModel.WAITING_TEXT, d.desk.chart().placeholder().heading().getText());
            assertTrue(d.desk.chart().placeholder().isVisible());
            assertTrue(DeskNodes.all(d.desk, CandleChart.class).isEmpty());
            assertTrue(d.desk.book().askRows().stream().allMatch(GridRow::skeleton), "skeleton, not data");
            assertTrue(d.desk.book().bidRows().stream().allMatch(GridRow::skeleton));
            assertTrue(d.desk.book().askRows().stream().allMatch(r -> r.depthFraction() == 0), "a skeleton has no depth bars");
            assertTrue(d.desk.trades().rows().stream().allMatch(GridRow::skeleton));
            assertEquals("—", d.desk.book().midRow().cell(0).getText());
            String text = all(d);
            assertFalse(text.contains("DEMO"), text);
            assertFalse(text.contains("MOCK"), text);
            assertEquals(DeskModel.Bot.MONITORING, DeskModel.bot(d.current));
            assertEquals("Off", d.desk.botRows().liveRow().valueLabel().getText());
            assertEquals("Research · Live OFF", d.desk.botStrip().stripText().getText());
            for (Tab t : Tab.values()) {
                assertEquals(TabState.EMPTY, d.desk.blotter().state(t), t.name());
            }
            assertEquals("No open positions", d.desk.blotter().messageTitle(Tab.POSITIONS).getText());
            assertEquals("Positions 0", d.desk.blotter().button(Tab.POSITIONS).getText());
            d.close();
        });
    }

    @Test
    void waitingIsConnectingNotConfiguredIsNot() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.waiting());
            assertEquals(StatusState.CONNECTING, d.desk.header().dot().state());
            assertFalse(d.desk.header().dot().expected());
            d.close();
        });
    }

    @Test
    void liveFeedDrawsRealCandlesBookAndTradesButNoAccountValues() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.live(11);
            DeskHarness d = DeskHarness.open(1920, 1080, t);
            var h = d.desk.header();
            assertEquals(Fmt.price(t.price), h.priceLabel().getText());
            assertFalse(h.priceLabel().getStyleClass().contains("na"));
            assertEquals("Live", h.statusLabel().getText());
            assertEquals(StatusState.OPERATIONAL, h.dot().state());
            assertNotNull(d.desk.chart().chart());
            assertTrue(d.desk.chart().showingCandles());
            assertFalse(d.desk.chart().placeholder().isVisible());
            assertFalse(d.desk.chart().overlay().isVisible(), "a live chart has no status overlay");
            assertEquals(1.0, d.desk.chart().chart().getOpacity());
            // book: 10 níveis reais por lado; profundidade vem do valor
            var asks = d.desk.book().askRows();
            assertEquals(10, asks.size());
            assertTrue(asks.stream().noneMatch(GridRow::skeleton));
            assertEquals(Fmt.price(t.asks.get(9).price()), asks.getFirst().cell(0).getText(), "farthest ask on top");
            assertEquals(Fmt.price(t.asks.getFirst().price()), asks.getLast().cell(0).getText(), "best ask next to the mid");
            assertTrue(asks.getLast().depthFraction() > 0 && asks.getLast().depthFraction() <= 1);
            double best = DeskModel.book(t.asks, t.bids, 10).asks().stream().mapToDouble(DeskModel.BookRow::depth).max().orElse(0);
            assertEquals(best, asks.stream().mapToDouble(GridRow::depthFraction).max().orElse(0), 1e-9, "bars answer to the real totals");
            assertTrue(d.desk.book().midRow().cell(0).getText().matches("[\\d,]+\\.\\d\\d"));
            assertTrue(d.desk.trades().rows().stream().noneMatch(GridRow::skeleton));
            assertEquals(Fmt.price(t.marketTrades.getFirst().price()), d.desk.trades().rows().getFirst().cell(0).getText(), "Recent Trades come from the public market feed");
            assertNotEquals(t.tradeRows.getFirst()[4], d.desk.trades().rows().getFirst().cell(0).getText(), "...never from account fills");
            // sem conta continua N/A
            assertEquals("N/A", d.desk.metrics().cells().get(0).value().getText());
            assertEquals("<1 s", d.desk.metrics().cells().get(4).value().getText());
            assertTrue(d.desk.metrics().cells().get(4).value().getStyleClass().contains("ready"));
            d.close();
        });
    }

    @Test
    void anAccountFixtureFillsTheMetricsAndTheBlotter() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.liveWithAccount(2);
            DeskHarness d = DeskHarness.open(1920, 1080, t);
            var m = d.desk.metrics().cells();
            assertEquals("10,212.40", m.get(0).value().getText());
            assertEquals("+85.20", m.get(1).value().getText());
            assertTrue(m.get(1).value().getStyleClass().contains("pos"));
            assertEquals("-1.30%", m.get(3).value().getText());
            assertTrue(m.get(3).value().getStyleClass().contains("neg"));
            assertEquals("", m.get(0).reason().getText());
            assertEquals("Positions 1", d.desk.blotter().button(Tab.POSITIONS).getText());
            assertEquals(TabState.READY, d.desk.blotter().state(Tab.POSITIONS));
            assertEquals(TabState.READY, d.desk.blotter().state(Tab.ORDERS));
            assertEquals(TabState.READY, d.desk.blotter().state(Tab.SIGNALS));
            assertEquals(TabState.READY, d.desk.blotter().state(Tab.ACTIVITY));
            assertEquals(TabState.READY, d.desk.blotter().state(Tab.TRADES));
            assertEquals("LONG", d.desk.blotter().table(Tab.POSITIONS).getItems().getFirst()[1]);
            assertEquals("Monitoring ETHUSDT", d.desk.activity().lines().getFirst().getChildren().get(2) instanceof javafx.scene.control.Labeled l ? l.getText() : "");
            d.close();
        });
    }

    @Test
    void staleKeepsTheLastValueButNeverLooksLive() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.stale(5);
            DeskHarness d = DeskHarness.open(1440, 900, t);
            var h = d.desk.header();
            assertEquals("STALE", h.statusLabel().getText());
            assertTrue(h.priceLabel().getStyleClass().contains("stale"), "the number stays, dimmed");
            assertEquals(Fmt.price(t.price), h.priceLabel().getText());
            String update = ((javafx.scene.control.Label) d.desk.lookup(".byx-desk-update")).getText();
            assertEquals("LAST UPDATE " + Fmt.time(t.feedUpdatedAt), update);
            assertTrue(d.desk.chart().overlay().isVisible());
            assertTrue(d.desk.chart().overlay().getText().startsWith("STALE · LAST UPDATE"));
            assertEquals(0.6, d.desk.chart().chart().getOpacity(), 1e-9);
            var data = d.desk.metrics().cells();
            assertTrue(data.get(0).value().getStyleClass().contains("stale"));
            assertEquals("10,212.40", data.get(0).value().getText());
            assertTrue(data.get(4).value().getStyleClass().contains("stale"));
            assertTrue(data.get(4).reason().getText().startsWith("Last update"));
            assertTrue(d.desk.book().getChildren().get(2).lookup(".stale") != null, "book dimmed and marked");
            assertEquals("Stale", d.desk.freshness().row(0).valueLabel().getText());
            d.close();
        });
    }

    @Test
    void degradedErrorAndDisconnectedAreDifferentFromStale() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.degraded(1));
            assertEquals("Degraded", d.desk.header().statusLabel().getText());
            assertEquals(StatusState.DEGRADED, d.desk.header().dot().state());
            assertEquals("DEGRADED", d.desk.chart().overlay().getText());
            assertFalse(d.desk.header().priceLabel().getStyleClass().contains("stale"), "degraded is live but slower, not stale");

            d.show(DeskFixtures.disconnected(1));
            assertEquals("Disconnected", d.desk.header().statusLabel().getText());
            assertEquals("—", d.desk.header().priceLabel().getText(), "a drop never shows the old price as live");
            assertFalse(d.desk.chart().showingCandles());
            assertEquals("Feed disconnected", d.desk.chart().placeholder().heading().getText());
            assertTrue(d.desk.book().askRows().stream().noneMatch(r -> r.depthFraction() > 0));
            assertTrue(DeskNodes.visibleTexts(d.desk.book()).stream().anyMatch(s -> s.startsWith("No market data available")));
            assertFalse(d.desk.header().dot().expected(), "a drop is red, not the neutral expected dot");

            d.show(DeskFixtures.error());
            assertEquals("Feed error", d.desk.header().statusLabel().getText());
            assertEquals("Market feed error", d.desk.chart().placeholder().heading().getText());
            d.close();
        });
    }

    @Test
    void mockDataIsLabelledAndOnlyInMockMode() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.mock());
            assertEquals("Mock feed", d.desk.header().statusLabel().getText());
            assertTrue(d.desk.chart().overlay().getText().startsWith("MOCK DATA"));
            d.show(DeskFixtures.noFeed());
            assertFalse(all(d).contains("MOCK"));
            d.close();
        });
    }

    @Test
    void blotterTabsSupportLoadingEmptyReadyErrorAndUnavailable() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.noFeed());
            var b = d.desk.blotter();
            for (Tab tab : Tab.values()) {
                for (TabState s : TabState.values()) {
                    b.setState(tab, s);
                    assertEquals(s, b.state(tab));
                }
            }
            b.setState(Tab.SIGNALS, TabState.ERROR);
            assertEquals("Could not load signals", b.messageTitle(Tab.SIGNALS).getText());
            b.setState(Tab.ACTIVITY, TabState.UNAVAILABLE);
            assertEquals("Activity unavailable", b.messageTitle(Tab.ACTIVITY).getText());

            // mapeamento real a partir do snapshot
            TraderSnapshot t = DeskFixtures.noFeed();
            t.loading = true;
            assertEquals(TabState.LOADING, BlotterPanel.stateOf(Tab.POSITIONS, t, List.of()));
            t.loading = false;
            t.backendOnline = false;
            assertEquals(TabState.UNAVAILABLE, BlotterPanel.stateOf(Tab.SIGNALS, t, List.of()), "backend-derived tabs are unavailable when the backend is");
            assertEquals(TabState.UNAVAILABLE, BlotterPanel.stateOf(Tab.ACTIVITY, t, List.of()));
            assertEquals(TabState.EMPTY, BlotterPanel.stateOf(Tab.POSITIONS, t, List.of()), "positions do not depend on the backend: execution is off");
            assertEquals(TabState.READY, BlotterPanel.stateOf(Tab.SIGNALS, t, List.<String[]>of(new String[] {"x"})));
            d.show(t);
            assertEquals("Signals unavailable", b.messageTitle(Tab.SIGNALS).getText());
            d.close();
        });
    }

    @Test
    void positionsColumnsFollowTheBreakpointAndOtherTabsKeepTheirOwn() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.liveWithAccount(1));
            assertEquals(7, d.desk.blotter().visiblePositionColumns());
            assertEquals(7, d.desk.blotter().table(Tab.ORDERS).getColumns().size());
            assertEquals(6, d.desk.blotter().table(Tab.TRADES).getColumns().size());
            assertEquals(5, d.desk.blotter().table(Tab.SIGNALS).getColumns().size());
            assertEquals(3, d.desk.blotter().table(Tab.ACTIVITY).getColumns().size());
            d.resize(1600, 1000);
            d.desk.onSnapshot(null);
            assertEquals(8, d.desk.blotter().visiblePositionColumns());
            d.close();
        });
    }

    @Test
    void theContextTabsOfTheCompactColumnSwitchPagesWithoutRebuilding() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.liveWithAccount(1));
            var tabs = d.desk.contextTabs();
            int nodes = d.desk.nodeCount();
            assertTrue(d.desk.book().getParent().isManaged() && !d.desk.botRows().getParent().isManaged(), "Market page shown, Bot page hidden");
            tabs.click(1);
            d.layout();
            assertEquals(1, tabs.selected());
            assertTrue(d.desk.botRows().getParent().isManaged() && !d.desk.book().getParent().isManaged(), "Bot page shown");
            assertTrue(d.rect(d.desk.botRows()).getHeight() > 0 && d.rect(d.desk.activity()).getHeight() > 0);
            tabs.click(2);
            d.layout();
            assertTrue(d.desk.risk().getParent().isManaged() && d.rect(d.desk.risk()).getHeight() > 0 && d.rect(d.desk.freshness()).getHeight() > 0);
            assertEquals("1,250.00", d.desk.risk().row(0).valueLabel().getText());
            tabs.buttons().get(2).fire();
            assertEquals(2, tabs.selected(), "firing the selected tab never deselects it");
            tabs.click(0);
            d.layout();
            assertTrue(d.desk.book().getParent().isManaged());
            assertEquals(nodes, d.desk.nodeCount(), "switching tabs creates no node");
            assertEquals(List.of("Market", "Bot", "Risk"), tabs.buttons().stream().map(javafx.scene.control.ToggleButton::getText).toList());
            d.close();
        });
    }

    @Test
    void theBlotterTabsSwitchTheVisibleTableWithoutRebuildingAndKeepTheState() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.liveWithAccount(1));
            var b = d.desk.blotter();
            int nodes = d.desk.nodeCount();
            var tables = new java.util.ArrayList<javafx.scene.control.TableView<String[]>>();
            for (Tab tab : Tab.values()) {
                tables.add(b.table(tab));
                b.button(tab).fire();
                d.layout();
                assertEquals(tab, b.selected());
                for (Tab other : Tab.values()) {
                    assertEquals(other == tab, b.table(other).getParent().isManaged(), tab + " selected: only its page is shown");
                }
                assertFalse(b.table(tab).getItems().isEmpty(), tab.name());
            }
            assertEquals(nodes, d.desk.nodeCount());
            for (int i = 0; i < Tab.values().length; i++) {
                assertSame(tables.get(i), b.table(Tab.values()[i]));
            }
            d.close();
        });
    }
}
