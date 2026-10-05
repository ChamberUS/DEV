package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Recent Trades V2: n linhas (Price | Size | Time). A fonte é a mesma do blotter Trades (auditoria §4.1):
 * sem feed e sem trades a lista é esqueleto/mensagem, nunca tick inventado. Direção: preço verde (BUY) ou
 * vermelho (SELL); o texto do lado continua legível no tooltip da linha.
 */
final class RecentTradesPanel extends VBox {
    private final Label stale = Fx.label("STALE", "byx-stale-chip");
    private final GridRow header = new GridRow().header("PRICE", "SIZE", "TIME");
    private final VBox rows = new VBox();
    private final Label message = Fx.label("", "byx-desk-t3", "byx-desk-message");
    private final List<GridRow> pool = new ArrayList<>();
    private int count;

    RecentTradesPanel() {
        getStyleClass().add("byx-desk-trades");
        setId("desk-recent-trades");
        HBox top = Fx.row(8, Fx.label("Recent trades", "byx-section-title-sm"), stale);
        top.getStyleClass().add("byx-desk-panel-head");
        Fx.shown(stale, false);
        message.setWrapText(true);
        message.setAlignment(Pos.CENTER_LEFT);
        message.setMaxWidth(Double.MAX_VALUE);
        Fx.shown(message, false);
        StackPane body = new StackPane(rows, message);
        body.setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(top, header, body);
        setInline(false);
    }

    void setInline(boolean inline) {
        Fx.cls(this, "byx-panel", !inline);
        Fx.cls(this, "byx-desk-inline", inline);
    }

    int count() {
        return count;
    }

    List<GridRow> rows() {
        return pool;
    }

    void setRows(int n) {
        if (n == count && !pool.isEmpty()) {
            return;
        }
        count = n;
        pool.clear();
        rows.getChildren().clear();
        for (int i = 0; i < n; i++) {
            GridRow r = new GridRow("trade");
            r.showSkeleton(true);
            pool.add(r);
        }
        rows.getChildren().addAll(pool);
    }

    void apply(DeskModel.Feed feed, List<DeskModel.Trade> trades) {
        boolean data = feed.showsMarketData() && !trades.isEmpty();
        boolean msg = !data && !feed.waiting();
        Fx.visible(rows, !msg);
        Fx.shown(message, msg);
        Fx.text(message, !msg ? "" : feed.showsMarketData() ? "No trades received." : "No market data available · " + feed.label);
        Fx.shown(stale, feed.looksStale() && data);
        Fx.cls(rows, "stale", feed.looksStale());
        setAccessibleText("Recent trades. " + (data ? trades.size() + " shown" : feed.waiting() ? DeskModel.WAITING_TEXT : "No trades"));
        for (int i = 0; i < pool.size(); i++) {
            GridRow r = pool.get(i);
            if (!data) {
                r.text("", "", "");
                r.priceTone(null);
                r.showSkeleton(feed.waiting());
                continue;
            }
            r.showSkeleton(false);
            if (i < trades.size()) {
                DeskModel.Trade t = trades.get(i);
                r.text(t.price(), t.size(), t.time());
                r.priceTone(t.buy() ? "buy" : t.sell() ? "sell" : null);
            } else {
                r.text("", "", "");
                r.priceTone(null);
            }
        }
    }
}
