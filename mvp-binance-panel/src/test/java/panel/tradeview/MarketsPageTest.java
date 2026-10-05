package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.tradeview.BlotterPanel.Tab;

/** Markets &amp; Portfolio V2: mesmo estado real do Desk, nenhum dado de demonstração, só navegação como controle. */
class MarketsPageTest {
    private static final class Fx {
        final DeskHarness.TestClock clock = new DeskHarness.TestClock();
        final List<String> routes = new ArrayList<>();
        TraderSnapshot current;
        final MarketsPage page;
        final Scene scene;

        Fx(TraderSnapshot first) {
            current = first;
            page = new MarketsPage(() -> current, clock, routes::add);
            scene = new Scene(page, 1440 - 68, 900 - 94);
            ByxTheme.apply(scene);
            show(first);
        }

        void show(TraderSnapshot t) {
            current = t;
            page.onSnapshot(null);
            for (int i = 0; i < 3; i++) {
                page.applyCss();
                page.layout();
            }
        }
    }

    @Test
    void noFeedShowsTheRealRowWithNaAndNoDemoData() throws Exception {
        DeskHarness.fx(() -> {
            Fx f = new Fx(DeskFixtures.noFeed());
            assertEquals("ETHUSDT", f.page.symbolLabel().getText());
            assertEquals("—", f.page.lastLabel().getText());
            assertEquals("NO FEED", f.page.stateBadge().getText());
            assertEquals("N/A", f.page.equityLabel().getText());
            assertEquals("Not connected", f.page.freshness().row(0).valueLabel().getText());
            assertEquals("Unavailable", f.page.risk().row(2).valueLabel().getText());
            assertEquals(BlotterPanel.TabState.EMPTY, f.page.blotter().state(Tab.POSITIONS));
            assertEquals("History", f.page.blotter().button(Tab.TRADES).getText());
            String text = String.join(" | ", DeskNodes.visibleTexts(f.page));
            assertFalse(text.contains("DEMO") || text.contains("MOCK"), text);
            assertEquals(6, f.page.blotter().visiblePositionColumns());
            return null;
        });
    }

    @Test
    void aLiveFixtureFillsTheRowAndStaleNeverLooksLive() throws Exception {
        DeskHarness.fx(() -> {
            Fx f = new Fx(DeskFixtures.liveWithAccount(2));
            assertEquals(panel.util.Fmt.price(f.current.price), f.page.lastLabel().getText());
            assertEquals("LIVE", f.page.stateBadge().getText());
            assertEquals("10,212.40", f.page.equityLabel().getText());
            assertEquals(TabStateOf.ready(), f.page.blotter().state(Tab.POSITIONS));
            f.show(DeskFixtures.stale(2));
            assertEquals("STALE", f.page.stateBadge().getText());
            assertTrue(f.page.lastLabel().getStyleClass().contains("stale"));
            f.show(DeskFixtures.disconnected(2));
            assertEquals("—", f.page.lastLabel().getText(), "a drop never shows the old price");
            assertEquals("DISCONNECTED", f.page.stateBadge().getText());
            return null;
        });
    }

    private static final class TabStateOf {
        static BlotterPanel.TabState ready() {
            return BlotterPanel.TabState.READY;
        }
    }

    @Test
    void theOnlyControlsAreNavigationAndTabs() throws Exception {
        DeskHarness.fx(() -> {
            Fx f = new Fx(DeskFixtures.liveWithAccount(1));
            List<String> allowed = List.of("Portfolio", "Performance", "Positions 1", "Orders 1", "History");
            for (ButtonBase b : DeskNodes.all(f.page, ButtonBase.class)) {
                assertTrue(allowed.contains(b.getText()), "unexpected control " + b.getText());
            }
            f.page.portfolioTab().fire();
            f.page.performanceTab().fire();
            assertEquals(List.of("t-portfolio", "t-performance"), f.routes);
            assertTrue(DeskNodes.all(f.page, javafx.scene.control.TextInputControl.class).isEmpty());
            return null;
        });
    }

    @Test
    void repeatedIdenticalPollsTouchNothingAndTheTableStaysIncremental() throws Exception {
        DeskHarness.fx(() -> {
            Fx f = new Fx(DeskFixtures.noFeed());
            f.current = DeskFixtures.noFeed();
            int applied = f.page.applied();
            List<String> digest = DeskNodes.digest(f.page);
            for (int i = 0; i < 100; i++) {
                f.show(DeskFixtures.noFeed());
            }
            assertEquals(applied, f.page.applied());
            assertEquals(100, f.page.skipped());
            assertEquals(digest, DeskNodes.digest(f.page));
            var table = f.page.blotter().table(Tab.POSITIONS);
            var rows = new ArrayList<String[]>();
            for (int i = 0; i < 120; i++) {
                TraderSnapshot t = DeskFixtures.liveWithAccount(i);
                t.positionRows.getFirst()[5] = "+" + i + ".00";
                f.show(t);
                rows.add(table.getItems().getFirst());
            }
            assertEquals(1, table.getItems().size());
            assertEquals("+119.00", table.getItems().getFirst()[5]);
            return null;
        });
    }

    @Test
    void theV2PageCarriesNoLegacyStyle() throws Exception {
        DeskHarness.fx(() -> {
            Fx f = new Fx(DeskFixtures.noFeed());
            assertEquals(ByxTheme.urls(), f.scene.getStylesheets());
            for (String legacy : List.of("card", "card-title", "badge", "badge-warn", "muted", "kv-value", "metric")) {
                DeskNodes.walk(f.page, n -> assertFalse(n.getStyleClass().contains(legacy), legacy));
            }
            assertEquals("JetBrains Mono SemiBold", f.page.equityLabel().getFont().getFamily());
            assertEquals(30, f.page.equityLabel().getFont().getSize(), 0.01);
            return null;
        });
    }
}
