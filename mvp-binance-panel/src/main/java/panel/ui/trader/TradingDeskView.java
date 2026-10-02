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

        VBox markets = Ui.card("Markets", marketRow(t));
        markets.setPrefWidth(150);
        markets.setMinWidth(150);
        markets.setMaxWidth(150);
        Node chart = t.candles.isEmpty() ? chartPlaceholder(t) : new CandleChart(t.candles);
        VBox chartCard = Ui.card(Fmt.text(t.symbol) + " " + Fmt.text(t.market) + " · 1m", chart);
        chartCard.setPrefHeight(400);
        chartCard.setMinHeight(400);
        if (chart instanceof Region r) {
            VBox.setVgrow(r, Priority.ALWAYS);
        }
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        VBox right = new VBox(14, orderBook(t), recentTrades(t), botCard(t));
        right.setPrefWidth(280);
        right.setMinWidth(280);
        right.setMaxWidth(280);
        HBox mid = new HBox(14, markets, chartCard, right);
        page.getChildren().add(mid);

        HBox strip = new HBox(0,
                stat("Equity", Fmt.price(t.equity), "muted"), stat("Daily PnL", Fmt.signed(t.dailyPnl, ""), t.dailyPnl == null ? "muted" : (t.dailyPnl >= 0 ? "ok" : "bad")),
                stat("Position", String.valueOf(t.positions), "muted"), stat("Exposure", Fmt.price(t.exposure), "muted"), stat("Drawdown", Fmt.signed(t.drawdown, "%"), "muted"));
        strip.getStyleClass().add("th-strip");
        page.getChildren().add(strip);

        double h = 210;
        TabPane tabs = new TabPane(
                new Tab("Positions (" + t.positions + ")", TTable.of(new String[] {"Symbol", "Side", "Size", "Entry", "Mark", "uPnL"}, t.positionRows, EmptyState.compact("positions", "No active positions", "Research mode does not place orders."), h)),
                new Tab("Orders (" + t.orders + ")", TTable.of(new String[] {"Time", "Symbol", "Type", "Side", "Size", "Price", "Status"}, t.orderRows, EmptyState.compact("orders", "No open orders", "Order execution is disabled."), h)),
                new Tab("Trades", TTable.of(new String[] {"Time", "Symbol", "Side", "Size", "Price", "Fee"}, t.tradeRows, EmptyState.compact("orders", "No trades", "Executed trades will be listed here."), h)),
                new Tab("Signals", TTable.of(new String[] {"Time", "Symbol", "Signal", "Strategy", "Strength"}, t.signalRows, EmptyState.compact("signal", "No signals generated yet", "Signals appear after an approved strategy is connected."), h)),
                new Tab("Bot Activity", TTable.of(new String[] {"Time", "Level", "Message"}, t.activityRows, EmptyState.compact("clock", "No bot activity", "The bot is in research / monitoring mode."), h)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(tab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> tab = b.intValue());
        page.getChildren().add(tabs);
    }

    private javafx.scene.Node header(TraderSnapshot t) {
        boolean mock = t.source == DataSource.MOCK;
        Node change = Ui.label(Fmt.signed(t.change24hPct, "%"), t.change24hPct == null ? "muted" : (t.change24hPct >= 0 ? "pos" : "neg"));
        HBox badges = new HBox(8, Ui.badge("MARKET ● " + (t.feed == null ? "NO FEED" : t.feed), t.feed == null ? "muted" : "warn"),
                Ui.badge("BACKEND " + (t.backendOnline ? "ONLINE" : "OFFLINE"), t.backendOnline ? "ok" : "bad"),
                Ui.badge("RECORDER " + Fmt.text(t.recorder), "muted"),
                Ui.badge(t.source + " · " + t.mode, "info"), Ui.badge("BOT: " + t.botState, "warn"), Ui.badge("TRADING " + t.trading, "bad"));
        if (mock) {
            badges.getChildren().add(Ui.badge("MOCK DATA", "warn"));
        }
        badges.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane h = new javafx.scene.layout.FlowPane(22, 8, Ui.label(Fmt.text(t.symbol) + " " + Fmt.text(t.market), "th-symbol"),
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
        javafx.scene.canvas.Canvas grid = new javafx.scene.canvas.Canvas();
        StackPane sp = new StackPane() {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                grid.setWidth(getWidth());
                grid.setHeight(getHeight());
                var g = grid.getGraphicsContext2D();
                g.clearRect(0, 0, getWidth(), getHeight());
                g.setStroke(javafx.scene.paint.Color.web("#2B3139", 0.55));
                for (int i = 1; i < 6; i++) {
                    g.strokeLine(0, getHeight() * i / 6, getWidth(), getHeight() * i / 6);
                }
                for (int i = 1; i < 10; i++) {
                    g.strokeLine(getWidth() * i / 10, 0, getWidth() * i / 10, getHeight());
                }
            }
        };
        grid.setManaged(false);
        grid.setMouseTransparent(true);
        var feed = ctx.icons.icon("feed", 38, "muted");
        VBox center = new VBox(10, feed.node(), Ui.label("MARKET DATA NOT CONNECTED", "chart-watermark"),
                Ui.label("Prices, candles and the order book will appear here once a market feed is connected.", "muted"),
                Ui.badge("MARKET · NO FEED", "muted"));
        center.setAlignment(Pos.CENTER);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setWrapText(true);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setMaxWidth(360);
        ((javafx.scene.control.Label) center.getChildren().get(2)).setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        sp.getChildren().addAll(grid, center);
        sp.setMinHeight(300);
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
