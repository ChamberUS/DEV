package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.homeview.HomeMarkets;
import panel.homeview.HomeScreen;
import panel.homeview.ServiceCards;
import panel.model.ByxSnapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** B01 in the real JavaFX scene graph: honest states, no demo numbers, Terminal navigation only where supported. */
class HomeScreenTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private static final class Fixture implements HomeScreen.Data {
        TraderSnapshot t = new TraderSnapshot();
        HomeMarkets.Catalog catalog = HomeMarkets.productionCatalog();
        boolean research;
        boolean wallet;
        int replays;
        final List<String> navigated = new ArrayList<>();
        HomeScreen screen;
        Stage stage = new Stage();

        Fixture() {
            t.symbol = "ETHUSDT";
            t.market = "USD-M Futures";
            t.feed = "LIVE";
            t.feedUpdatedAt = NOW.minusSeconds(2);
            t.price = 3000.5;
            t.change24hPct = -1.25;
            t.volume24h = 98765.0;
        }

        Fixture show() {
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.OFF);
            screen = new HomeScreen(motion, Clock.fixed(NOW, ZoneOffset.UTC), this, navigated::add);
            Scene scene = new Scene((Parent) screen.node(), 1440, 900);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            stage.show();
            screen.onShow();
            ((Parent) screen.node()).applyCss();
            ((Parent) screen.node()).layout();
            return this;
        }

        void refresh() {
            screen.onSnapshot(null);
            ((Parent) screen.node()).applyCss();
            ((Parent) screen.node()).layout();
        }

        void close() {
            screen.dispose();
            stage.close();
        }

        @Override public String displayName() { return "demo.user"; }
        @Override public TraderSnapshot trader() { return t; }
        @Override public ByxSnapshot network() {
            return ByxSnapshot.unknown("TEST", "LOCALNET", "ONLINE", null);
        }
        @Override public boolean researchVisible() { return research; }
        @Override public boolean walletVerified() { return wallet; }
        @Override public HomeMarkets.Catalog catalog() { return catalog; }
        @Override public void retryCatalog() { }
        @Override public void replayWelcome() { replays++; }
    }

    private static String texts(Node root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return String.join("\n", out);
    }

    private static void collect(Node n, List<String> out) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (n instanceof Button b && b.getGraphic() != null) {
            collect(b.getGraphic(), out);
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), out);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, out));
        }
    }

    private static List<Button> rows(Node root) {
        return root.lookupAll(".byx-home-row").stream().map(n -> (Button) n).toList();
    }

    @Test
    void showsExactlyTheSupportedPerpetualWithRealValuesOnly() throws Exception {
        String text = FxSupport.fx(() -> {
            Fixture f = new Fixture().show();
            String s = texts(f.screen.node());
            assertEquals(1, rows(f.screen.node()).size());
            assertEquals(HomeMarkets.State.READY, f.screen.model().state());
            f.close();
            return s;
        });
        assertTrue(text.contains("ETHUSDT"));
        assertTrue(text.contains("PERPETUAL"));
        assertTrue(text.contains("3,000.50"));
        assertTrue(text.contains("-1.25%"));
        assertTrue(text.contains("98,765 USDT"));
        assertFalse(text.contains("BTC"), "no invented symbols");
        assertFalse(text.contains("DEMO DATA"), "real feed values carry no demo badge");
        assertTrue(text.contains("Welcome back, demo.user"));
        assertTrue(text.contains("BETA") && text.contains("LIVE TRADING OFF") && text.contains("LOCALNET"));
    }

    @Test
    void missingNumbersRenderAsDashesNeverZero() throws Exception {
        String text = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.t.change24hPct = null;
            f.t.volume24h = null;
            f.show();
            assertEquals(HomeMarkets.State.PARTIAL_DATA, f.screen.model().state());
            String s = texts(f.screen.node());
            f.close();
            return s;
        });
        assertTrue(text.contains("—"));
        assertFalse(text.contains("0.00%"), "an absent change is not 0.00%");
        assertFalse(text.contains("0 USDT"), "an absent volume is not 0");
        assertTrue(text.contains("not zero") || text.contains("not provided"));
    }

    @Test
    void staleOfflineAndErrorStatesAreExplicit() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture().show();
            f.t.feed = "RECONNECTING";
            f.t.feedUpdatedAt = NOW.minusSeconds(130);
            f.refresh();
            Object[] stale = {f.screen.model().state(), texts(f.screen.node())};
            f.t.feed = "DISCONNECTED";
            f.t.price = null;
            f.t.change24hPct = null;
            f.t.volume24h = null;
            f.t.feedUpdatedAt = null;
            f.refresh();
            Object[] off = {f.screen.model().state(), texts(f.screen.node())};
            f.catalog = new HomeMarkets.Catalog.Failed("boom");
            f.refresh();
            Object[] err = {f.screen.model().state(), texts(f.screen.node())};
            f.close();
            return new Object[] {stale, off, err};
        });
        Object[] stale = (Object[]) r[0];
        assertEquals(HomeMarkets.State.STALE, stale[0]);
        assertTrue(((String) stale[1]).contains("Data may be out of date."));
        assertTrue(((String) stale[1]).contains("2m 10s"), "age is shown");
        Object[] off = (Object[]) r[1];
        assertEquals(HomeMarkets.State.OFFLINE, off[0]);
        assertFalse(((String) off[1]).contains("3,000"), "no stale price after the data is gone");
        Object[] err = (Object[]) r[2];
        assertEquals(HomeMarkets.State.ERROR, err[0]);
        assertTrue(((String) err[1]).contains("Couldn’t load markets"));
        assertTrue(((String) err[1]).contains("Try again"));
        assertFalse(((String) err[1]).contains("No markets are available"), "failed request is not an empty catalog");
    }

    @Test
    void unsupportedSearchSaysSoAndListsWhatExists() throws Exception {
        String text = FxSupport.fx(() -> {
            Fixture f = new Fixture().show();
            f.screen.searchField().setText("BTC spot");
            f.screen.node().applyCss();
            assertEquals(HomeMarkets.State.UNSUPPORTED_SEARCH, f.screen.model().state());
            assertEquals(0, rows(f.screen.node()).size());
            String s = texts(f.screen.node());
            f.screen.searchField().clear();
            assertEquals(HomeMarkets.State.READY, f.screen.model().state());
            f.close();
            return s;
        });
        assertTrue(text.contains("“BTC spot” isn’t supported in this beta"));
        assertTrue(text.contains("ETHUSDT"), "lists the real catalog");
    }

    @Test
    void terminalNavigationOnlyForSupportedInstruments() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.catalog = new HomeMarkets.Catalog.Loaded(List.of(
                    new HomeMarkets.Instrument("ETHUSDT", HomeMarkets.Kind.PERPETUAL, "Binance USD-M Futures", "USDT"),
                    new HomeMarkets.Instrument("ETHUSDT", HomeMarkets.Kind.SPOT, "Binance Spot", "USDT")));
            f.show();
            List<Button> rows = rows(f.screen.node());
            assertEquals(2, rows.size());
            rows.get(1).fire(); // spot
            Object[] afterSpot = {List.copyOf(f.navigated), f.screen.terminalNote().isVisible(), f.screen.terminalNote().getText()};
            rows.get(0).fire(); // perpetual
            Object[] out = {afterSpot, List.copyOf(f.navigated)};
            f.close();
            return out;
        });
        Object[] spot = (Object[]) r[0];
        assertEquals(List.of(), spot[0], "spot has no Terminal view");
        assertTrue((boolean) spot[1]);
        assertEquals("The Terminal shows perpetual futures only. Spot has no Terminal view in this beta.", spot[2]);
        assertEquals(List.of("t-desk"), r[1]);
    }

    @Test
    void serviceCardsAreHonestAndOnlyActionableOnesNavigate() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.research = true;
            f.show();
            Set<String> actionable = f.screen.node().lookupAll(".byx-home-card-button").stream()
                    .map(n -> (String) n.getProperties().get("home.card")).collect(Collectors.toSet());
            f.screen.node().lookupAll(".byx-home-card-button").forEach(n -> ((Button) n).fire());
            Object[] out = {actionable, f.screen.cards().stream().filter(c -> !c.actionable()).map(ServiceCards.Card::id).toList(), List.copyOf(f.navigated),
                    texts(f.screen.node())};
            f.close();
            return out;
        });
        @SuppressWarnings("unchecked")
        Set<String> actionable = (Set<String>) r[0];
        assertFalse(actionable.contains("wallet"), "unavailable wallet is not a button");
        assertTrue(actionable.containsAll(Set.of("markets", "desk", "network", "benefits", "help", "research")));
        assertEquals(List.of("wallet"), r[1]);
        assertTrue(((String) r[3]).contains("Needs a configured test network"));
        assertTrue(((List<?>) r[2]).contains("overview"), "Research card only requests the route; the router gate verifies");
    }

    @Test
    void timerRunsOnlyWhileShownAndSurvivesRepeatedShowHide() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture().show();
            boolean on = f.screen.timerRunning();
            for (int i = 0; i < 20; i++) {
                f.screen.onHide();
                f.screen.onShow();
            }
            boolean stillOne = f.screen.timerRunning();
            f.screen.onHide();
            boolean off = f.screen.timerRunning();
            f.screen.onShow();
            f.screen.dispose();
            Object[] out = {on, stillOne, off, f.screen.timerRunning()};
            f.stage.close();
            return out;
        });
        assertEquals(true, r[0]);
        assertEquals(true, r[1]);
        assertEquals(false, r[2]);
        assertEquals(false, r[3], "dispose stops the timer");
    }
}
