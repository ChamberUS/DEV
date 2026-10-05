package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.util.Fmt;

/**
 * Order Book V2: n níveis de ask, linha Mid, n níveis de bid. A quantidade de linhas muda só com o breakpoint;
 * um tick troca texto e largura de barra nas MESMAS linhas (nada é reconstruído por tick). Sem feed: esqueleto
 * estático sem barras; sem dado de mercado (caído/erro): mensagem. STALE mantém o último valor, esmaecido e marcado.
 */
final class OrderBookPanel extends VBox {
    private final Label spread = Fx.label("Spread —", "byx-desk-t3");
    private final Label stale = Fx.label("STALE", "byx-stale-chip");
    private final GridRow header = new GridRow().header("PRICE", "SIZE", "TOTAL");
    private final VBox rows = new VBox();
    private final Label message = Fx.label("", "byx-desk-t3", "byx-desk-message");
    private final GridRow mid = new GridRow("mid");
    private final List<GridRow> asks = new ArrayList<>();
    private final List<GridRow> bids = new ArrayList<>();
    private int levels;

    OrderBookPanel() {
        getStyleClass().add("byx-desk-book");
        setId("desk-order-book");
        Label title = Fx.label("Order book", "byx-section-title-sm");
        HBox top = Fx.row(8, title, stale, Fx.spacer(), spread);
        top.getStyleClass().add("byx-desk-panel-head");
        Fx.shown(stale, false);
        message.setWrapText(true);
        message.setAlignment(Pos.CENTER);
        message.setMaxWidth(Double.MAX_VALUE);
        Fx.shown(message, false);
        StackPane body = new StackPane(rows, message);
        body.setAlignment(Pos.TOP_CENTER);
        getChildren().addAll(top, header, body);
        setInline(false);
    }

    void setInline(boolean inline) {
        Fx.cls(this, "byx-panel", !inline);
        Fx.cls(this, "byx-desk-inline", inline);
    }

    int levels() {
        return levels;
    }

    GridRow midRow() {
        return mid;
    }

    List<GridRow> askRows() {
        return asks;
    }

    List<GridRow> bidRows() {
        return bids;
    }

    /** Recria o pool só quando a quantidade de níveis muda (troca de breakpoint). Começa em esqueleto. */
    void setLevels(int n) {
        if (n == levels && !asks.isEmpty()) {
            return;
        }
        levels = n;
        asks.clear();
        bids.clear();
        rows.getChildren().clear();
        for (int i = 0; i < n; i++) {
            GridRow r = new GridRow("ask");
            r.showSkeleton(true);
            asks.add(r);
        }
        for (int i = 0; i < n; i++) {
            GridRow r = new GridRow("bid");
            r.showSkeleton(true);
            bids.add(r);
        }
        mid.text("—", "Mid", "");
        mid.cell(1).getStyleClass().add("byx-desk-mid-label");
        mid.showSkeleton(false);
        rows.getChildren().addAll(asks);
        rows.getChildren().add(mid);
        rows.getChildren().addAll(bids);
    }

    /** book só vale quando feed.showsMarketData(); fora disso os parâmetros de dados são ignorados. */
    void apply(DeskModel.Feed feed, DeskModel.Book book) {
        boolean data = feed.showsMarketData() && !book.empty();
        boolean skeleton = feed.waiting();
        // sem dado e sem espera: mensagem (caído, erro, indisponível) ou "feed vivo sem book"
        boolean msg = !data && !feed.waiting();
        Fx.visible(rows, !msg);
        Fx.shown(message, msg);
        Fx.text(message, !msg ? "" : feed.showsMarketData() ? "No order book levels received yet."
                : "No market data available · " + feed.label);
        Fx.shown(stale, feed.looksStale());
        Fx.cls(rows, "stale", feed.looksStale());
        setAccessibleText("Order book. " + (data ? "Spread " + (book.spread() == null ? Fmt.NA : Fmt.price(book.spread()))
                : feed.waiting() ? DeskModel.WAITING_TEXT : "No market data"));
        if (!data) {
            for (GridRow r : asks) {
                clear(r, skeleton);
            }
            for (GridRow r : bids) {
                clear(r, skeleton);
            }
            mid.text("—", "Mid", "");
            Fx.cls(mid.cell(0), "byx-desk-t3", true);
            Fx.text(spread, "Spread —");
            return;
        }
        fill(asks, book.asks(), true);
        fill(bids, book.bids(), false);
        Fx.cls(mid.cell(0), "byx-desk-t3", book.mid() == null);
        mid.text(book.mid() == null ? "—" : Fmt.price(book.mid()), "Mid", "");
        Fx.text(spread, "Spread " + (book.spread() == null ? "—" : Fmt.price(book.spread())));
    }

    private static void clear(GridRow r, boolean skeleton) {
        r.text("", "", "");
        r.showSkeleton(skeleton);
        r.priceTone(null);
    }

    /** Linhas de ask ficam alinhadas ao mid: os dados ocupam as últimas linhas, as sobras ficam vazias. */
    private static void fill(List<GridRow> pool, List<DeskModel.BookRow> data, boolean ask) {
        int n = pool.size();
        for (int i = 0; i < n; i++) {
            GridRow r = pool.get(i);
            int di = ask ? i - (n - data.size()) : i; // asks: farthest first, best next to mid
            if (di < 0 || di >= data.size()) {
                clear(r, false);
                r.showDepth(0, null);
                continue;
            }
            DeskModel.BookRow b = data.get(di);
            r.showSkeleton(false);
            r.text(Fmt.price(b.price()), String.format(Locale.US, "%.3f", b.size()), String.format(Locale.US, "%.3f", b.total()));
            r.priceTone(ask ? "ask" : "bid");
            r.showDepth(b.depth(), ask ? "ask" : "bid");
        }
    }
}
