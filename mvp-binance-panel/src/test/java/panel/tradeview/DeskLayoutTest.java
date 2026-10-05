package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;

/**
 * Medidas da referência (trading-desk-1440/1600/1920): nada escala, a informação é reorganizada.
 * 1440 (x, y, w, h): cabeçalho 88,70,1332,60 · métricas 88,144,988,84 · gráfico 88,242,988,376 ·
 * coluna de contexto 1090,144,330,474 · blotter 88,632,1332,214. 1600 e 1920 seguem da mesma grade
 * (colunas 350 e 330+350; blotter 240 e 300; chart = altura restante).
 */
class DeskLayoutTest {
    private static void rect(String what, Bounds b, double x, double y, double w, double h) {
        assertEquals(x, b.getMinX(), 1, what + " x");
        assertEquals(y, b.getMinY(), 1, what + " y");
        assertEquals(w, b.getWidth(), 1, what + " w");
        assertEquals(h, b.getHeight(), 1, what + " h");
    }

    @Test
    void compact1440MatchesTheReferenceMeasurements() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.noFeed());
            assertEquals(DeskMode.COMPACT, d.desk.mode());
            rect("header", d.rect(d.desk.header()), 88, 70, 1332, 60);
            rect("metrics", d.rect(d.desk.metrics()), 88, 144, 988, 84);
            rect("chart", d.rect(d.desk.chart()), 88, 242, 988, 376);
            rect("context column", d.rect(d.desk.compactColumn()), 1090, 144, 330, 474);
            rect("blotter", d.rect(d.desk.blotter()), 88, 632, 1332, 214);
            assertEquals(3, d.desk.book().askRows().size());
            assertEquals(3, d.desk.book().bidRows().size());
            assertEquals(3, d.desk.trades().rows().size());
            assertEquals(7, d.desk.blotter().visiblePositionColumns());
            Bounds column = d.rect(d.desk.compactColumn());
            assertTrue(column.contains(d.rect(d.desk.botStrip()).getMinX(), d.rect(d.desk.botStrip()).getMinY()), "bot strip sits in the context column");
            assertTrue(d.desk.botStrip().isVisible(), "bot in a compact strip");
            d.close();
        });
    }

    @Test
    void standard1600StacksThreePanelsInAColumnOf350() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1600, 1000, DeskFixtures.noFeed());
            assertEquals(DeskMode.STANDARD, d.desk.mode());
            double mainW = 1600 - 68 - 40;
            rect("header", d.rect(d.desk.header()), 88, 70, mainW, 60);
            rect("metrics", d.rect(d.desk.metrics()), 88, 144, mainW - 350 - 14, 84);
            rect("chart", d.rect(d.desk.chart()), 88, 242, mainW - 350 - 14, 450);
            rect("blotter", d.rect(d.desk.blotter()), 88, 706, mainW - 350 - 14, 240);
            Bounds book = d.rect(d.desk.book());
            Bounds trades = d.rect(d.desk.trades());
            Bounds bot = d.rect(d.desk.botRows());
            assertEquals(1600 - 20 - 350, book.getMinX(), 1);
            assertEquals(350, book.getWidth(), 1);
            assertTrue(book.getMaxY() < trades.getMinY() && trades.getMaxY() < bot.getMinY(), "three stacked panels");
            assertEquals(6, d.desk.book().askRows().size());
            assertEquals(6, d.desk.book().bidRows().size());
            assertEquals(4, d.desk.trades().rows().size());
            assertEquals(8, d.desk.blotter().visiblePositionColumns());
            assertEquals(4, d.desk.botRows().rowCount(), "bot compact with four rows");
            assertNull(d.desk.botStrip().getScene(), "the compact strip exists only in the compact context column");
            d.close();
        });
    }

    @Test
    void expanded1920UsesTwoContextColumns() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.noFeed());
            assertEquals(DeskMode.EXPANDED, d.desk.mode());
            double mainW = 1920 - 68 - 40;
            double left = mainW - 330 - 350 - 28;
            rect("metrics", d.rect(d.desk.metrics()), 88, 144, left, 84);
            rect("chart", d.rect(d.desk.chart()), 88, 242, left, 470);
            rect("blotter", d.rect(d.desk.blotter()), 88, 726, left, 300);
            Bounds book = d.rect(d.desk.book());
            Bounds bot = d.rect(d.desk.botFull());
            assertEquals(330, book.getWidth(), 1, "Market column");
            assertEquals(350, bot.getWidth(), 1, "Context column");
            assertEquals(88 + left + 14, book.getMinX(), 1);
            assertEquals(book.getMaxX() + 14, bot.getMinX(), 1);
            assertEquals(10, d.desk.book().askRows().size());
            assertEquals(10, d.desk.book().bidRows().size());
            assertEquals(9, d.desk.trades().rows().size());
            assertEquals(10, d.desk.blotter().visiblePositionColumns());
            assertTrue(d.desk.botFull().rowCount() > d.desk.botRows().rowCount(), "complete bot");
            assertTrue(d.rect(d.desk.risk()).getMinY() > bot.getMaxY(), "Risk, Freshness and Activity are their own panels");
            assertTrue(d.rect(d.desk.freshness()).getMinY() > d.rect(d.desk.risk()).getMaxY());
            assertTrue(d.rect(d.desk.activity()).getMinY() > d.rect(d.desk.freshness()).getMaxY());
            d.close();
        });
    }

    @Test
    void theBlotterNeverGrowsAndTheRightColumnNeverOverflowsAtTheReferenceSizes() throws Exception {
        DeskHarness.fx(() -> {
            for (int[] s : new int[][] {{1440, 900}, {1600, 1000}, {1920, 1080}}) {
                DeskHarness d = DeskHarness.open(s[0], s[1], DeskFixtures.noFeed());
                double bottom = s[1] - 38 - 16;
                Bounds blotter = d.rect(d.desk.blotter());
                assertEquals(bottom, blotter.getMaxY(), 1, s[0] + " blotter ends above the dock padding");
                assertEquals(d.desk.mode().blotterHeight, blotter.getHeight(), 0.5);
                // com a cena de referência a coluna cabe: nenhuma barra de rolagem
                for (var sp : new javafx.scene.control.ScrollPane[] {d.desk.compactColumn()}) {
                    assertFalse(d.desk.mode() == DeskMode.COMPACT && sp.getContent().getLayoutBounds().getHeight() > sp.getViewportBounds().getHeight() + 1,
                            "1440x900 context column fits without scrolling");
                }
                d.close();
            }
        });
    }

    @Test
    void sanity1280x760ScrollsTheContextInsteadOfOverlappingIt() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1280, 760, DeskFixtures.noFeed());
            assertEquals(DeskMode.COMPACT, d.desk.mode());
            Bounds book = d.rect(d.desk.book());
            Bounds trades = d.rect(d.desk.trades());
            assertTrue(trades.getMinY() >= book.getMaxY() - 0.5, "panels do not overlap");
            assertEquals(214, d.rect(d.desk.blotter()).getHeight(), 0.5);
            assertTrue(d.rect(d.desk.chart()).getHeight() > 200, "the chart keeps usable height");
            d.close();
        });
    }

    @Test
    void switchingBreakpointReusesThePanelsAndKeepsTheData() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1440, 900, DeskFixtures.liveWithAccount(4));
            var book = d.desk.book();
            var trades = d.desk.trades();
            var header = d.desk.header();
            String price = header.priceLabel().getText();
            d.resize(1920, 1080);
            d.desk.onSnapshot(null);
            d.layout();
            assertEquals(DeskMode.EXPANDED, d.desk.mode());
            assertSame(book, d.desk.book());
            assertSame(trades, d.desk.trades());
            assertEquals(price, header.priceLabel().getText(), "the same price in every breakpoint");
            assertEquals(10, d.desk.book().askRows().size());
            assertFalse(d.desk.book().askRows().stream().anyMatch(GridRow::skeleton), "ten levels are filled by the same real data");
            d.resize(1440, 900);
            d.desk.onSnapshot(null);
            d.layout();
            assertEquals(DeskMode.COMPACT, d.desk.mode());
            assertEquals(3, d.desk.book().askRows().size());
            d.close();
        });
    }
}
