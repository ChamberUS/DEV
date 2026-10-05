package panel.tradeview;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxFonts;
import panel.design.ByxIcon;
import panel.model.DataSource;
import panel.model.TraderSnapshot;
import panel.ui.trader.TTable;

/**
 * Blotter V2: Positions, Orders, Trades, Signals, Activity. As tabelas são as {@link TTable} existentes
 * (modelo String[] por linha, atualização incremental): trocar de aba não recria nada e um poll sem mudança não
 * toca nenhuma linha. Cada aba está em LOADING, EMPTY, READY, ERROR ou UNAVAILABLE; os cabeçalhos ficam
 * visíveis e a mensagem aparece embaixo (referência). Positions mostra 7/8/10 colunas conforme o breakpoint.
 */
final class BlotterPanel extends VBox {
    enum Tab {
        POSITIONS("Positions", new String[] {"Symbol", "Side", "Size", "Entry", "Mark", "uPnL", "Margin", "Leverage", "Liq. price", "Opened"},
                new double[] {1.3, 1, 1, 1, 1, 1, 1, 1, 1, 1}, "positions"),
        ORDERS("Orders", new String[] {"Time", "Symbol", "Type", "Side", "Size", "Price", "Status"},
                new double[] {1, 1.2, 1, 1, 1, 1, 1}, "orders"),
        TRADES("Trades", new String[] {"Time", "Symbol", "Side", "Size", "Price", "Fee"},
                new double[] {1, 1.2, 1, 1, 1, 1}, "chart"),
        SIGNALS("Signals", new String[] {"Time", "Symbol", "Signal", "Strategy", "Strength"},
                new double[] {1, 1.2, 1, 2, 1}, "signal"),
        ACTIVITY("Activity", new String[] {"Time", "Level", "Message"}, new double[] {1, 1, 6}, "clock");

        final String label;
        final String[] headers;
        final double[] weights;
        final String icon;

        Tab(String label, String[] headers, double[] weights, String icon) {
            this.label = label;
            this.headers = headers;
            this.weights = weights;
            this.icon = icon;
        }
    }

    enum TabState { LOADING, EMPTY, READY, ERROR, UNAVAILABLE }

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Tab, ToggleButton> buttons = new EnumMap<>(Tab.class);
    private final Map<Tab, TableView<String[]>> tables = new EnumMap<>(Tab.class);
    private final Map<Tab, Page> pages = new EnumMap<>(Tab.class);
    private final Map<Tab, TabState> states = new EnumMap<>(Tab.class);
    private final Label guard = Fx.label("Live trading is disabled", "byx-desk-t3");
    private DeskMode mode = DeskMode.COMPACT;
    private Tab selected = Tab.POSITIONS;
    private int lastKey = Integer.MIN_VALUE;

    BlotterPanel() {
        getStyleClass().addAll("byx-panel", "byx-desk-blotter");
        setId("desk-blotter");
        setPadding(new Insets(0, 16, 8, 16));
        HBox bar = new HBox(24);
        bar.getStyleClass().add("byx-desk-tabs");
        bar.setAlignment(Pos.CENTER_LEFT);
        StackPane body = new StackPane();
        body.setMinHeight(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        for (Tab tab : Tab.values()) {
            ToggleButton b = new ToggleButton(tab.label);
            b.getStyleClass().add("byx-desk-tab");
            b.setToggleGroup(group);
            b.setUserData(tab);
            buttons.put(tab, b);
            bar.getChildren().add(b);
            TableView<String[]> table = TTable.of(upper(tab.headers), List.of(), new Label(), 0);
            style(table, tab);
            tables.put(tab, table);
            Page page = new Page(table, tab);
            pages.put(tab, page);
            states.put(tab, TabState.EMPTY);
            body.getChildren().add(page);
        }
        bar.getChildren().addAll(Fx.spacer(), guard);
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                if (was != null) {
                    was.setSelected(true);
                }
                return;
            }
            selected = (Tab) now.getUserData();
            showSelected();
        });
        buttons.get(Tab.POSITIONS).setSelected(true);
        getChildren().addAll(bar, body);
        setMinSize(0, 0);
        setMode(DeskMode.COMPACT);
        showSelected();
    }

    private static String[] upper(String[] headers) {
        String[] out = new String[headers.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = headers[i].toUpperCase(java.util.Locale.ROOT);
        }
        return out;
    }

    private static void style(TableView<String[]> table, Tab tab) {
        table.getStyleClass().add("byx-desk-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setFixedCellSize(42);
        table.setMinHeight(0);
        table.setPrefHeight(0);
        table.setMaxHeight(Double.MAX_VALUE);
        table.setFocusTraversable(true);
        table.setAccessibleText(tab.label + " table");
        for (int i = 0; i < table.getColumns().size(); i++) {
            TableColumn<String[], String> c = (TableColumn<String[], String>) table.getColumns().get(i);
            c.setSortable(false);
            c.setReorderable(false);
            c.setResizable(false);
            c.setPrefWidth(tab.weights[i] * 100);
            String h = tab.headers[i];
            if (h.equals("Side") || h.equals("uPnL") || h.equals("Signal")) {
                c.setCellFactory(col -> new ToneCell());
            }
        }
    }

    /** BUY/LONG verde, SELL/SHORT vermelho, uPnL pelo sinal; o texto continua presente (a cor nunca é o único sinal). */
    private static final class ToneCell extends TableCell<String[], String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
            String v = empty || item == null ? "" : item.trim().toUpperCase(java.util.Locale.ROOT);
            boolean pos = v.equals("BUY") || v.equals("LONG") || v.startsWith("+") && !v.matches("\\+0(\\.0+)?.*");
            boolean neg = v.equals("SELL") || v.equals("SHORT") || v.startsWith("-") && !v.matches("-0(\\.0+)?.*");
            Fx.cls(this, "tone-pos", pos);
            Fx.cls(this, "tone-neg", neg);
        }
    }

    /** Tabela + camada de estado (mensagem sob o cabeçalho, como na referência). */
    private static final class Page extends StackPane {
        final TableView<String[]> table;
        final VBox layer = new VBox(8);
        final Label title = Fx.label("", "byx-section-title-sm");
        final Label text = Fx.label("", "byx-desk-secondary", "byx-desk-body");
        final StackPane icon = new StackPane();
        final VBox skeleton = new VBox(14);
        final Tab tab;

        Page(TableView<String[]> table, Tab tab) {
            this.table = table;
            this.tab = tab;
            icon.getStyleClass().add("byx-desk-empty-icon");
            icon.getChildren().add(ByxIcon.of(tab.icon, 20, "t3"));
            icon.setMinSize(46, 46);
            icon.setMaxSize(46, 46);
            text.setWrapText(true);
            text.setMaxWidth(440);
            text.setAlignment(Pos.CENTER);
            text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            layer.setAlignment(Pos.CENTER);
            layer.getChildren().addAll(icon, title, text);
            layer.setPadding(new Insets(40, 0, 0, 0)); // abaixo da linha de cabeçalho
            layer.setPickOnBounds(false);
            double[] inset = {120, 320, 220};
            for (int i = 0; i < 3; i++) {
                var bar = Fx.skeleton();
                VBox.setMargin(bar, new Insets(0, inset[i], 0, 0));
                skeleton.getChildren().add(bar);
            }
            skeleton.setPadding(new Insets(56, 24, 0, 24));
            skeleton.setPickOnBounds(false);
            getChildren().addAll(table, layer, skeleton);
        }
    }

    // ------------------------------------------------------------------ API

    DeskMode mode() {
        return mode;
    }

    Tab selected() {
        return selected;
    }

    void select(Tab tab) {
        buttons.get(tab).setSelected(true);
    }

    TableView<String[]> table(Tab tab) {
        return tables.get(tab);
    }

    ToggleButton button(Tab tab) {
        return buttons.get(tab);
    }

    TabState state(Tab tab) {
        return states.get(tab);
    }

    Label messageTitle(Tab tab) {
        return pages.get(tab).title;
    }

    Label messageText(Tab tab) {
        return pages.get(tab).text;
    }

    int visiblePositionColumns() {
        return (int) tables.get(Tab.POSITIONS).getColumns().stream().filter(c -> c.isVisible()).count();
    }

    /** Altura e colunas do breakpoint (a altura é fixa: 214, 240, 300). */
    void setMode(DeskMode mode) {
        this.mode = mode;
        setMinHeight(mode.blotterHeight);
        setPrefHeight(mode.blotterHeight);
        setMaxHeight(mode.blotterHeight);
        var columns = tables.get(Tab.POSITIONS).getColumns();
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).setVisible(i < mode.blotterColumns);
        }
    }

    /** Estado de uma aba a partir de dados reais (ver auditoria §3). Positions/Orders/Trades dependem da conta, que não existe. */
    static TabState stateOf(Tab tab, TraderSnapshot t, List<String[]> rows) {
        if (!rows.isEmpty()) {
            return TabState.READY;
        }
        if (t.loading) {
            return TabState.LOADING;
        }
        boolean backendDerived = tab == Tab.SIGNALS || tab == Tab.ACTIVITY;
        if (backendDerived && t.source == DataSource.REAL && !t.backendOnline) {
            return TabState.UNAVAILABLE;
        }
        return TabState.EMPTY;
    }

    private static String[] message(Tab tab, TabState state) {
        return switch (state) {
            case ERROR -> new String[] {"Could not load " + tab.label.toLowerCase(java.util.Locale.ROOT), "The data source reported an error."};
            case UNAVAILABLE -> new String[] {tab.label + " unavailable", "The local backend is offline, so this list cannot be read."};
            default -> switch (tab) {
                case POSITIONS -> new String[] {"No open positions",
                        "Live trading is disabled. Positions appear here once an account is connected and trading is enabled."};
                case ORDERS -> new String[] {"No open orders",
                        "Order execution is disabled. Orders appear here once an account is connected and trading is enabled."};
                case TRADES -> new String[] {"No trades received", "Fills appear here once an account is connected and trading is enabled."};
                case SIGNALS -> new String[] {"No signals generated yet", "Signals appear after an approved strategy is connected."};
                case ACTIVITY -> new String[] {"No bot activity", "Bot events appear here once a strategy is approved."};
            };
        };
    }

    /** Força um estado (QA e erros reais futuros); os dados reais passam por {@link #apply}. */
    void setState(Tab tab, TabState state) {
        if (states.get(tab) == state && state != TabState.EMPTY && state != TabState.UNAVAILABLE && state != TabState.ERROR) {
            return;
        }
        states.put(tab, state);
        Page p = pages.get(tab);
        String[] m = message(tab, state);
        Fx.text(p.title, m[0]);
        Fx.text(p.text, m[1]);
        boolean msg = state == TabState.EMPTY || state == TabState.ERROR || state == TabState.UNAVAILABLE;
        Fx.shown(p.layer, msg);
        Fx.shown(p.skeleton, state == TabState.LOADING);
        Fx.cls(p.icon, "warn", state == TabState.UNAVAILABLE);
        Fx.cls(p.icon, "neg", state == TabState.ERROR);
        p.setAccessibleText(tab.label + ": " + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    void apply(TraderSnapshot t) {
        int key = java.util.Objects.hash(t.source, t.loading, t.backendOnline, t.positions, t.orders, t.trading,
                java.util.Arrays.deepHashCode(t.positionRows.toArray()), java.util.Arrays.deepHashCode(t.orderRows.toArray()),
                java.util.Arrays.deepHashCode(t.tradeRows.toArray()), java.util.Arrays.deepHashCode(t.signalRows.toArray()),
                java.util.Arrays.deepHashCode(t.activityRows.toArray()));
        if (key == lastKey) {
            return;
        }
        lastKey = key;
        Fx.text(buttons.get(Tab.POSITIONS), "Positions " + t.positions);
        Fx.text(buttons.get(Tab.ORDERS), "Orders " + t.orders);
        Fx.text(guard, DeskModel.liveOff(t) ? "Live trading is disabled" : "");
        List<List<String[]>> data = List.of(t.positionRows, t.orderRows, t.tradeRows, t.signalRows, t.activityRows);
        for (Tab tab : Tab.values()) {
            List<String[]> rows = data.get(tab.ordinal());
            TTable.update(tables.get(tab), rows);
            setState(tab, stateOf(tab, t, rows));
        }
    }

    private void showSelected() {
        for (Tab tab : Tab.values()) {
            boolean on = tab == selected;
            Fx.shown(pages.get(tab), on);
        }
    }

    List<Node> pageNodes() {
        return new ArrayList<>(pages.values());
    }
}
