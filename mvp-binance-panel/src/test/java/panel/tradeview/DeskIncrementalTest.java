package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import panel.model.TraderSnapshot;
import panel.tradeview.BlotterPanel.Tab;

/** 100+ atualizações consecutivas: mesmos nós, sem duplicar linhas, sem vazar listener ou Timeline, sem reconstrução. */
class DeskIncrementalTest {
    private static Set<javafx.scene.Node> identities(javafx.scene.Node root) {
        Set<javafx.scene.Node> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        DeskNodes.walk(root, out::add);
        return out;
    }

    @Test
    void a150TickStreamReusesEveryNodeAndNeverRebuilds() throws Exception {
        DeskHarness.fx(() -> {
            for (int[] size : new int[][] {{1440, 900}, {1600, 1000}, {1920, 1080}}) {
                DeskHarness d = DeskHarness.open(size[0], size[1], DeskFixtures.liveWithAccount(0));
                int nodes = d.desk.nodeCount();
                Set<javafx.scene.Node> before = identities(d.desk);
                var chart = d.desk.chart().chart();
                var asks = new ArrayList<>(d.desk.book().askRows());
                var bids = new ArrayList<>(d.desk.book().bidRows());
                var rows = new ArrayList<>(d.desk.trades().rows());
                var positions = d.desk.blotter().table(Tab.POSITIONS);
                var cols = List.copyOf(positions.getColumns());
                int loops = d.motion.loopCount();
                int appliedBefore = d.desk.applied();
                for (int i = 1; i <= 150; i++) {
                    TraderSnapshot t = DeskFixtures.liveWithAccount(i);
                    t.positionRows.getFirst()[5] = String.format("%+.2f", i * 0.37 - 20); // uPnL muda a cada tick
                    d.show(t);
                }
                assertEquals(nodes, d.desk.nodeCount(), size[0] + ": node count is constant across 150 ticks");
                assertEquals(before, identities(d.desk), size[0] + ": every node is the same instance");
                assertSame(chart, d.desk.chart().chart(), "the chart is redrawn in place");
                assertEquals(asks, d.desk.book().askRows());
                assertEquals(bids, d.desk.book().bidRows());
                assertEquals(rows, d.desk.trades().rows());
                assertSame(positions, d.desk.blotter().table(Tab.POSITIONS));
                assertEquals(cols, positions.getColumns());
                assertEquals(1, positions.getItems().size(), "no row duplication");
                assertEquals(String.format("%+.2f", 150 * 0.37 - 20), positions.getItems().getFirst()[5]);
                assertEquals(loops, d.motion.loopCount(), "no loop accumulated");
                assertEquals(appliedBefore + 150, d.desk.applied(), "every changed tick applied exactly once");
                // o último tick está de fato na tela
                TraderSnapshot last = d.current;
                assertEquals(panel.util.Fmt.price(last.price), d.desk.header().priceLabel().getText());
                d.close();
            }
        });
    }

    @Test
    void identicalSnapshotsTouchNothing() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.liveWithAccount(9));
            List<String> digest = DeskNodes.digest(d.desk);
            int applied = d.desk.applied();
            int skipped = d.desk.skipped();
            for (int i = 0; i < 120; i++) {
                d.show(DeskFixtures.liveWithAccount(9)); // novo objeto, mesmo conteúdo (um poll sem mudança)
            }
            assertEquals(applied, d.desk.applied(), "an unchanged poll does not re-apply");
            assertEquals(skipped + 120, d.desk.skipped());
            assertEquals(digest, DeskNodes.digest(d.desk));
            d.close();
        });
    }

    @Test
    void tableRowsAreReplacedOnlyWhenTheirContentChanges() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.liveWithAccount(1);
            DeskHarness d = DeskHarness.open(1920, 1080, t);
            var table = d.desk.blotter().table(Tab.POSITIONS);
            String[] first = table.getItems().getFirst();
            TraderSnapshot same = DeskFixtures.liveWithAccount(2); // outro book, mesma posição
            d.show(same);
            assertSame(first, table.getItems().getFirst(), "an equal row keeps its instance");
            TraderSnapshot changed = DeskFixtures.liveWithAccount(3);
            changed.positionRows.getFirst()[2] = "0.500";
            d.show(changed);
            assertNotSame(first, table.getItems().getFirst());
            // cresce e encolhe sem duplicar
            TraderSnapshot grow = DeskFixtures.liveWithAccount(3);
            for (int i = 0; i < 20; i++) {
                grow.positionRows.add(new String[] {"X" + i, "LONG", "1", "1", "1", "+0.00", "1", "1x", "1", "t"});
            }
            grow.positions = grow.positionRows.size();
            d.show(grow);
            assertEquals(21, table.getItems().size());
            assertEquals(21, new java.util.HashSet<>(table.getItems().stream().map(r -> r[0] + r[2]).toList()).size());
            d.show(DeskFixtures.noFeed());
            assertEquals(0, table.getItems().size());
            d.close();
        });
    }

    @Test
    void theAgeTickerExistsOnlyWhileVisibleAndNeverAccumulates() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.live(1);
            DeskHarness d = DeskHarness.open(1440, 900, t); // feedUpdatedAt conhecido
            assertTrue(d.desk.tickerRunning());
            d.desk.onShow();
            d.desk.onShow();
            for (int i = 0; i < 20; i++) {
                d.show(DeskFixtures.live(1));
            }
            assertTrue(d.desk.tickerRunning());
            d.desk.onHide();
            assertFalse(d.desk.tickerRunning(), "hidden Desk keeps no timer");
            d.desk.onShow();
            assertTrue(d.desk.tickerRunning());
            // sem horário de feed (real hoje) não há timer nenhum
            d.show(DeskFixtures.noFeed());
            assertFalse(d.desk.tickerRunning(), "no feed timestamp, no clock");
            d.close();
        });
    }

    @Test
    void theAgeAdvancesWithTheClockAndTurnsStaleWithoutNewData() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.liveWithAccount(1));
            var age = d.desk.metrics().cells().get(4).value();
            assertEquals("<1 s", age.getText());
            d.clock.now = DeskFixtures.NOW.plusSeconds(7);
            d.desk.onSnapshot(null);
            assertEquals("7.0 s", age.getText());
            assertEquals("Live", d.desk.header().statusLabel().getText());
            d.clock.now = DeskFixtures.NOW.plusSeconds(40);
            d.desk.onSnapshot(null);
            assertEquals("STALE", d.desk.header().statusLabel().getText(), "an old timestamp makes the feed stale on its own");
            assertTrue(age.getStyleClass().contains("stale"));
            assertEquals("40 s", age.getText());
            d.close();
        });
    }
}
