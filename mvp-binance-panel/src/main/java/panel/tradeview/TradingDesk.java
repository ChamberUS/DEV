package panel.tradeview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.ui.View;

/**
 * Trading Desk V2 (conteúdo V2 dentro do shell V2, sem folhas legadas). Uma grade de quatro linhas
 * (cabeçalho 60 · métricas 84 · gráfico flexível · blotter fixo) e as colunas de contexto do breakpoint.
 * Os painéis são criados uma vez; trocar de breakpoint só os reorganiza (reparent), e um snapshot só altera
 * texto, classe e largura de barras dos nós existentes. O Desk lê o estado operacional; não escreve nele e não
 * tem nenhum controle que possa mudar o live trading.
 */
public final class TradingDesk extends GridPane implements View {
    private final Supplier<TraderSnapshot> source;
    private final Clock clock;
    private final MotionService motion;

    private final MarketHeader header;
    private final MetricsStrip metrics = new MetricsStrip();
    private final ChartPanel chart;
    private final OrderBookPanel book = new OrderBookPanel();
    private final RecentTradesPanel trades = new RecentTradesPanel();
    private final BotPanel botStrip;
    private final BotPanel botRows;
    private final BotPanel botFull;
    private final InfoPanel risk = new InfoPanel("desk-risk", "Risk", "Exposure", "Drawdown", "Risk limits");
    private final InfoPanel freshness = new InfoPanel("desk-freshness", "Data freshness", "Binance feed", "Last tick", "Latency", "Staleness");
    private final ActivityPanel activity = new ActivityPanel();
    private final BlotterPanel blotter = new BlotterPanel();

    // coluna COMPACT: abas Market / Bot / Risk
    private final DeskSegment contextTabs = new DeskSegment("Context", "Market", "Bot", "Risk");
    private final VBox pageMarket = new VBox(10);
    private final VBox pageBot = new VBox(10);
    private final VBox pageRisk = new VBox(10);
    private final ScrollPane compactColumn;
    private final ScrollPane standardColumn;
    private final ScrollPane marketColumn;
    private final ScrollPane contextColumn;

    private DeskMode mode;
    private boolean modeApplied;
    private boolean shown;
    private TraderSnapshot last;
    private int lastFingerprint;
    private DeskModel.Feed lastFeed;
    private boolean dirty = true;
    private Timeline ticker;
    private int applied;
    private int skipped;
    private int modeChanges;

    public TradingDesk(MotionService motion, Supplier<TraderSnapshot> source, Clock clock) {
        this.motion = motion;
        this.source = source;
        this.clock = clock;
        getStyleClass().add("byx-desk");
        setId("desk");
        setMinSize(0, 0);
        setPadding(new Insets(14, 20, 16, 20));
        setHgap(14);
        setVgap(14);
        header = new MarketHeader(motion);
        chart = new ChartPanel(motion);
        botStrip = new BotPanel(motion, BotPanel.Variant.STRIP, true);
        botRows = new BotPanel(motion, BotPanel.Variant.ROWS, false);
        botFull = new BotPanel(motion, BotPanel.Variant.FULL, false);

        pageMarket.getChildren().addAll(book, trades, botStrip);
        pageBot.getChildren().addAll(botRows, activity);
        pageRisk.getChildren().addAll(risk, freshness);
        VBox.setVgrow(activity, Priority.ALWAYS);
        pageBot.setPadding(new Insets(6, 0, 0, 0));
        pageRisk.setPadding(new Insets(6, 0, 0, 0));
        StackPane pages = new StackPane(pageMarket, pageBot, pageRisk);
        VBox compactContent = new VBox(10, contextTabs, pages);
        compactContent.setId("desk-context");
        compactColumn = column(compactContent, true);
        contextTabs.setOnSelect(this::showContextPage);
        showContextPage(0);

        standardColumn = column(new VBox(14), false);
        marketColumn = column(new VBox(14), false);
        contextColumn = column(new VBox(14), false);
        standardColumn.setId("desk-right");
        marketColumn.setId("desk-market");
        contextColumn.setId("desk-context-col");
        compactColumn.setId("desk-right");

        widthProperty().addListener((o, a, w) -> applyMode(DeskMode.of(w.doubleValue())));
        applyMode(DeskMode.COMPACT);
    }

    private static ScrollPane column(VBox content, boolean panelChrome) {
        ScrollPane s = new ScrollPane(content);
        s.getStyleClass().add("byx-desk-scroll");
        if (panelChrome) {
            s.getStyleClass().addAll("byx-panel", "byx-desk-context");
        }
        s.setFitToWidth(true);
        s.setFitToHeight(true);
        s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        s.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        s.setMinSize(0, 0);
        s.setPannable(false);
        return s;
    }

    // ------------------------------------------------------------------ layout

    public DeskMode mode() {
        return mode;
    }

    /** Reorganiza para o breakpoint. Os painéis são os mesmos; só o pai, as colunas e as contagens mudam. */
    void applyMode(DeskMode next) {
        if (modeApplied && next == mode) {
            return;
        }
        mode = next;
        modeApplied = true;
        modeChanges++;
        boolean compact = next == DeskMode.COMPACT;
        book.setLevels(next.bookLevels);
        trades.setRows(next.tradeRows);
        book.setInline(compact);
        trades.setInline(compact);
        risk.setInline(compact);
        freshness.setInline(compact);
        activity.setInline(compact);
        botRows.setInline(compact);
        blotter.setMode(next);

        // soltar os painéis dos pais antigos antes de reparentar
        for (VBox box : new VBox[] {pageMarket, pageBot, pageRisk, (VBox) standardColumn.getContent(),
                (VBox) marketColumn.getContent(), (VBox) contextColumn.getContent()}) {
            box.getChildren().clear();
        }
        getChildren().clear();
        getColumnConstraints().clear();
        getRowConstraints().clear();

        getRowConstraints().addAll(fixed(60), fixed(84), grow(), fixed(next.blotterHeight));
        ColumnConstraints left = new ColumnConstraints();
        left.setHgrow(Priority.ALWAYS);
        left.setMinWidth(0);
        getColumnConstraints().add(left);
        switch (next) {
            case COMPACT -> {
                pageMarket.getChildren().addAll(book, trades, botStrip);
                pageBot.getChildren().addAll(botRows, activity);
                pageRisk.getChildren().addAll(risk, freshness);
                VBox.setVgrow(activity, Priority.ALWAYS);
                getColumnConstraints().add(width(next.marketColumn));
                place(header, 0, 0, 2, 1);
                place(metrics, 0, 1, 1, 1);
                place(chart, 0, 2, 1, 1);
                place(compactColumn, 1, 1, 1, 2);
                place(blotter, 0, 3, 2, 1);
            }
            case STANDARD -> {
                ((VBox) standardColumn.getContent()).getChildren().addAll(book, trades, botRows);
                VBox.setVgrow(botRows, Priority.ALWAYS);
                getColumnConstraints().add(width(next.marketColumn));
                place(header, 0, 0, 2, 1);
                place(metrics, 0, 1, 1, 1);
                place(chart, 0, 2, 1, 1);
                place(standardColumn, 1, 1, 1, 3);
                place(blotter, 0, 3, 1, 1);
            }
            case EXPANDED -> {
                ((VBox) marketColumn.getContent()).getChildren().addAll(book, trades);
                VBox.setVgrow(trades, Priority.ALWAYS);
                ((VBox) contextColumn.getContent()).getChildren().addAll(botFull, risk, freshness, activity);
                VBox.setVgrow(activity, Priority.ALWAYS);
                getColumnConstraints().addAll(width(next.marketColumn), width(next.contextColumn));
                place(header, 0, 0, 3, 1);
                place(metrics, 0, 1, 1, 1);
                place(chart, 0, 2, 1, 1);
                place(marketColumn, 1, 1, 1, 3);
                place(contextColumn, 2, 1, 1, 3);
                place(blotter, 0, 3, 1, 1);
            }
        }
        dirty = true;
        if (last != null) {
            update(last);
        }
    }

    /** Posição explícita: as restrições ficam no nó e sobrevivem à troca de breakpoint, então os spans sempre são reescritos. */
    private void place(Node node, int col, int row, int colSpan, int rowSpan) {
        GridPane.setConstraints(node, col, row, colSpan, rowSpan);
        getChildren().add(node);
    }

    private static RowConstraints fixed(double h) {
        RowConstraints r = new RowConstraints(h, h, h);
        r.setVgrow(Priority.NEVER);
        return r;
    }

    private static RowConstraints grow() {
        RowConstraints r = new RowConstraints();
        r.setVgrow(Priority.ALWAYS);
        r.setMinHeight(0);
        return r;
    }

    private static ColumnConstraints width(double w) {
        return new ColumnConstraints(w, w, w);
    }

    private void showContextPage(int i) {
        Fx.shown(pageMarket, i == 0);
        Fx.shown(pageBot, i == 1);
        Fx.shown(pageRisk, i == 2);
    }

    // ------------------------------------------------------------------ View

    @Override
    public Node node() {
        return this;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        update(source.get());
    }

    @Override
    public void onShow() {
        shown = true;
        syncTicker();
    }

    @Override
    public void onHide() {
        shown = false;
        syncTicker();
    }

    /** Aplica o snapshot. Igual ao anterior (e mesmo breakpoint): nenhum nó é tocado além de relógio/idade. */
    void update(TraderSnapshot t) {
        last = t;
        Instant now = clock.instant();
        DeskModel.Feed feed = DeskModel.feed(t, now);
        int fp = DeskModel.fingerprint(t);
        boolean changed = dirty || fp != lastFingerprint || feed != lastFeed;
        // o que depende do relógio é sempre reavaliado, mas só escreve se o texto mudou
        header.apply(t, feed);
        metrics.apply(DeskModel.metrics(t, feed, now));
        if (changed) {
            applied++;
            chart.apply(t, feed);
            book.apply(feed, DeskModel.book(t.asks, t.bids, book.levels()));
            trades.apply(feed, DeskModel.trades(t.tradeRows, trades.count()));
            botStrip.apply(t);
            botRows.apply(t);
            botFull.apply(t);
            blotter.apply(t);
            activity.apply(t);
            applyRisk(t, feed, now);
        } else {
            skipped++;
        }
        applyFreshness(t, feed, now);
        lastFingerprint = fp;
        lastFeed = feed;
        dirty = false;
        syncTicker();
    }

    private void applyRisk(TraderSnapshot t, DeskModel.Feed feed, Instant now) {
        var cells = DeskModel.metrics(t, feed, now);
        risk.row(0).set(cells.get(2).value(), true, cells.get(2).state() == DeskModel.CellState.NA ? "dim" : null);
        risk.row(1).set(cells.get(3).value(), true, cells.get(3).state() == DeskModel.CellState.NA ? "dim" : null);
        risk.row(2).set("Unavailable", false, "dim"); // não existe fonte de limites de risco
    }

    private void applyFreshness(TraderSnapshot t, DeskModel.Feed feed, Instant now) {
        freshness.row(0).set(feed.freshnessText, false, feed.waiting() ? "dim" : feed.looksStale() ? "warn" : null);
        boolean known = t.feedUpdatedAt != null && feed.showsMarketData();
        freshness.row(1).set(known ? panel.util.Fmt.time(t.feedUpdatedAt) : "—", true, known ? null : "dim");
        freshness.row(2).set("—", true, "dim"); // latência: sem fonte
        freshness.row(3).set(known ? DeskModel.age(Duration.between(t.feedUpdatedAt, now)) : "N/A", true,
                known ? (feed.looksStale() ? "warn" : null) : "dim");
    }

    /** Um relógio de 1 s só existe enquanto o Desk está visível E há horário de feed para envelhecer. */
    private void syncTicker() {
        boolean want = shown && last != null && last.feedUpdatedAt != null;
        if (want && ticker == null) {
            ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> {
                if (last != null) {
                    update(last);
                }
            }));
            ticker.setCycleCount(Animation.INDEFINITE);
            ticker.play();
        } else if (!want && ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    boolean tickerRunning() {
        return ticker != null && ticker.getStatus() == Animation.Status.RUNNING;
    }

    // ------------------------------------------------------------------ inspeção (testes e QA)

    int applied() {
        return applied;
    }

    int skipped() {
        return skipped;
    }

    int modeChanges() {
        return modeChanges;
    }

    MarketHeader header() {
        return header;
    }

    MetricsStrip metrics() {
        return metrics;
    }

    ChartPanel chart() {
        return chart;
    }

    OrderBookPanel book() {
        return book;
    }

    RecentTradesPanel trades() {
        return trades;
    }

    BotPanel botStrip() {
        return botStrip;
    }

    BotPanel botRows() {
        return botRows;
    }

    BotPanel botFull() {
        return botFull;
    }

    InfoPanel risk() {
        return risk;
    }

    InfoPanel freshness() {
        return freshness;
    }

    ActivityPanel activity() {
        return activity;
    }

    BlotterPanel blotter() {
        return blotter;
    }

    DeskSegment contextTabs() {
        return contextTabs;
    }

    ScrollPane compactColumn() {
        return compactColumn;
    }

    /** Quantidade de nós do Desk (a mesma em todo tick; só muda com o breakpoint). */
    int nodeCount() {
        return count(this);
    }

    /** As células virtuais de um TableView são do controle (criadas ao exibir); a tabela conta como um nó. */
    private static int count(Node n) {
        int c = 1;
        if (n instanceof javafx.scene.control.TableView<?>) {
            return c;
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                c += count(child);
            }
        }
        return c;
    }
}
