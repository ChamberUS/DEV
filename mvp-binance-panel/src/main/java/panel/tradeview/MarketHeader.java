package panel.tradeview;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.design.ByxStatusDot;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.util.Fmt;

/**
 * Cabeçalho de mercado V2 (60 px): símbolo, tipo de contrato, mercado, preço, variação e estatísticas de 24h,
 * status do feed, estado do live trading e timeframe. Só valores reais: sem feed o preço é "—" e as 24h são
 * N/A; valores do snapshot não são mostrados quando o feed não pode ter produzido dado (caído, erro).
 */
final class MarketHeader extends HBox {
    private final Label symbol = Fx.label("N/A", "byx-desk-symbol");
    private final Label contract = ByxBadge.of("PERPETUAL", ByxBadge.Tone.NEUTRAL);
    private final Label venue = Fx.label("Binance USD-M Futures", "byx-desk-venue");
    private final Label price = Fx.label("—", "byx-desk-price", "na");
    private final Label change = Fx.label("24h N/A", "byx-desk-change");
    private final Stat mark = new Stat("Mark price");
    private final Stat high = new Stat("24h high");
    private final Stat low = new Stat("24h low");
    private final Stat volume = new Stat("24h volume (USDT)"); // janela móvel de 24 h, em USDT
    private final HBox stats = Fx.row(18, mark, high, low, volume);
    private final Label liveBadge = ByxBadge.of("LIVE OFF", ByxBadge.Tone.NEUTRAL);
    private final ByxStatusDot dot;
    private final Label status = Fx.label(DeskModel.WAITING_TEXT, "byx-desk-status-text");
    private final Label update = Fx.label("", "byx-desk-update");
    private boolean statsWanted;
    private final HBox pill;
    private final Tooltip feedTip = new Tooltip();
    private final DeskSegment timeframe = new DeskSegment("Timeframe", "1m", "5m", "15m", "1h", "4h");

    MarketHeader(MotionService motion) {
        getStyleClass().addAll("byx-panel", "byx-desk-header");
        setId("desk-header");
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(false);
        setSpacing(16);
        setPadding(new Insets(0, 20, 0, 20));
        setMinSize(0, 60);
        setPrefHeight(60);
        setMaxHeight(60);
        dot = new ByxStatusDot(9, motion);
        pill = Fx.row(8, dot, new VBox(0, status, update));
        pill.setFillHeight(false);
        pill.getStyleClass().add("byx-desk-status");
        pill.setId("desk-feed-status");
        Tooltip.install(pill, feedTip);
        venue.setMinWidth(0);
        HBox.setHgrow(venue, Priority.SOMETIMES);
        liveBadge.setId("desk-live-badge");
        liveBadge.setTooltip(new Tooltip("Live trading is OFF. This screen cannot enable it."));
        for (int i = 1; i < 5; i++) {
            timeframe.disable(i, "The current backend supplies 1m candles.");
        }
        timeframe.setId("desk-timeframe");
        getChildren().addAll(symbol, contract, venue, Fx.spacer(), price, change, stats, liveBadge, pill, timeframe);
        // o que identifica o mercado nunca é cortado: só o nome do mercado (venue) cede espaço; as estatísticas de 24h
        // saem quando a faixa não comporta (janela mínima 1280)
        for (var keep : new javafx.scene.Node[] {symbol, contract, price, change, stats, liveBadge, pill, timeframe}) {
            ((javafx.scene.layout.Region) keep).setMinWidth(USE_PREF_SIZE);
        }
        Fx.shown(stats, false);
        Fx.shown(update, false);
        widthProperty().addListener((o, a, w) -> syncStats());
    }

    DeskSegment timeframe() {
        return timeframe;
    }

    Label priceLabel() {
        return price;
    }

    ByxStatusDot dot() {
        return dot;
    }

    Label statusLabel() {
        return status;
    }

    void apply(TraderSnapshot t, DeskModel.Feed feed) {
        Fx.text(symbol, t.symbol == null || t.symbol.isBlank() ? Fmt.NA : t.symbol);
        Fx.shown(contract, DeskModel.perpetual(t.market));
        Fx.text(venue, DeskModel.venue(t.market));
        boolean data = feed.showsMarketData();
        boolean hasPrice = data && t.price != null;
        Fx.text(price, hasPrice ? Fmt.price(t.price) : "—");
        Fx.cls(price, "na", !hasPrice);
        Fx.cls(price, "stale", hasPrice && feed.looksStale());
        boolean hasChange = data && t.change24hPct != null;
        Fx.text(change, hasChange ? DeskModel.change24h(t) : "24h N/A");
        Fx.tone(change, hasChange ? (t.change24hPct < 0 ? "neg" : t.change24hPct > 0 ? "pos" : null) : null, "neg", "pos");
        Fx.cls(change, "stale", hasChange && feed.looksStale());
        boolean day = data && (DeskModel.hasDayStats(t) || t.markPrice != null);
        statsWanted = day;
        syncStats();
        if (day) {
            mark.set(t.markPrice == null ? Fmt.NA : Fmt.price(t.markPrice), feed.looksStale()); // distinto do último preço negociado
            high.set(t.high24h == null ? Fmt.NA : Fmt.price(t.high24h), feed.looksStale());
            low.set(t.low24h == null ? Fmt.NA : Fmt.price(t.low24h), feed.looksStale());
            volume.set(DeskModel.volume(t.volume24h), feed.looksStale());
        }
        Fx.text(liveBadge, DeskModel.liveOff(t) ? "LIVE OFF" : "LIVE " + Fmt.text(t.trading).toUpperCase(java.util.Locale.ROOT));
        Fx.text(status, feed.label);
        dot.setState(feed.dot, feed.expectedUnavailable);
        boolean stale = feed.looksStale();
        Fx.shown(update, stale);
        Fx.text(update, "LAST UPDATE " + (t.feedUpdatedAt == null ? "—" : Fmt.time(t.feedUpdatedAt)));
        Fx.cls(pill, "stale", stale);
        pill.setAccessibleText("Market feed: " + feed.label);
        String tip = "Market feed: " + Fmt.text(t.feed) + " · Backend: " + (t.backendOnline ? "Online" : "Offline");
        if (!tip.equals(feedTip.getText())) {
            feedTip.setText(tip);
        }
    }

    /** Largura mínima da faixa para as estatísticas de 24h (1440 → 1332 cabe; a janela mínima 1280 → 1172 não). */
    static final double STATS_MIN_WIDTH = 1300;

    private void syncStats() {
        Fx.shown(stats, statsWanted && getWidth() >= STATS_MIN_WIDTH);
    }

    boolean statsShown() {
        return stats.isManaged() && stats.isVisible();
    }

    private static final class Stat extends VBox {
        private final Label value = Fx.label("N/A", "byx-desk-stat-value");

        Stat(String title) {
            getStyleClass().add("byx-desk-stat");
            getChildren().addAll(ByxFonts.upper(Fx.label(title, "byx-label")), value);
            setMinWidth(USE_PREF_SIZE);
        }

        void set(String text, boolean stale) {
            Fx.text(value, text);
            Fx.cls(value, "stale", stale);
        }
    }
}
