package panel.ui.trader;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.TraderSnapshot;
import panel.ui.Charts;
import panel.ui.Ui;
import panel.util.Fmt;

/** Telas secundárias do Trader Workspace. Todas somente leitura. */
public final class TraderScreens {
    private TraderScreens() {
    }

    private static VBox sized(VBox c, double w) {
        c.setPrefWidth(w);
        return c;
    }

    private static Node mockNote(TraderSnapshot t) {
        return t.source == panel.model.DataSource.MOCK ? Ui.badge("MOCK DATA — fictional values", "warn") : Ui.label("", "muted");
    }

    public static class Bot extends TraderPage {
        public Bot(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Bot", "Automated trading engine", Ui.badge(t.botState, "warn"), mockNote(t)));
            FlowPane f = new FlowPane(14, 14);
            f.getChildren().addAll(
                    sized(Ui.card("State", botHead(t), Ui.kv("Mode", t.mode), Ui.kvNode("Trading", Ui.badge(t.trading, "bad")), Ui.kv("Account", t.account)), 340),
                    sized(Ui.card("Strategy", Ui.kv("Active", t.strategy), Ui.kv("Status", t.strategyStatus), Ui.kv("Current signal", t.signal)), 340),
                    sized(Ui.card("Controls", wrapped("START / STOP are disabled: order execution does not exist in this phase."),
                            disabled("START BOT"), disabled("STOP BOT")), 340));
            page.getChildren().add(f);
            FlowPane f2 = new FlowPane(14, 14);
            f2.getChildren().addAll(sized(Ui.card("Recent activity", panel.ui.EmptyState.of("clock", "No bot activity", "The bot is monitoring in research mode and has not acted.")), 520),
                    sized(Ui.card("Operating guard", Ui.kvNode("Order execution", Ui.badge("NOT IMPLEMENTED", "bad")), Ui.kvNode("Live trading", Ui.badge("BLOCKED", "bad")),
                            Ui.kvNode("Exchange", Ui.badge("NOT CONNECTED", "muted")), Ui.kvNode("Strategy", Ui.badge("NONE APPROVED", "muted"))), 520));
            page.getChildren().add(f2);
        }

        private static javafx.scene.control.Label wrapped(String s) {
            var l = Ui.label(s, "muted");
            l.setWrapText(true);
            l.setMaxWidth(290);
            return l;
        }

        private Node botHead(TraderSnapshot t) {
            var a = new panel.ui.motion.BotAvatar(ctx.motion, 52, panel.ui.motion.BotAvatar.State.parse(t.botState));
            var h = new HBox(12, a, new VBox(2, Ui.label("Adaptive Trader", "stage-title"), Ui.badge(t.botState, "warn")));
            h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return h;
        }

        private static Node disabled(String text) {
            var b = Ui.button(text, "ghost");
            b.setDisable(true);
            return b;
        }
    }

    public static class Strategies extends TraderPage {
        public Strategies(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Strategies", "Strategy approval comes from the Research workspace", mockNote(t)));
            page.getChildren().add(TTable.of(new String[] {"Strategy", "Version", "Status", "Approval"}, t.strategyRows, "No strategies", 160));
        }
    }

    public static class Signals extends TraderPage {
        public Signals(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Signals", "Signals emitted by the active strategy", Ui.badge("STRATEGY · " + (t.strategy == null ? "NONE" : t.strategy.toUpperCase()), "muted"), mockNote(t)));
            HBox filters = filters("All", "Buy", "Sell", "Neutral");
            page.getChildren().add(filters);
            VBox empty = panel.ui.EmptyState.of("signal", "No signals generated yet", "Signals will appear after an approved strategy is connected.",
                    Ui.badge("STRATEGY · NONE APPROVED", "muted"), Ui.badge("BOT · RESEARCH", "warn"));
            page.getChildren().add(TTable.of(new String[] {"Time", "Symbol", "Signal", "Strategy", "Strength"}, t.signalRows, empty, 380));
            page.getChildren().add(Ui.card("Current", Ui.kv("Signal", t.signal), Ui.kv("Strategy", t.strategy), Ui.kv("Status", t.strategyStatus)));
        }
    }

    static HBox filters(String... names) {
        HBox h = new HBox(6);
        for (String n : names) {
            javafx.scene.control.ToggleButton b = new javafx.scene.control.ToggleButton(n.toUpperCase());
            b.getStyleClass().add("chip");
            b.setSelected(n.equals(names[0]));
            b.setDisable(!n.equals(names[0]));
            h.getChildren().add(b);
        }
        h.getChildren().add(Ui.label("Filters become available with live data.", "muted"));
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    public static class Portfolio extends TraderPage {
        public Portfolio(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Portfolio", "Account overview", mockNote(t)));
            FlowPane f = new FlowPane(14, 14);
            f.getChildren().addAll(
                    sized(Ui.card("Account", Ui.metric("Equity", Fmt.price(t.equity), "muted"), Ui.kv("Account", t.account), Ui.kv("Balance", Fmt.price(t.balance))), 300),
                    sized(Ui.card("Today", Ui.metric("Daily PnL", Fmt.signed(t.dailyPnl, ""), "muted"), Ui.kv("Drawdown", Fmt.signed(t.drawdown, "%"))), 300),
                    sized(Ui.card("Exposure", Ui.metric("Open positions", String.valueOf(t.positions), "muted"), Ui.kv("Open orders", String.valueOf(t.orders)), Ui.kv("Exposure", Fmt.price(t.exposure))), 300));
            page.getChildren().add(f);
            page.getChildren().add(Ui.card("Equity curve", equity(t)));
        }
    }

    static Node equity(TraderSnapshot t) {
        if (t.equityCurve.isEmpty()) {
            return Ui.emptyState("No equity history");
        }
        List<String> x = new ArrayList<>();
        for (int i = 0; i < t.equityCurve.size(); i++) {
            x.add(String.valueOf(i));
        }
        return Charts.line("equity", x, t.equityCurve);
    }

    public static class Positions extends TraderPage {
        public Positions(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Positions", t.positions + " open", Ui.badge("RESEARCH MODE", "warn"), mockNote(t)));
            page.getChildren().add(filters("All", "Long", "Short"));
            page.getChildren().add(TTable.of(new String[] {"Symbol", "Side", "Size", "Entry", "Mark", "uPnL"}, t.positionRows,
                    panel.ui.EmptyState.of("positions", "No active positions", "Research mode does not place orders.", Ui.badge("TRADING DISABLED", "bad")), 380));
        }
    }

    public static class Orders extends TraderPage {
        public Orders(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Orders", t.orders + " open", Ui.badge("RESEARCH MODE", "warn"), mockNote(t)));
            page.getChildren().add(filters("Open", "Filled", "Cancelled"));
            page.getChildren().add(TTable.of(new String[] {"Time", "Symbol", "Type", "Side", "Size", "Price", "Status"}, t.orderRows,
                    panel.ui.EmptyState.of("orders", "No open orders", "Order execution is disabled.", Ui.badge("TRADING DISABLED", "bad")), 380));
        }
    }

    public static class Performance extends TraderPage {
        public Performance(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Performance", "Results of the trading bot", mockNote(t)));
            VBox stats = Ui.card("Statistics");
            for (String k : new String[] {"Total return", "Sharpe", "Win rate", "Trades", "Max drawdown"}) {
                stats.getChildren().add(Ui.kv(k, t.performance.get(k)));
            }
            page.getChildren().addAll(stats, Ui.card("Equity curve", equity(t)));
        }
    }

    public static class Activity extends TraderPage {
        public Activity(AppContext c) {
            super(c);
        }

        @Override
        protected void build(TraderSnapshot t, VBox page) {
            page.getChildren().add(Ui.pageHeader("Activity", "What the bot did", mockNote(t)));
            page.getChildren().add(TTable.of(new String[] {"Time", "Level", "Message"}, t.activityRows, panel.ui.EmptyState.of("clock", "No bot activity", "The bot is in research / monitoring mode and has not acted."), 380));
        }
    }
}
