package panel.tradeview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.ui.View;
import panel.util.Fmt;

/**
 * Markets &amp; Portfolio V2 (referência screens/trading/markets-portfolio): Market watch com o mercado atual, faixa
 * Equity / Daily PnL / Positions / Orders, blotter Positions / Orders / History e, à direita, Risk e Data freshness.
 * Só estado real: sem feed a linha mostra "—" e NO FEED; sem conta, N/A. As abas Portfolio e Performance
 * são as rotas existentes (não são conteúdo inventado aqui).
 */
public final class MarketsPage extends GridPane implements View {
    private static final double[] WEIGHTS = {1.4, 1, 1, 1, 1, 1, 1};

    private final Supplier<TraderSnapshot> source;
    private final Clock clock;
    private final Label symbol = Fx.label("N/A", "byx-desk-row-value", "byx-markets-symbol");
    private final Label kind = Fx.label("Perp", "byx-desk-t3");
    private final Label venue = Fx.label("Binance USD-M", "byx-desk-cell-text");
    private final Label last = Fx.label("—", "byx-desk-cell-mono");
    private final Label change = Fx.label("—", "byx-desk-cell-mono");
    private final Label volume = Fx.label("—", "byx-desk-cell-mono");
    private final Label freshness = Fx.label("—", "byx-desk-cell-mono");
    private final Label state = ByxBadge.of("NO FEED", ByxBadge.Tone.NEUTRAL);
    private final Label equity = Fx.label("N/A", "byx-markets-big");
    private final Label pnl = Fx.label("N/A", "byx-markets-big");
    private final Label positions = Fx.label("0", "byx-markets-big");
    private final Label orders = Fx.label("0", "byx-markets-big");
    private final Label riskNote = Fx.label("Limits and exposure appear when live trading is enabled and an account is connected.",
            "byx-desk-secondary", "byx-desk-body");
    private final InfoPanel risk = new InfoPanel("markets-risk", "Risk", "Exposure", "Drawdown", "Risk limits");
    private final InfoPanel fresh = new InfoPanel("markets-freshness", "Data freshness", "Binance feed", "Last tick");
    private final BlotterPanel blotter = new BlotterPanel(List.of(BlotterPanel.Tab.POSITIONS, BlotterPanel.Tab.ORDERS, BlotterPanel.Tab.TRADES),
            Map.of(BlotterPanel.Tab.TRADES, "History"), false);
    private final Button portfolioTab = new Button("Portfolio");
    private final Button performanceTab = new Button("Performance");
    private int lastFingerprint;
    private DeskModel.Feed lastFeed;
    private boolean first = true;
    private int applied;
    private int skipped;

    public MarketsPage(Supplier<TraderSnapshot> source, Clock clock, Consumer<String> navigate) {
        this.source = source;
        this.clock = clock;
        getStyleClass().add("byx-desk");
        setId("markets");
        setMinSize(0, 0);
        setPadding(new Insets(14, 20, 16, 20));
        setHgap(14);
        setVgap(14);
        setAlignment(Pos.TOP_LEFT);
        ColumnConstraints left = new ColumnConstraints();
        left.setHgrow(Priority.ALWAYS);
        left.setMinWidth(0);
        getColumnConstraints().addAll(left, new ColumnConstraints(340, 340, 340));
        for (int i = 0; i < 3; i++) {
            RowConstraints r = new RowConstraints();
            r.setVgrow(Priority.NEVER);
            getRowConstraints().add(r);
        }

        portfolioTab.setOnAction(e -> navigate.accept("t-portfolio"));
        performanceTab.setOnAction(e -> navigate.accept("t-performance"));
        for (Button b : new Button[] {portfolioTab, performanceTab}) {
            b.getStyleClass().add("byx-desk-tab");
            b.setTooltip(new javafx.scene.control.Tooltip("Opens the " + b.getText() + " screen"));
        }
        Label watch = Fx.label("Market watch", "byx-desk-tab", "selected-label");
        HBox tabs = new HBox(24, watch, portfolioTab, performanceTab);
        tabs.getStyleClass().add("byx-desk-tabs");
        tabs.setAlignment(Pos.CENTER_LEFT);

        GridPane table = new GridPane();
        table.getStyleClass().add("byx-markets-table");
        for (double w : WEIGHTS) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(w * 100 / java.util.Arrays.stream(WEIGHTS).sum());
            table.getColumnConstraints().add(c);
        }
        String[] heads = {"Symbol", "Venue", "Last", "24h", "Volume", "Freshness", "State"};
        for (int i = 0; i < heads.length; i++) {
            Label h = ByxFonts.upper(Fx.label(heads[i], "byx-label"));
            h.getStyleClass().add("byx-markets-head");
            table.add(h, i, 0);
        }
        HBox sym = Fx.row(8, symbol, kind);
        table.add(sym, 0, 1);
        Node[] cells = {venue, last, change, volume, freshness, state};
        for (int i = 0; i < cells.length; i++) {
            table.add(cells[i], i + 1, 1);
        }
        for (Node n : table.getChildren()) {
            GridPane.setValignment(n, javafx.geometry.VPos.CENTER);
        }
        VBox watchPanel = new VBox(6, tabs, table);
        watchPanel.getStyleClass().add("byx-panel");
        watchPanel.setId("markets-watch");

        HBox strip = new HBox(48, stat("Equity", equity), stat("Daily PnL", pnl), stat("Positions", positions), stat("Orders", orders));
        strip.getStyleClass().add("byx-panel");
        strip.setId("markets-strip");

        blotter.setFixedHeight(260, 6);

        VBox right = new VBox(6, ByxFonts.upper(Fx.label("Risk", "byx-label")), riskNote);
        risk.setInline(true);
        fresh.setInline(true);
        riskNote.setWrapText(true);
        // o painel da direita repete a referência: rótulo "k" + descrição e as linhas, sem títulos de painel próprios
        risk.getChildren().remove(0);
        fresh.getChildren().remove(0);
        Label freshHead = ByxFonts.upper(Fx.label("Data freshness", "byx-label"));
        VBox.setMargin(freshHead, new Insets(14, 0, 0, 0));
        right.getChildren().addAll(risk, freshHead, fresh);
        right.getStyleClass().add("byx-panel");
        right.setId("markets-right");
        right.setMinHeight(0);

        GridPane.setConstraints(watchPanel, 0, 0);
        GridPane.setConstraints(strip, 0, 1);
        GridPane.setConstraints(blotter, 0, 2);
        GridPane.setConstraints(right, 1, 0, 1, 3);
        GridPane.setValignment(right, javafx.geometry.VPos.TOP);
        getChildren().addAll(watchPanel, strip, blotter, right);
    }

    private static VBox stat(String title, Label value) {
        return new VBox(ByxFonts.upper(Fx.label(title, "byx-label")), value);
    }

    @Override
    public Node node() {
        return this;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        update(source.get());
    }

    BlotterPanel blotter() {
        return blotter;
    }

    InfoPanel risk() {
        return risk;
    }

    InfoPanel freshness() {
        return fresh;
    }

    int applied() {
        return applied;
    }

    int skipped() {
        return skipped;
    }

    Label stateBadge() {
        return state;
    }

    Label lastLabel() {
        return last;
    }

    Label symbolLabel() {
        return symbol;
    }

    Label equityLabel() {
        return equity;
    }

    Button portfolioTab() {
        return portfolioTab;
    }

    Button performanceTab() {
        return performanceTab;
    }

    void update(TraderSnapshot t) {
        Instant now = clock.instant();
        DeskModel.Feed feed = DeskModel.feed(t, now);
        int fp = DeskModel.fingerprint(t);
        if (!first && fp == lastFingerprint && feed == lastFeed && t.feedUpdatedAt == null) {
            skipped++;
            return;
        }
        first = false;
        lastFingerprint = fp;
        lastFeed = feed;
        applied++;
        boolean data = feed.showsMarketData();
        Fx.text(symbol, t.symbol == null || t.symbol.isBlank() ? Fmt.NA : t.symbol);
        Fx.text(kind, DeskModel.perpetual(t.market) ? "Perp" : "");
        Fx.text(venue, DeskModel.venue(t.market).replaceFirst(" Futures$", ""));
        Fx.text(last, data && t.price != null ? Fmt.price(t.price) : "—");
        Fx.text(change, data && t.change24hPct != null ? Fmt.signed(t.change24hPct, "%") : "—");
        Fx.tone(change, data && t.change24hPct != null ? (t.change24hPct < 0 ? "neg" : t.change24hPct > 0 ? "pos" : null) : null, "neg", "pos");
        Fx.text(volume, data && t.volume24h != null ? DeskModel.volume(t.volume24h) : "—");
        boolean known = data && t.feedUpdatedAt != null;
        Fx.text(freshness, known ? DeskModel.age(Duration.between(t.feedUpdatedAt, now)) : "—");
        Fx.text(state, feed == DeskModel.Feed.NO_FEED || feed == DeskModel.Feed.WAITING ? "NO FEED" : feed.label.toUpperCase(java.util.Locale.ROOT));
        Fx.tone(state, switch (feed) {
            case LIVE -> "tone-pos";
            case MOCK, STALE, DEGRADED, RECONNECTING -> "tone-wrn";
            case DISCONNECTED, ERROR, UNAVAILABLE -> "tone-neg";
            default -> null;
        }, "tone-pos", "tone-wrn", "tone-neg");
        Fx.cls(last, "stale", feed.looksStale());
        Fx.cls(change, "stale", feed.looksStale());

        Fx.text(equity, t.equity == null ? Fmt.NA : Fmt.price(t.equity));
        Fx.text(pnl, t.dailyPnl == null ? Fmt.NA : Fmt.signed(t.dailyPnl, ""));
        Fx.text(positions, String.valueOf(t.positions));
        Fx.text(orders, String.valueOf(t.orders));
        Fx.cls(equity, "na", t.equity == null);
        Fx.cls(pnl, "na", t.dailyPnl == null);
        Fx.cls(positions, "na", t.positions == 0);
        Fx.cls(orders, "na", t.orders == 0);

        var cells = DeskModel.metrics(t, feed, now);
        risk.row(0).set(cells.get(2).value(), true, cells.get(2).state() == DeskModel.CellState.NA ? "dim" : null);
        risk.row(1).set(cells.get(3).value(), true, cells.get(3).state() == DeskModel.CellState.NA ? "dim" : null);
        risk.row(2).set("Unavailable", false, "dim");
        fresh.row(0).set(feed.freshnessText, false, feed.waiting() ? "dim" : feed.looksStale() ? "warn" : null);
        fresh.row(1).set(known ? Fmt.time(t.feedUpdatedAt) : "—", true, known ? null : "dim");
        blotter.apply(t);
    }
}
