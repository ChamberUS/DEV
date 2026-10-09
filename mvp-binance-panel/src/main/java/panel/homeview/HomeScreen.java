package panel.homeview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.i18n.Strings;
import panel.model.ByxSnapshot;
import panel.model.DataSource;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.tradeview.DeskModel;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Home (B01): native replacement for the old "land on the ETHUSDT chart" entry. Public, read-only information only.
 * It never shows a number it was not given: missing = "—" with a reason, stale data is labelled with its age, a failed request is an error
 * (not "no markets"), and spot is never conflated with perpetual futures. Every action is a navigation request to the router, which keeps
 * sole authority over what opens.
 */
public final class HomeScreen implements View {
    /** Everything the Home reads, supplied by the app (tests inject fixtures). All reads are cheap snapshots; nothing blocks the FX thread. */
    public interface Data {
        String displayName();

        TraderSnapshot trader();

        ByxSnapshot network();

        boolean researchVisible();

        boolean walletVerified();

        HomeMarkets.Catalog catalog();

        /** The user pressed "Try again" on a catalog error. */
        void retryCatalog();

        /** Welcome can be replayed (the dialog is owned by the app). */
        void replayWelcome();
    }

    private static final double TWO_COLUMNS_FROM = 1180;
    private final MotionService motion;
    private final Clock clock;
    private final Data data;
    private final Consumer<String> navigate;
    private final ScrollPane scroll;
    private final VBox page = Kit.page(18);
    private final Label subtitle = Fx.label("", "byx-desk-secondary");
    private final FlowPane chips = new FlowPane(8, 8);
    private final VBox marketsBody = new VBox(10);
    private final TextField search = new TextField();
    private final Label terminalNote = Fx.label("", "byx-desk-t3", "byx-desk-body");
    private final VBox gettingStarted = new VBox(10);
    private final VBox about = new VBox(8);
    private final GridPane services = new GridPane();
    private final GridPane grid = new GridPane();
    private final Timeline tick = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> render(false)));
    private boolean shown;
    private boolean startHidden;
    private String lastKey = "";
    private boolean twoColumns = true;
    private int serviceColumns = -1;
    private HomeMarkets.Model model;
    private List<ServiceCards.Card> cards = List.of();

    public HomeScreen(MotionService motion, Clock clock, Data data, Consumer<String> navigate) {
        this.motion = motion;
        this.clock = clock;
        this.data = data;
        this.navigate = navigate;
        tick.setCycleCount(Animation.INDEFINITE);

        chips.setAlignment(Pos.CENTER_LEFT);
        Label title = Fx.label(Strings.get("home.title"), "byx-page-title");
        title.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        subtitle.setWrapText(true);
        VBox head = new VBox(4, title, subtitle, chips);
        VBox.setMargin(chips, new Insets(6, 0, 0, 0));

        search.setPromptText(Strings.get("mk.search"));
        search.setAccessibleText(Strings.get("mk.search"));
        search.getStyleClass().add("byx-home-search");
        search.setMinWidth(180);
        search.setPrefWidth(260);
        search.textProperty().addListener((o, a, b) -> render(true));
        Button clear = new Button(Strings.get("mk.clear"));
        clear.getStyleClass().add("byx-link");
        clear.setOnAction(e -> {
            search.clear();
            search.requestFocus();
        });
        clear.visibleProperty().bind(search.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        HBox marketsHead = new HBox(10, Fx.label(Strings.get("mk.title"), "byx-section-title-sm"), Fx.spacer(), clear, search);
        marketsHead.setAlignment(Pos.CENTER_LEFT);
        terminalNote.setWrapText(true);
        terminalNote.setManaged(false);
        terminalNote.setVisible(false);
        VBox markets = Kit.panel(null, marketsHead, marketsBody, terminalNote);
        markets.setId("home-markets");

        VBox started = Kit.panel(null, gettingStarted);
        started.setId("home-getting-started");
        VBox aboutPanel = Kit.panel(Strings.get("scope.title"), about);
        aboutPanel.setId("home-about");
        VBox side = new VBox(14, started, aboutPanel);

        grid.setHgap(14);
        grid.setVgap(14);
        grid.add(markets, 0, 0);
        grid.add(side, 1, 0);
        GridPane.setFillHeight(markets, false); // the markets panel hugs its content instead of stretching to the side column
        GridPane.setValignment(markets, javafx.geometry.VPos.TOP);
        applyColumns(true);

        Label svcTitle = Fx.label(Strings.get("svc.title"), "byx-section-title-sm");
        Label svcSub = Kit.muted(Strings.get("svc.sub"));
        services.setHgap(12);
        services.setVgap(12);
        VBox svcBox = new VBox(8, svcTitle, svcSub, services);
        svcBox.setId("home-services");

        page.getChildren().addAll(head, grid, svcBox);
        scroll = Kit.scroll(page);
        scroll.setId("home");
        scroll.getProperties().put("byx.view", "home");
        page.widthProperty().addListener((o, a, w) -> relayout(w.doubleValue()));
        render(true);
    }

    // -------------------------------------------------------------------- layout

    private void relayout(double width) {
        // inner width of the markets table: page padding (56), the 64% column in two-column mode, panel padding (32) and the row padding (20)
        double panelWidth = (width >= TWO_COLUMNS_FROM ? (width - 56 - 14) * 0.64 : width - 56) - 32 - 20;
        int level = panelWidth >= 700 ? 0 : panelWidth >= 590 ? 1 : panelWidth >= 440 ? 2 : 3;
        if (level != columnLevel) {
            columnLevel = level;
            render(true);
        }
        boolean two = width >= TWO_COLUMNS_FROM;
        if (two != twoColumns) {
            applyColumns(two);
        }
        int cols = width >= 980 ? 3 : width >= 640 ? 2 : 1;
        if (cols != serviceColumns) {
            layoutServices(cols);
        }
    }

    private void applyColumns(boolean two) {
        twoColumns = two;
        grid.getColumnConstraints().clear();
        Node markets = grid.getChildren().get(0);
        Node side = grid.getChildren().get(1);
        if (two) {
            ColumnConstraints a = new ColumnConstraints();
            a.setHgrow(Priority.ALWAYS);
            a.setPercentWidth(64);
            a.setMinWidth(0);
            ColumnConstraints b = new ColumnConstraints();
            b.setHgrow(Priority.ALWAYS);
            b.setPercentWidth(36);
            b.setMinWidth(0);
            grid.getColumnConstraints().addAll(a, b);
            GridPane.setConstraints(markets, 0, 0);
            GridPane.setConstraints(side, 1, 0);
        } else {
            ColumnConstraints a = new ColumnConstraints();
            a.setHgrow(Priority.ALWAYS);
            a.setPercentWidth(100);
            a.setMinWidth(0);
            grid.getColumnConstraints().add(a);
            GridPane.setConstraints(markets, 0, 0);
            GridPane.setConstraints(side, 0, 1);
        }
    }

    private void layoutServices(int cols) {
        serviceColumns = cols;
        services.getChildren().clear();
        services.getColumnConstraints().clear();
        for (int i = 0; i < cols; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setHgrow(Priority.ALWAYS);
            c.setPercentWidth(100.0 / cols);
            c.setMinWidth(0);
            services.getColumnConstraints().add(c);
        }
        for (int i = 0; i < cards.size(); i++) {
            services.add(card(cards.get(i)), i % cols, i / cols);
        }
    }

    // -------------------------------------------------------------------- render

    private void render(boolean force) {
        if (!shown && !force) {
            return;
        }
        Instant now = clock.instant();
        TraderSnapshot t = data.trader();
        ByxSnapshot net = data.network();
        DeskModel.Feed feed = DeskModel.feed(t, now);
        boolean mock = t.source == DataSource.MOCK;
        HomeMarkets.Model m = HomeMarkets.build(data.catalog(), i -> quoteOf(t, i), feed, mock, search.getText(), now);
        List<ServiceCards.Card> next = ServiceCards.build(new ServiceCards.Inputs(feed, nodeReachable(net), data.walletVerified(), data.researchVisible()));
        String key = key(m, t, net, next, startHidden, data.displayName());
        if (key.equals(lastKey) && !force) {
            return;
        }
        lastKey = key;
        model = m;
        subtitle.setText(Strings.fmt("home.sub", "n", data.displayName()));
        renderChips(t, net, mock);
        renderMarkets(m);
        renderGettingStarted();
        renderAbout(t, net);
        boolean cardsChanged = !next.equals(cards);
        cards = next;
        if (cardsChanged || serviceColumns < 0) {
            layoutServices(serviceColumns < 0 ? 3 : serviceColumns);
        }
    }

    private static String key(HomeMarkets.Model m, TraderSnapshot t, ByxSnapshot net, List<ServiceCards.Card> cards, boolean hidden, String name) {
        StringBuilder sb = new StringBuilder().append(m.state()).append('|').append(m.query()).append('|')
                .append(m.age() == null ? "-" : DeskModel.age(m.age())).append('|').append(m.partialRows()).append('|').append(hidden).append('|').append(name)
                .append('|').append(t.trading).append('|').append(net == null ? "" : net.environment()).append('|').append(cards);
        for (HomeMarkets.Row r : m.rows()) {
            sb.append('|').append(r.instrument()).append(r.quote());
        }
        return sb.toString();
    }

    private static HomeMarkets.Quote quoteOf(TraderSnapshot t, HomeMarkets.Instrument i) {
        if (t.symbol == null || !t.symbol.equalsIgnoreCase(i.symbol())) {
            return null;
        }
        return new HomeMarkets.Quote(t.price, t.change24hPct, t.volume24h, t.feedUpdatedAt);
    }

    private static boolean nodeReachable(ByxSnapshot net) {
        if (net == null || net.connection() == null) {
            return false;
        }
        return switch (net.connection().toUpperCase(Locale.ROOT)) {
            case "ONLINE", "SYNCING", "DEGRADED", "STALE" -> true;
            default -> false;
        };
    }

    private void renderChips(TraderSnapshot t, ByxSnapshot net, boolean mock) {
        chips.getChildren().clear();
        chips.getChildren().add(ByxBadge.of(Strings.get("ctx.beta").toUpperCase(Locale.ROOT), ByxBadge.Tone.ACCENT));
        chips.getChildren().add(ByxBadge.of(Strings.get("ctx.public").toUpperCase(Locale.ROOT), ByxBadge.Tone.INFO));
        boolean liveOff = !"ENABLED".equalsIgnoreCase(t.trading);
        chips.getChildren().add(ByxBadge.of(liveOff ? Strings.get("ctx.live").toUpperCase(Locale.ROOT) : ("LIVE TRADING " + t.trading).toUpperCase(Locale.ROOT),
                liveOff ? ByxBadge.Tone.NEUTRAL : ByxBadge.Tone.WARNING));
        String env = net == null || net.environment() == null || net.environment().isBlank() ? null : net.environment().toUpperCase(Locale.ROOT);
        chips.getChildren().add(ByxBadge.of(env == null ? Strings.get("home.net.unknown").toUpperCase(Locale.ROOT) : env + " · TEST", env == null ? ByxBadge.Tone.NEUTRAL : ByxBadge.Tone.WARNING));
        if (mock) {
            chips.getChildren().add(ByxBadge.of(Strings.get("home.mock"), ByxBadge.Tone.WARNING));
        }
    }

    private void renderMarkets(HomeMarkets.Model m) {
        marketsBody.getChildren().clear();
        Fx.visible(terminalNote, false);
        Fx.shown(terminalNote, false);
        marketsBody.getChildren().add(Kit.dim(Strings.get("mk.meta")));
        switch (m.state()) {
            case LOADING -> {
                if (m.totalRows() == 0 && m.rows().isEmpty()) {
                    marketsBody.getChildren().add(Kit.muted(Strings.get("mk.loading")));
                    for (int i = 0; i < 2; i++) {
                        Region sk = Fx.skeleton();
                        sk.setMinHeight(36);
                        marketsBody.getChildren().add(sk);
                    }
                } else {
                    addRows(m);
                }
            }
            case ERROR -> {
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.ERROR, Strings.get("mk.errT"), Strings.get("mk.errB")));
                if ("FEED_ERROR".equals(m.detail())) {
                    addRows(m);
                } else {
                    ByxButton retry = new ByxButton(Strings.get("common.retry"), ByxButton.Variant.SECONDARY, motion);
                    retry.setOnAction(e -> data.retryCatalog());
                    marketsBody.getChildren().add(retry);
                }
            }
            case EMPTY -> {
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.INFO, Strings.get("mk.emptyT"), Strings.get("mk.emptyB")));
            }
            case UNSUPPORTED_SEARCH -> {
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.INFO, Strings.fmt("mk.unsT", "q", m.query()), Strings.get("mk.unsB")));
                marketsBody.getChildren().add(Kit.label(Strings.get("mk.supported")));
                for (HomeMarkets.Instrument i : m.suggestions()) {
                    marketsBody.getChildren().add(instrumentCell(i));
                }
            }
            case OFFLINE -> {
                String body = m.age() != null ? Strings.fmt("mk.lastSeen", "n", DeskModel.age(m.age()))
                        : "NOT_CONFIGURED".equals(m.detail()) ? Strings.get("mk.notConnected") : Strings.get("mk.offNoData");
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.WARNING,
                        "NOT_CONFIGURED".equals(m.detail()) ? Strings.get("mk.notConnectedT") : Strings.get("mk.offT"), body));
                addRows(m);
            }
            case STALE -> {
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.WARNING, Strings.get("mk.staleT"),
                        m.age() == null ? Strings.get("home.fresh.unknown") : Strings.fmt("mk.staleAge", "n", DeskModel.age(m.age()))));
                addRows(m);
            }
            case PARTIAL_DATA -> {
                marketsBody.getChildren().add(new ByxBanner(ByxBanner.Kind.INFO, Strings.get("mk.na"),
                        Strings.fmt("mk.partB", "n", m.partialRows(), "m", m.totalRows())));
                addRows(m);
            }
            case READY -> addRows(m);
        }
        if (m.mock()) {
            marketsBody.getChildren().add(ByxBadge.data(ByxBadge.Data.DEMO_DATA));
        }
        if (m.single() && m.showsValues()) {
            marketsBody.getChildren().add(Kit.dim(Strings.get("mk.single")));
        }
    }

    /** Columns kept at a given width: 0 = all, 1 = without "Updated", 2 = also without "24h volume", 3 = also without "24h change" (the symbol and last price stay). */
    private int columnLevel = 0;

    private static GridPane columns(int level) {
        GridPane g = new GridPane();
        g.setHgap(12);
        ColumnConstraints first = new ColumnConstraints();
        first.setHgrow(Priority.ALWAYS);
        first.setMinWidth(150); // symbol + market-type chip always fit; the venue name is the one that ellipsizes
        g.getColumnConstraints().add(first);
        double[] widths = {96, 96, 150, 70};
        for (int c = 0; c < widths.length; c++) {
            boolean hidden = c == 3 && level >= 1 || c == 2 && level >= 2 || c == 1 && level >= 3;
            ColumnConstraints cc = new ColumnConstraints(hidden ? 0 : widths[c]);
            cc.setMinWidth(hidden ? 0 : widths[c]);
            cc.setMaxWidth(hidden ? 0 : widths[c]);
            cc.setHalignment(javafx.geometry.HPos.RIGHT);
            g.getColumnConstraints().add(cc);
        }
        return g;
    }

    private void addRows(HomeMarkets.Model m) {
        GridPane head = columns(columnLevel);
        head.setPadding(new Insets(0, 10, 0, 10));
        String[] heads = {Strings.get("mk.instrument"), Strings.get("mk.last"), Strings.get("mk.change"), Strings.get("mk.volume"), Strings.get("mk.updated")};
        for (int c = 0; c < heads.length; c++) {
            Label h = Kit.label(heads[c]);
            h.setMinWidth(Region.USE_PREF_SIZE);
            boolean hidden = c == 4 && columnLevel >= 1 || c == 3 && columnLevel >= 2 || c == 2 && columnLevel >= 3;
            h.setVisible(!hidden);
            h.setManaged(!hidden);
            if (c > 0) {
                GridPane.setHalignment(h, javafx.geometry.HPos.RIGHT);
            }
            head.add(h, c, 0);
        }
        VBox table = new VBox(2, head);
        for (HomeMarkets.Row r : m.rows()) {
            table.getChildren().add(rowButton(r, m));
        }
        marketsBody.getChildren().add(table);
    }

    private Node rowButton(HomeMarkets.Row r, HomeMarkets.Model m) {
        HomeMarkets.Quote q = r.quote();
        boolean stale = m.state() == HomeMarkets.State.STALE || m.state() == HomeMarkets.State.OFFLINE;
        Label last = value(q == null ? null : q.last(), v -> panel.util.Fmt.price(v), stale, null);
        Label change = value(q == null ? null : q.change24hPct(), v -> panel.util.Fmt.signed(v, "%"), stale,
                q == null || q.change24hPct() == null ? null : q.change24hPct() >= 0 ? "pos" : "neg");
        Label volume = value(q == null ? null : q.volume24hQuote(), v -> String.format(Locale.US, "%,.0f", v) + " " + r.instrument().quote(), stale, null);
        Instant at = q == null ? null : q.observedAt();
        Label updated = at == null ? missing() : Fx.label(DeskModel.age(Duration.between(at, clock.instant())), "byx-desk-row-value", "mono", stale ? "warn" : "dim");
        GridPane row = columns(columnLevel);
        row.add(instrumentCell(r.instrument()), 0, 0);
        Label[] cells = {last, change, volume, updated};
        for (int c = 0; c < cells.length; c++) {
            boolean hidden = c == 3 && columnLevel >= 1 || c == 2 && columnLevel >= 2 || c == 1 && columnLevel >= 3;
            cells[c].setMinWidth(Region.USE_PREF_SIZE);
            cells[c].setAlignment(Pos.CENTER_RIGHT);
            cells[c].setVisible(!hidden);
            cells[c].setManaged(!hidden);
            GridPane.setHalignment(cells[c], javafx.geometry.HPos.RIGHT);
            row.add(cells[c], c + 1, 0);
        }
        Button b = new Button();
        row.prefWidthProperty().bind(b.widthProperty().subtract(20)); // same inner width as the header grid: columns line up exactly
        b.setGraphic(row);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("byx-home-row");
        b.getProperties().put("home.symbol", r.instrument().symbol());
        String kind = r.instrument().kind() == HomeMarkets.Kind.PERPETUAL ? Strings.get("mk.perp") : Strings.get("mk.spot");
        b.setAccessibleText(r.instrument().symbol() + ", " + kind + ", " + r.instrument().venue() + ", "
                + Strings.get("mk.last") + " " + (q == null || q.last() == null ? Strings.get("mk.na") : panel.util.Fmt.price(q.last())) + ", "
                + (r.instrument().terminalSupported() ? Strings.get("mk.openTerm") : Strings.fmt("mk.termUnsupported", "s", r.instrument().symbol())));
        b.setOnAction(e -> openInTerminal(r.instrument()));
        return b;
    }

    private Node instrumentCell(HomeMarkets.Instrument i) {
        Label sym = Fx.label(i.symbol(), "byx-desk-row-value", "mono");
        boolean perp = i.kind() == HomeMarkets.Kind.PERPETUAL;
        Label kind = ByxBadge.of(perp ? Strings.get("mk.perp") : Strings.get("mk.spot"), perp ? ByxBadge.Tone.INFO : ByxBadge.Tone.NEUTRAL);
        Label venue = Fx.label(i.venue(), "byx-desk-t3");
        sym.setMinWidth(Region.USE_PREF_SIZE);
        kind.setMinWidth(Region.USE_PREF_SIZE);
        venue.setMinWidth(0); // the venue may ellipsize; the symbol and the market-type chip never do
        HBox h = new HBox(8, sym, kind, venue);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setMinWidth(0);
        return h;
    }

    private static Label missing() {
        Label l = Fx.label("—", "byx-desk-row-value", "mono", "dim");
        l.setAccessibleText(Strings.get("mk.na"));
        javafx.scene.control.Tooltip.install(l, new javafx.scene.control.Tooltip(Strings.get("mk.na")));
        return l;
    }

    private static Label value(Double v, java.util.function.Function<Double, String> fmt, boolean stale, String tone) {
        if (v == null) {
            return missing();
        }
        Label l = Fx.label(fmt.apply(v), "byx-desk-row-value", "mono");
        if (stale) {
            l.getStyleClass().add("warn");
        } else if (tone != null) {
            l.getStyleClass().add(tone);
        }
        return l;
    }

    private void openInTerminal(HomeMarkets.Instrument i) {
        if (i.terminalSupported()) {
            terminalNote.setText("");
            Fx.visible(terminalNote, false);
            Fx.shown(terminalNote, false);
            navigate.accept("t-desk");
        } else {
            terminalNote.setText(i.kind() == HomeMarkets.Kind.SPOT ? Strings.get("mk.spotNoTerm") : Strings.fmt("mk.termUnsupported", "s", i.symbol()));
            Fx.shown(terminalNote, true);
            Fx.visible(terminalNote, true);
        }
    }

    private void renderGettingStarted() {
        gettingStarted.getChildren().clear();
        gettingStarted.getChildren().add(Kit.titled(Strings.get("ob.title"), hideButton()));
        if (startHidden) {
            gettingStarted.getChildren().add(Kit.dim(Strings.get("home.hideNote")));
            Button show = new Button(Strings.get("home.showStart"));
            show.getStyleClass().add("byx-link");
            show.setOnAction(e -> {
                startHidden = false;
                render(true);
            });
            gettingStarted.getChildren().add(show);
            return;
        }
        gettingStarted.getChildren().add(step(Strings.get("ob.1"), "h-overview"));
        gettingStarted.getChildren().add(step(Strings.get("ob.2"), "t-desk"));
        gettingStarted.getChildren().add(step(Strings.get("ob.3"), "h-help"));
        gettingStarted.getChildren().add(Kit.dim(Strings.get("ob.note")));
        Button replay = new Button(Strings.get("welcome.replay"));
        replay.getStyleClass().add("byx-link");
        replay.setOnAction(e -> data.replayWelcome());
        gettingStarted.getChildren().add(replay);
    }

    private Node hideButton() {
        Button hide = new Button(Strings.get("common.hide"));
        hide.getStyleClass().add("byx-link");
        hide.setVisible(!startHidden);
        hide.setManaged(!startHidden);
        hide.setOnAction(e -> {
            startHidden = true; // session-only: preference persistence is not authorized in this build
            render(true);
        });
        return hide;
    }

    private Node step(String text, String route) {
        Label l = Kit.muted(text);
        HBox.setHgrow(l, Priority.ALWAYS);
        ByxButton go = new ByxButton(Strings.get("ob.go"), ByxButton.Variant.SECONDARY, motion).small();
        go.setOnAction(e -> navigate.accept(route));
        go.setAccessibleText(Strings.get("ob.go") + ": " + text);
        HBox h = new HBox(10, l, go);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void renderAbout(TraderSnapshot t, ByxSnapshot net) {
        about.getChildren().clear();
        boolean liveOff = !"ENABLED".equalsIgnoreCase(t.trading);
        String env = net == null || net.environment() == null || net.environment().isBlank() ? Strings.get("home.net.unknown")
                : net.environment().toUpperCase(Locale.ROOT) + " · TEST";
        about.getChildren().add(Kit.row(Strings.get("scope.data"), Strings.get("scope.dataV"), false));
        about.getChildren().add(Kit.row(Strings.get("scope.net"), env, false));
        about.getChildren().add(Kit.row(Strings.get("scope.trade"), liveOff ? Strings.get("ctx.live") : t.trading, false));
        about.getChildren().add(Kit.row(Strings.get("scope.wallet"), data.walletVerified() ? Strings.get("st.readonly") : Strings.get("scope.walletV"), false));
    }

    private Node card(ServiceCards.Card c) {
        Label title = Fx.label(Strings.get(c.titleKey()), "byx-section-title-sm");
        ByxBadge.Tone tone = switch (c.availability()) {
            case AVAILABLE -> ByxBadge.Tone.POSITIVE;
            case READ_ONLY -> ByxBadge.Tone.INFO;
            case UNAVAILABLE -> ByxBadge.Tone.WARNING;
            case PLANNED -> ByxBadge.Tone.NEUTRAL;
        };
        String state = switch (c.availability()) {
            case AVAILABLE -> Strings.get("st.available");
            case READ_ONLY -> Strings.get("st.readonly");
            case UNAVAILABLE -> Strings.get("st.unavailable");
            case PLANNED -> Strings.get("st.planned");
        };
        if (c.restricted()) { // Research is never "available": it opens only after the existing admin verification
            state = Strings.get("svc.research.restricted");
            tone = ByxBadge.Tone.ACCENT;
        }
        Label pill = ByxBadge.of(state.toUpperCase(Locale.ROOT), tone);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        HBox head = new HBox(8, title, Fx.spacer(), pill);
        head.setAlignment(Pos.CENTER_LEFT);
        Label desc = Kit.muted(Strings.get(c.descKey()));
        VBox body = new VBox(6, head, desc);
        if (c.reasonKey() != null) {
            body.getChildren().add(Kit.dim(Strings.get(c.reasonKey())));
        }
        body.setMinHeight(Region.USE_PREF_SIZE);
        String a11y = Strings.get(c.titleKey()) + ", " + state + ". " + Strings.get(c.descKey()) + (c.reasonKey() == null ? "" : " " + Strings.get(c.reasonKey()));
        if (!c.actionable()) {
            body.getStyleClass().addAll("byx-panel", "byx-home-card", "unavailable");
            body.setMaxHeight(Double.MAX_VALUE);
            body.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
            body.setAccessibleText(a11y);
            return body;
        }
        // an actionable card is ONE keyboard target: a transparent button covers the card (the card's own content sizes and wraps itself)
        body.getStyleClass().addAll("byx-panel", "byx-home-card", "byx-home-card-body");
        body.setMaxHeight(Double.MAX_VALUE);
        body.setMouseTransparent(true);
        Button b = new Button();
        b.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        b.getStyleClass().add("byx-home-card-button");
        b.getProperties().put("home.card", c.id());
        b.setAccessibleText(a11y + (c.restricted() ? " " + Strings.get("svc.research.cta") : " " + Strings.get("svc.open")));
        b.setOnAction(e -> navigate.accept(c.route()));
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(body, b);
        stack.getStyleClass().add("byx-home-card-stack");
        stack.setMaxHeight(Double.MAX_VALUE);
        return stack;
    }

    // -------------------------------------------------------------------- test hooks / View

    public HomeMarkets.Model model() {
        return model;
    }

    public List<ServiceCards.Card> cards() {
        return cards;
    }

    public TextField searchField() {
        return search;
    }

    public boolean timerRunning() {
        return tick.getStatus() == Animation.Status.RUNNING;
    }

    public Label terminalNote() {
        return terminalNote;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        render(false);
    }

    @Override
    public void onShow() {
        shown = true;
        render(true);
        tick.play();
    }

    @Override
    public void onHide() {
        shown = false;
        tick.stop();
    }

    @Override
    public void dispose() {
        onHide();
    }
}
