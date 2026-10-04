package panel.ui.trader;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.DataSource;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Level;
import panel.ui.EmptyState;
import panel.ui.Ui;
import panel.util.Fmt;

public class TradingDeskView extends TraderPage {
    private int tab;

    public TradingDeskView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(TraderSnapshot t, VBox page) {
        page.getChildren().add(header(t));

        Node chart = t.candles.isEmpty() ? chartPlaceholder(t) : new CandleChart(t.candles);
        HBox timeframes = new HBox(8);
        for (String interval : new String[] {"1m", "5m", "15m", "1h", "4h"}) {
            var button = new javafx.scene.control.ToggleButton(interval);
            button.getStyleClass().add("chip");
            button.setSelected(interval.equals("1m"));
            button.setDisable(!interval.equals("1m"));
            button.setTooltip(new javafx.scene.control.Tooltip("The current backend supplies 1m candles."));
            timeframes.getChildren().add(button);
        }
        VBox chartCard = new VBox(14, timeframes, chart);
        chartCard.getStyleClass().add("card");
        chartCard.setPrefHeight(440);
        VBox.setVgrow(chart, Priority.ALWAYS);
        VBox right = new VBox(14, orderBook(t), recentTrades(t), botCard(t));
        right.setMinWidth(0);
        var middle = new javafx.scene.layout.GridPane();
        middle.getStyleClass().add("byx-columns");
        var mainColumn = new javafx.scene.layout.ColumnConstraints();
        mainColumn.setPercentWidth(76);
        var sideColumn = new javafx.scene.layout.ColumnConstraints();
        sideColumn.setPercentWidth(24);
        middle.getColumnConstraints().addAll(mainColumn, sideColumn);
        middle.add(chartCard, 0, 0);
        middle.add(right, 1, 0, 1, 3);
        javafx.scene.layout.GridPane.setVgrow(chartCard, Priority.ALWAYS);
        VBox.setVgrow(middle, Priority.ALWAYS);
        HBox strip = new HBox(0,
                stat("Equity", Fmt.price(t.equity), "muted"), stat("Daily PnL", Fmt.signed(t.dailyPnl, ""), t.dailyPnl == null ? "muted" : (t.dailyPnl >= 0 ? "ok" : "bad")),
                stat("Exposure", Fmt.price(t.exposure), "muted"), stat("Drawdown", Fmt.signed(t.drawdown, "%"), "muted"), stat("Data age", "N/A", "muted"));
        strip.getStyleClass().addAll("th-strip", "desk-metrics");
        middle.add(strip, 0, 1);

        double h = 110;
        TabPane tabs = new TabPane(
                new Tab("Positions (" + t.positions + ")", TTable.of(new String[] {"Symbol", "Side", "Size", "Entry", "Mark", "uPnL"}, t.positionRows, EmptyState.compact("positions", "No active positions", "Research mode does not place orders."), h)),
                new Tab("Orders (" + t.orders + ")", TTable.of(new String[] {"Time", "Symbol", "Type", "Side", "Size", "Price", "Status"}, t.orderRows, EmptyState.compact("orders", "No open orders", "Order execution is disabled."), h)),
                new Tab("Trades", TTable.of(new String[] {"Time", "Symbol", "Side", "Size", "Price", "Fee"}, t.tradeRows, EmptyState.compact("orders", "No trades", "Executed trades will be listed here."), h)),
                new Tab("Signals", TTable.of(new String[] {"Time", "Symbol", "Signal", "Strategy", "Strength"}, t.signalRows, EmptyState.compact("signal", "No signals generated yet", "Signals appear after an approved strategy is connected."), h)),
                new Tab("Bot Activity", TTable.of(new String[] {"Time", "Level", "Message"}, t.activityRows, EmptyState.compact("clock", "No bot activity", "The bot is in research / monitoring mode."), h)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(tab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> tab = b.intValue());
        middle.add(tabs, 0, 2);
        page.getChildren().add(middle);
    }

    private javafx.scene.Node header(TraderSnapshot t) {
        boolean mock = t.source == DataSource.MOCK;
        Node change = Ui.label(Fmt.signed(t.change24hPct, "%"), t.change24hPct == null ? "muted" : (t.change24hPct >= 0 ? "pos" : "neg"));
        HBox badges = new HBox(8, Ui.badge("LIVE TRADING OFF / DISABLED", "muted"));
        if (mock) badges.getChildren().add(Ui.badge("MOCK DATA", "warn"));
        badges.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane h = new javafx.scene.layout.FlowPane(22, 8, Ui.label((t.symbol == null ? "ETHUSDT" : t.symbol) + " · " + (t.market == null ? "Binance USD-M Futures" : t.market), "th-symbol"),
                t.loading ? panel.ui.motion.Skeleton.bar(120, 26) : Ui.label(Fmt.price(t.price), "th-price"), change,
                labeled("24h High", Fmt.price(t.high24h)), labeled("24h Low", Fmt.price(t.low24h)), labeled("24h Volume", Fmt.price(t.volume24h)), badges);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setColumnHalignment(javafx.geometry.HPos.LEFT);
        h.getStyleClass().add("th-header");
        return h;
    }

    private static VBox labeled(String k, String v) {
        return new VBox(1, Ui.label(k, "muted"), Ui.label(v, "kv-value"));
    }

    private static VBox stat(String k, String v, String tone) {
        javafx.scene.control.Label val = Ui.label(v, "metric", "metric-" + tone);
        if (Fmt.NA.equals(v)) {
            val.getStyleClass().add("metric-na");
        }
        VBox b = new VBox(2, Ui.label(k.toUpperCase(), "card-title"), val);
        b.getStyleClass().add("th-stat");
        HBox.setHgrow(b, Priority.ALWAYS);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private Node marketRow(TraderSnapshot t) {
        VBox row = new VBox(2, Ui.label(Fmt.text(t.symbol), "stage-title"), Ui.label(Fmt.text(t.market) + " · " + Fmt.price(t.price), "muted"));
        row.getStyleClass().add("th-market-row");
        row.setOnMouseClicked(e -> ctx.navigate.accept("t-markets"));
        return row;
    }

    private Node chartPlaceholder(TraderSnapshot t) {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.getStyleClass().add("chart-grid");
        for (int i = 0; i < 6; i++) {
            var row = new javafx.scene.layout.RowConstraints(); row.setPercentHeight(100.0 / 6);
            grid.getRowConstraints().add(row);
            Region line = new Region(); line.getStyleClass().add("chart-grid-line");
            grid.add(line, 0, i); javafx.scene.layout.GridPane.setHgrow(line, Priority.ALWAYS);
        }
        StackPane sp = new StackPane();
        grid.setMouseTransparent(true);
        var feed = ctx.icons.icon("feed", 38, "muted");
        VBox center = new VBox(10, feed.node(), Ui.label("Waiting for market data", "empty-title"),
                Ui.label("Candles appear once the Binance USD-M feed connects. No values are simulated.", "muted"),
                Ui.badge("MARKET · NO FEED", "muted"));
        center.setAlignment(Pos.CENTER);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setWrapText(true);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setMaxWidth(360);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        sp.getChildren().addAll(grid, center);
        sp.setMinHeight(220);
        sp.sceneProperty().addListener((o, a, s) -> {
            if (s != null) {
                javafx.application.Platform.runLater(feed::play);
            }
        });
        return sp;
    }

    private static HBox columns(String... names) {
        HBox h = new HBox();
        for (int i = 0; i < names.length; i++) {
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            if (i > 0) {
                h.getChildren().add(sp);
            }
            h.getChildren().add(Ui.label(names[i], "th-th"));
        }
        return h;
    }

    private VBox recentTrades(TraderSnapshot t) {
        VBox c = Ui.card("Recent trades", columns("Price", "Size", "Time"));
        c.getChildren().add(EmptyState.compact("orders", "No trades received", "Waiting for a market feed."));
        return c;
    }

    private VBox orderBook(TraderSnapshot t) {
        VBox book = Ui.card("Order book", columns("Price", "Size", "Total"));
        if (t.asks.isEmpty() && t.bids.isEmpty()) {
            book.getChildren().add(EmptyState.compact("feed", "No order book feed", "Bids and asks appear when a feed is connected."));
            return book;
        }
        VBox asks = new VBox(1), bids = new VBox(1);
        double max = java.util.stream.Stream.concat(t.asks.stream(), t.bids.stream()).mapToDouble(Level::size).max().orElse(1);
        List<Level> a = t.asks.stream().limit(7).sorted((x, y) -> Double.compare(y.price(), x.price())).toList();
        a.forEach(l -> asks.getChildren().add(level(l, max, "neg", "th-bar-ask")));
        t.bids.stream().limit(7).forEach(l -> bids.getChildren().add(level(l, max, "pos", "th-bar-bid")));
        double spread = t.asks.get(0).price() - t.bids.get(0).price();
        book.getChildren().addAll(asks, Ui.label("Spread " + Fmt.price(spread), "muted"), bids);
        return book;
    }

    private static Node level(Level l, double max, String tone, String barClass) {
        Region bar = new Region();
        bar.getStyleClass().add(barClass);
        HBox row = new HBox(Ui.label(Fmt.price(l.price()), tone), Ui.spacer(), Ui.label(String.format("%.3f", l.size()), "kv-value"));
        StackPane sp = new StackPane(bar, row);
        StackPane.setAlignment(bar, Pos.CENTER_RIGHT);
        bar.setMaxWidth(0);
        sp.widthProperty().addListener((o, a, w) -> bar.setMaxWidth(w.doubleValue() * l.size() / max));
        bar.setMaxHeight(14);
        return sp;
    }

    private VBox botCard(TraderSnapshot t) {
        panel.ui.motion.BotAvatar avatar = new panel.ui.motion.BotAvatar(ctx.motion, 44, panel.ui.motion.BotAvatar.State.parse(t.botState));
        VBox name = new VBox(2, Ui.label("Adaptive Trader", "stage-title"), Ui.badge(t.botState, "warn"));
        HBox head = new HBox(12, avatar, name);
        head.setAlignment(Pos.CENTER_LEFT);
        return Ui.card("Bot", head,
                Ui.kv("Backend", t.backendOnline ? "ONLINE" : "OFFLINE"), Ui.kv("Mode", t.mode), Ui.kv("Trading", t.trading), Ui.kv("Account", t.account),
                Ui.kv("Strategy", t.strategy), Ui.kv("Signal", t.signal));
    }
}
