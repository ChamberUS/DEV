package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxFonts;

/**
 * Faixa de métricas V2 (84 px): Equity, Daily PnL, Exposure, Drawdown, Data age. Cada célula está em um de
 * READY, N/A, STALE ou UNAVAILABLE e mostra uma razão discreta quando existe ("No account", "No feed").
 */
final class MetricsStrip extends HBox {
    private static final String[] STATES = {"ready", "na", "stale", "unavailable"};

    private final List<Cell> cells = new ArrayList<>();

    MetricsStrip() {
        getStyleClass().addAll("byx-panel", "byx-desk-metrics");
        setId("desk-metrics");
        for (String title : new String[] {"Equity", "Daily PnL", "Exposure", "Drawdown", "Data age"}) {
            Cell c = new Cell(title);
            cells.add(c);
            getChildren().add(c);
            HBox.setHgrow(c, Priority.ALWAYS);
        }
        setMinHeight(84);
        setPrefHeight(84);
        setMaxHeight(84);
        setMinWidth(0);
    }

    List<Cell> cells() {
        return cells;
    }

    void apply(List<DeskModel.Cell> model) {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).apply(model.get(i));
        }
    }

    static final class Cell extends VBox {
        private final Label key;
        private final Label value = Fx.label("N/A", "byx-desk-metric-value");
        private final Label reason = Fx.label("", "byx-desk-reason");
        private final String title;

        Cell(String title) {
            this.title = title;
            getStyleClass().add("byx-desk-metric");
            setPadding(new Insets(10, 20, 10, 20));
            key = ByxFonts.upper(Fx.label(title, "byx-label"));
            getChildren().addAll(key, value, reason);
            setMinWidth(0);
            setAccessibleRoleDescription("metric");
        }

        Label value() {
            return value;
        }

        Label reason() {
            return reason;
        }

        void apply(DeskModel.Cell c) {
            String state = c.state().name().toLowerCase(Locale.ROOT);
            Fx.tone(value, state, STATES);
            String signed = c.state() == DeskModel.CellState.READY && (title.equals("Daily PnL") || title.equals("Drawdown"))
                    ? (c.value().startsWith("-") ? "neg" : c.value().startsWith("+") && !c.value().matches("\\+0(\\.0+)?.*") ? "pos" : null)
                    : null;
            Fx.tone(value, signed, "neg", "pos");
            Fx.text(value, c.value());
            Fx.text(reason, c.reason() == null ? "" : c.reason());
            Fx.cls(reason, "warn", c.state() == DeskModel.CellState.STALE);
            setAccessibleText(title + ": " + c.value() + (c.reason() == null ? "" : ", " + c.reason()) + ", " + c.state());
        }
    }
}
