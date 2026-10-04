package panel.ui.trader;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Level;
import panel.motion.MotionTokens;
import panel.ui.LedgerMark;
import panel.ui.Ui;
import panel.util.Fmt;

public class TradingDeskView extends TraderPage {
    private final Label symbol = Ui.label("ETHUSDT", "desk-symbol");
    private final Label venue = Ui.label("BINANCE USD-M FUTURES", "card-title");
    private final Label price = Ui.label("—", "metric", "muted");
    private final Label feed = Ui.badge("Waiting for market data", "muted");
    private final List<Label> values = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();
    private final List<TableView<String[]>> tables = new ArrayList<>();
    private final StackPane rows = new StackPane();
    private final StackPane chart = new StackPane();
    private final VBox bookRows = new VBox(14);
    private final VBox recentRows = new VBox(14);
    private final TableView<String[]> recentTable = TTable.of(new String[] {"Time", "Symbol", "Side", "Size", "Price", "Fee"}, List.of(), "No trades", 160);
    private final Label botState = Ui.label("MONITORING", "badge", "badge-warn");
    private final Region botDot = new Region();
    private final List<Label> botValues = new ArrayList<>();
    private final String[] emptyText = {"No open positions. Live trading is off, so none will open.",
            "No open orders. Order execution is disabled.", "No trades received.", "No signals generated yet."};
    private boolean built;
    private int tab;
    private String bookKey;
    private String recentKey;
    private List<TraderSnapshot.Candle> candles;

    public TradingDeskView(AppContext ctx) {
        super(ctx);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    }

    @Override public void onSnapshot(Snapshot s) {
        TraderSnapshot t = ctx.trading.snapshot.get();
        if (!built) { build(t, body); built = true; }
        update(t);
    }

    @Override protected void build(TraderSnapshot t, VBox page) {
        HBox periods = new HBox(2); periods.setAlignment(Pos.CENTER_LEFT);
        Region feedDot = new Region(); feedDot.getStyleClass().add("market-feed-dot");
        feed.setGraphic(feedDot); feed.setGraphicTextGap(13);
        for (String interval : new String[] {"1m", "5m", "15m", "1h", "4h"}) {
            Button button = new Button(interval);
            button.getStyleClass().add("desk-period");
            if (interval.equals("1m")) button.getStyleClass().add("selected");
            else button.setDisable(true);
            button.setTooltip(new Tooltip("The current backend supplies 1m candles."));
            periods.getChildren().add(button);
        }
        HBox head = new HBox(14, symbol, Ui.badge("PERPETUAL", "muted"), venue, Ui.spacer(), price, feed, periods);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().addAll("card", "desk-header");
        head.setId("desk-header");
        venue.setTooltip(new Tooltip("Market details, 24h statistics and account status: Markets / Portfolio."));
        chart.getStyleClass().addAll("card", "desk-chart");
        chart.setId("desk-chart");
        chart.setMinSize(0, 0);

        HBox metrics = new HBox(40);
        for (String title : new String[] {"Equity", "Daily PnL", "Exposure", "Drawdown", "Data age"}) {
            Label value = Ui.label("N/A", "desk-value", "mono"); values.add(value);
            metrics.getChildren().add(new VBox(2, Ui.label(title.toUpperCase(), "card-title"), value));
        }
        HBox tabBar = new HBox(20);
        tabBar.getStyleClass().add("desk-tabs");
        String[][] headers = {{"Symbol", "Side", "Size", "Entry", "Mark", "uPnL"},
                {"Time", "Symbol", "Type", "Side", "Size", "Price", "Status"},
                {"Time", "Symbol", "Side", "Size", "Price", "Fee"}, {"Time", "Symbol", "Signal", "Strategy", "Strength"}};
        for (int i = 0; i < 4; i++) {
            int index = i;
            Button button = new Button(new String[] {"Positions 0", "Orders 0", "Trades", "Signals"}[i]);
            button.getStyleClass().add("desk-tab");
            button.setOnAction(e -> { tab = index; update(ctx.trading.snapshot.get()); });
            tabs.add(button); tabBar.getChildren().add(button);
            TableView<String[]> table = TTable.of(headers[i], List.of(), emptyText[i], 0);
            table.setMaxHeight(Double.MAX_VALUE); tables.add(table);
        }
        MenuButton more = new MenuButton("⋯"); more.getStyleClass().add("desk-tab");
        MenuItem activity = new MenuItem("Bot Activity"); activity.setOnAction(e -> ctx.navigate.accept("t-activity"));
        MenuItem details = new MenuItem("Market / account details"); details.setOnAction(e -> ctx.navigate.accept("t-markets"));
        more.getItems().addAll(activity, details); more.setAccessibleText("More: Bot Activity and market details");
        tabBar.getChildren().addAll(Ui.spacer(), more);
        VBox bottom = new VBox(12, metrics, tabBar, rows);
        bottom.getStyleClass().addAll("card", "desk-bottom"); bottom.setId("desk-bottom");
        VBox.setVgrow(rows, Priority.ALWAYS);

        VBox book = Ui.card("Order book", bookRows); book.setId("desk-order-book");
        VBox recent = Ui.card("Recent trades", recentRows); recent.setId("desk-recent-trades");
        book.setSpacing(14); recent.setSpacing(14);
        book.setMinHeight(0); recent.setMinHeight(0); book.setPrefHeight(0); recent.setPrefHeight(0);
        VBox.setVgrow(book, Priority.ALWAYS); VBox.setVgrow(recent, Priority.ALWAYS);
        VBox right = new VBox(14, book, recent, botCard()); right.setId("desk-right");
        GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(14);
        ColumnConstraints left = new ColumnConstraints(); left.setHgrow(Priority.ALWAYS); left.setMinWidth(0);
        grid.getColumnConstraints().addAll(left, new ColumnConstraints(300));
        RowConstraints middle = new RowConstraints(); middle.setVgrow(Priority.ALWAYS); middle.setMinHeight(0);
        grid.getRowConstraints().addAll(new RowConstraints(60), middle, new RowConstraints(190));
        grid.add(head, 0, 0); grid.add(chart, 0, 1); grid.add(bottom, 0, 2); grid.add(right, 1, 0, 1, 3);
        VBox.setVgrow(grid, Priority.ALWAYS); page.getChildren().add(grid);
    }

    private void update(TraderSnapshot t) {
        symbol.setText(t.symbol == null ? "ETHUSDT" : t.symbol);
        venue.setText(t.market == null ? "BINANCE USD-M FUTURES" : t.market.toUpperCase(java.util.Locale.ROOT));
        price.setText(t.price == null ? "—" : Fmt.price(t.price));
        String state = waiting(t) ? "Waiting for market data" : "NOT_CONFIGURED".equals(t.feed) ? "Market feed not configured" : Fmt.text(t.feed);
        feed.setText((t.source == panel.model.DataSource.MOCK ? "MOCK · " : "") + state);
        feed.setTooltip(new Tooltip("Market feed: " + Fmt.text(t.feed) + " · Backend: " + (t.backendOnline ? "Online" : "Offline")));
        if (!java.util.Objects.equals(candles, t.candles) || chart.getChildren().isEmpty()
                || !java.util.Objects.equals(chart.getProperties().get("feed-state"), state)) {
            candles = List.copyOf(t.candles); chart.getProperties().put("feed-state", state);
            chart.getChildren().setAll(t.candles.isEmpty() ? chartPlaceholder(state, waiting(t)) : new CandleChart(t.candles));
        }
        values.get(0).setText(Fmt.price(t.equity)); values.get(1).setText(Fmt.signed(t.dailyPnl, ""));
        values.get(2).setText(Fmt.price(t.exposure)); values.get(3).setText(Fmt.signed(t.drawdown, "%")); values.get(4).setText("—");
        tabs.get(0).setText("Positions " + t.positions); tabs.get(1).setText("Orders " + t.orders);
        List<List<String[]>> data = List.of(t.positionRows, t.orderRows, t.tradeRows, t.signalRows);
        for (int i = 0; i < 4; i++) {
            TTable.update(tables.get(i), data.get(i)); tabs.get(i).getStyleClass().remove("selected");
        }
        tabs.get(tab).getStyleClass().add("selected");
        Node content = data.get(tab).isEmpty() ? rows.getProperties().get("empty-" + tab) instanceof Node n ? n : empty(tab) : tables.get(tab);
        if (rows.getChildren().isEmpty() || rows.getChildren().getFirst() != content) rows.getChildren().setAll(content);
        String nextBook = state + t.asks + t.bids;
        if (!nextBook.equals(bookKey)) {
            bookKey = nextBook; bookRows.getChildren().clear();
            if (t.asks.isEmpty() && t.bids.isEmpty()) emptyMarket(bookRows, 9, state, waiting(t));
            else {
                double max = java.util.stream.Stream.concat(t.asks.stream(), t.bids.stream()).mapToDouble(Level::size).max().orElse(1);
                t.asks.stream().limit(7).forEach(l -> bookRows.getChildren().add(level(l, max, "neg", "th-bar-ask")));
                if (!t.asks.isEmpty() && !t.bids.isEmpty()) bookRows.getChildren().add(Ui.label("Spread " + Fmt.price(t.asks.getFirst().price() - t.bids.getFirst().price()), "muted"));
                t.bids.stream().limit(7).forEach(l -> bookRows.getChildren().add(level(l, max, "pos", "th-bar-bid")));
            }
        }
        String nextRecent = state + java.util.Arrays.deepToString(t.tradeRows.toArray());
        if (!nextRecent.equals(recentKey)) {
            recentKey = nextRecent;
            if (t.tradeRows.isEmpty()) { recentRows.getChildren().clear(); emptyMarket(recentRows, 6, state, waiting(t)); }
            else {
                TTable.update(recentTable, t.tradeRows);
                if (recentRows.getChildren().isEmpty() || recentRows.getChildren().getFirst() != recentTable) recentRows.getChildren().setAll(recentTable);
            }
        }
        boolean monitoring = t.botState != null && t.botState.contains("MONITORING");
        botState.setText(monitoring ? "MONITORING" : Fmt.text(t.botState));
        ctx.motion.reference.setBreathing(botDot, monitoring, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        botValues.get(0).setText("RESEARCH".equals(t.mode) ? "Research" : Fmt.text(t.mode));
        botValues.get(1).setText("DISABLED".equals(t.trading) ? "Off" : Fmt.text(t.trading));
        botValues.get(2).setText(Fmt.text(t.strategy)); botValues.get(3).setText(Fmt.text(t.signal));
    }

    private Node empty(int index) {
        Label label = Ui.label(emptyText[index], "muted");
        label.setMaxWidth(Double.MAX_VALUE); label.setAlignment(Pos.TOP_LEFT); label.getStyleClass().add("desk-empty");
        rows.getProperties().put("empty-" + index, label); return label;
    }

    private static boolean waiting(TraderSnapshot t) {
        if (t.feed != null && java.util.Set.of("ERROR", "OFFLINE", "STALE", "UNAVAILABLE", "NOT_CONFIGURED", "NOT CONFIGURED").contains(t.feed)) return false;
        return t.loading || t.feed == null || java.util.Set.of("WAITING", "CONNECTING", "AWAITING").contains(t.feed);
    }

    private void emptyMarket(VBox target, int count, String state, boolean waiting) {
        if (!waiting) { target.getChildren().add(Ui.label(state + " · No market data available", "muted")); return; }
        double[] widths = {.90, .70, .80, .55, .85, .90, .70, .80, .55};
        for (int i = 0; i < count; i++) {
            Region bar = new Region(); bar.getStyleClass().add("skeleton");
            bar.setMinHeight(10); bar.setPrefHeight(10); bar.setMaxHeight(10);
            bar.prefWidthProperty().bind(target.widthProperty().multiply(widths[i]));
            bar.maxWidthProperty().bind(bar.prefWidthProperty()); target.getChildren().add(bar);
        }
    }

    private Node chartPlaceholder(String state, boolean waiting) {
        VBox mark = LedgerMark.create(44); mark.setId("desk-waiting-mark");
        if (waiting) ctx.motion.reference.breathe(mark, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        Label message = Ui.label(waiting ? "Candles appear once the Binance USD-M\nfeed connects. No values are simulated."
                : "No market data available. Check feed status in Markets.", "muted");
        message.setMaxWidth(300); message.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox center = new VBox(10, mark, Ui.label(state, "desk-empty-title"), message); center.setAlignment(Pos.CENTER);
        javafx.scene.canvas.Canvas lines = new javafx.scene.canvas.Canvas(); lines.setMouseTransparent(true);
        StackPane placeholder = new StackPane(lines, center);
        Runnable draw = () -> {
            lines.setWidth(placeholder.getWidth()); lines.setHeight(placeholder.getHeight());
            var g = lines.getGraphicsContext2D(); g.clearRect(0, 0, lines.getWidth(), lines.getHeight());
            g.setStroke(javafx.scene.paint.Color.web("#ffffff07"));
            for (double y = lines.getHeight() - 78.5; y > 0; y -= 79) g.strokeLine(0, y, lines.getWidth(), y);
        };
        placeholder.widthProperty().addListener((o,a,b) -> draw.run()); placeholder.heightProperty().addListener((o,a,b) -> draw.run());
        return placeholder;
    }

    private static Node level(Level l, double max, String tone, String barClass) {
        Region bar = new Region(); bar.getStyleClass().add(barClass);
        HBox row = new HBox(Ui.label(Fmt.price(l.price()), tone), Ui.spacer(), Ui.label(String.format("%.3f", l.size()), "kv-value"));
        StackPane sp = new StackPane(bar, row); StackPane.setAlignment(bar, Pos.CENTER_RIGHT);
        bar.maxWidthProperty().bind(sp.widthProperty().multiply(max <= 0 ? 0 : l.size() / max)); bar.setMaxHeight(14); return sp;
    }

    private VBox botCard() {
        botDot.getStyleClass().add("bot-monitor-dot"); botDot.setId("desk-bot-dot");
        botState.setGraphic(botDot); botState.setGraphicTextGap(6);
        HBox head = new HBox(new VBox(0, Ui.label("BOT", "card-title"), Ui.label("Adaptive Trader", "desk-empty-title")), Ui.spacer(), botState);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(head); card.getStyleClass().add("card"); card.setId("desk-bot");
        for (String key : new String[] {"Mode", "Live trading", "Strategy", "Signal"}) {
            Label value = Ui.label("N/A", "mono"); botValues.add(value);
            HBox row = new HBox(12, Ui.label(key, "muted"), Ui.spacer(), value); row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("reference-row"); card.getChildren().add(row);
        }
        return card;
    }
}
