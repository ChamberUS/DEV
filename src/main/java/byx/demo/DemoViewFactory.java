package byx.demo;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxRegion;
import panel.design.RegionState;
import panel.helpview.FaqScreen;
import panel.helpview.HelpContent;
import panel.helpview.LegalScreen;
import panel.helpview.ShortcutsScreen;
import panel.helpview.SupportScreen;
import panel.homeview.HomeMarkets;
import panel.homeview.HomeScreen;
import panel.i18n.LocaleView;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.ui.trader.TTable;
import panel.v2.Kit;

/** Presentation-only composition. No authority, networking, Service, custody or research object exists here. */
public final class DemoViewFactory {
    private final MotionService motion;
    private final Supplier<TraderSnapshot> market;
    private final Clock clock;
    private final Consumer<String> navigate;

    public DemoViewFactory(MotionService motion, Supplier<TraderSnapshot> market, Clock clock, Consumer<String> navigate) {
        this.motion = motion;
        this.market = market;
        this.clock = clock;
        this.navigate = navigate;
    }

    public View create(String route) {
        return switch (route) {
            case "t-home" -> home();
            case "t-portfolio" -> portfolio();
            case "t-positions" -> positions();
            case "t-orders" -> orders();
            case "t-history" -> history();
            case "t-performance" -> performance();
            case "h-faq" -> new FaqScreen(motion, faq(), navigate);
            case "h-help" -> new SupportScreen(motion, navigate, false);
            case "h-about" -> about();
            case "h-terms" -> new LegalScreen("Terms of Use", legal(), true);
            case "h-privacy" -> new LegalScreen("Privacy", legal(), false);
            case "h-shortcuts" -> new ShortcutsScreen("Ctrl");
            case "sys-status" -> status();
            default -> throw new IllegalArgumentException("Unsupported presentation route");
        };
    }

    private View home() {
        return new HomeScreen(motion, clock, new HomeScreen.Data() {
            public String displayName() { return "DEMO_USER"; }
            public TraderSnapshot trader() { return market.get(); }
            public ByxSnapshot network() { return ByxSnapshot.unknown("UNAVAILABLE", "DEMO_OFFLINE", "DISABLED", "DEMO offline"); }
            public boolean researchVisible() { return false; }
            public boolean walletVerified() { return false; }
            public HomeMarkets.Catalog catalog() {
                return new HomeMarkets.Catalog.Loaded(List.of(
                    new HomeMarkets.Instrument("ETHUSDT", HomeMarkets.Kind.PERPETUAL, "DEMO", "USDT"),
                    new HomeMarkets.Instrument("BTCUSDT", HomeMarkets.Kind.PERPETUAL, "DEMO", "USDT")));
            }
            public void retryCatalog() { /* The versioned illustrative catalog is local and always available. */ }
            public void replayWelcome() { navigate.accept("h-about"); }
        }, navigate);
    }

    private View portfolio() {
        TraderSnapshot data = market.get();
        VBox page = page("Portfolio", "Illustrative portfolio. All balances and positions are fictional.");
        FlowPane summary = new FlowPane(14, 14,
            stat("Equity", "12,500.00 USDT"), stat("Daily PnL", "+125.00 USDT"),
            stat("Positions", "2"), stat("Orders", "0"));
        page.getChildren().addAll(summary,
            Kit.panel("Positions", table(new String[]{"Symbol", "Side", "Quantity", "Entry", "State"}, data.positionRows)),
            links(new String[]{"Markets & Portfolio", "Positions", "History"}, new String[]{"t-markets", "t-positions", "t-history"}));
        return read(page, "demo-portfolio");
    }

    private View positions() {
        VBox page = page("Positions", "Illustrative positions. No account is connected and no real order can be placed.");
        page.getChildren().add(Kit.panel("Positions", table(new String[]{"Symbol", "Side", "Quantity", "Entry", "State"}, market.get().positionRows)));
        return read(page, "demo-positions");
    }

    private View history() {
        VBox page = page("History", "Illustrative history. These rows are generated locally and are not real transactions.");
        page.getChildren().add(Kit.panel("History", table(new String[]{"Time", "Symbol", "State", "Quantity", "Price"}, market.get().tradeRows)));
        return read(page, "demo-history");
    }

    private View orders() {
        VBox page = page("Orders", "Real orders are unavailable in this DEMO.");
        ByxRegion region = new ByxRegion("Orders", java.util.EnumSet.of(RegionState.UNAVAILABLE), motion);
        region.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("No real orders", "This offline DEMO cannot create, submit, cancel or broadcast an order."));
        region.setMinHeight(240);
        page.getChildren().add(Kit.panel(null, Kit.titled("Orders", ByxBadge.availability(ByxBadge.Availability.UNAVAILABLE)), region));
        View result = read(page, "demo-orders");
        return new View() {
            public Node node() { return result.node(); }
            public void onSnapshot(Snapshot ignored) { }
            public void dispose() { region.dispose(); }
        };
    }

    private View performance() {
        VBox page = page("Performance", "Illustrative metrics. No strategy execution or performance report is connected.");
        page.getChildren().add(Kit.panel("Illustrative metrics", Kit.row("Exposure", "20%", true), Kit.row("Drawdown", "1%", true), Kit.row("Daily PnL", "+125.00 USDT", true)));
        return read(page, "demo-performance");
    }

    private View about() {
        VBox page = page("About BYX", "BYX-MVP DEMO — Windows Alpha 2");
        page.getChildren().addAll(
            Kit.panel("Presentation restored", Kit.muted("Original JavaFX login, shell, market panels, typography, themes and motion are reused.")),
            Kit.panel("Offline DEMO", Kit.muted("Public sign-in only opens a local USER session. Every market value is fictional."),
                Kit.row("Profile", "DEMO_USER / USER", true), Kit.row("Version", "0.1.0-alpha.2", true)),
            Kit.panel("Security", Kit.muted("Service, admin access, research, wallets, signing, custody, transactions and broadcasts are unavailable.")),
            Kit.panel("Feedback", Kit.muted("Submit feedback manually through GitHub Issues in ChamberUS/DEV. Never include private data or credentials.")),
            links(new String[]{"FAQ", "Help and support", "Terms of Use", "Privacy"}, new String[]{"h-faq", "h-help", "h-terms", "h-privacy"}));
        return read(page, "demo-about");
    }

    private View status() {
        VBox page = page("System Status", "Status of this local DEMO composition.");
        page.getChildren().addAll(
            Kit.panel("Offline DEMO", Kit.row("Data source", "MOCK", true), Kit.row("Profile", "DEMO_USER / USER", true)),
            Kit.panel("Unavailable capabilities", Kit.row("Backend", "Disabled", false), Kit.row("Service", "Disabled", false),
                Kit.row("Admin access", "Disabled", false), Kit.row("LAN", "Unavailable", false), Kit.row("Real trading", "Disabled", false),
                Kit.row("Wallets", "Unavailable", false), Kit.row("Signing", "Unavailable", false), Kit.row("Broadcasts", "0", true)),
            links(new String[]{"Settings", "Help and support"}, new String[]{"t-settings", "h-help"}));
        return read(page, "demo-system-status");
    }

    private static HelpContent.FaqContent faq() {
        return new HelpContent.FaqContent("DEMO", List.of("demo", "presentation"), List.of("DEMO", "Presentation"), List.of(
            new HelpContent.Faq("login", "demo", "How do I sign in?", "Use the public DEMO account teste-byx and password byx123123. The profile remains DEMO_USER / USER."),
            new HelpContent.Faq("offline", "demo", "Are prices and balances real?", "No. Every market value is generated locally. The DEMO cannot connect an account or send orders."),
            new HelpContent.Faq("language", "presentation", "How do I change the language?", "Choose Portuguese (Brazil) or English in the language selector. Text changes immediately without signing in again."),
            new HelpContent.Faq("preferences", "presentation", "Which preferences are saved?", "Only language, theme, density and motion preferences are saved in the DEMO presentation profile. Credentials and authority are never saved."),
            new HelpContent.Faq("window", "presentation", "Can I resize the window?", "Use the standard Windows border and title bar to resize, minimize, maximize and restore. Smaller content areas allow scrolling."),
            new HelpContent.Faq("feedback", "demo", "How do I send feedback?", "Submit feedback manually through GitHub Issues in ChamberUS/DEV. Never include private data or credentials.")));
    }

    private static HelpContent.LegalContent legal() {
        List<HelpContent.Section> terms = List.of(
            new HelpContent.Section("evaluation", "DEMO evaluation", "This preview demonstrates presentation only. Fictional data is not financial information or investment advice."),
            new HelpContent.Section("unavailable", "Unavailable capabilities", "Service, admin access, research, wallets, signing, custody, transactions and broadcasts are unavailable."));
        List<HelpContent.Section> privacy = List.of(
            new HelpContent.Section("preferences", "Presentation preferences", "Only language, theme, density and motion preferences are saved in the DEMO presentation profile. Credentials and authority are never saved."),
            new HelpContent.Section("feedback", "Feedback", "Submit feedback manually through GitHub Issues in ChamberUS/DEV. Never include private data or credentials."));
        return new HelpContent.LegalContent("LEGAL_PLACEHOLDER", "0.1.0-alpha.2", "2026-10-11", terms, privacy);
    }

    private VBox page(String title, String subtitle) {
        VBox page = Kit.reading(14, 1240);
        page.getChildren().add(Kit.header(title, subtitle, ByxBadge.data(ByxBadge.Data.DEMO_DATA)));
        return page;
    }

    private FlowPane links(String[] labels, String[] routes) {
        FlowPane links = new FlowPane(8, 8);
        for (int i = 0; i < labels.length; i++) {
            String route = routes[i];
            ByxButton button = new ByxButton(labels[i], ByxButton.Variant.SECONDARY, motion);
            button.setOnAction(e -> navigate.accept(route));
            links.getChildren().add(button);
        }
        return links;
    }

    private static VBox stat(String title, String value) {
        Label number = LocaleView.literal(Fx.label(value, "byx-markets-big"));
        VBox panel = Kit.panel(title, number);
        panel.setMinWidth(160);
        return panel;
    }

    private static Node table(String[] headers, List<String[]> rows) {
        TableView<String[]> table = TTable.of(headers, rows, "", 230);
        table.getStyleClass().add("byx-desk-table");
        table.setMinWidth(760);
        table.setFixedCellSize(36);
        for (TableColumn<String[], ?> column : table.getColumns()) {
            @SuppressWarnings("unchecked") TableColumn<String[], String> stringColumn = (TableColumn<String[], String>) column;
            stringColumn.setMinWidth(120);
            boolean copyState = "State".equals(stringColumn.getText());
            stringColumn.setCellFactory(ignored -> {
                TableCell<String[], String> cell = new TableCell<>() {
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
                };
                return copyState ? cell : LocaleView.literal(cell);
            });
        }
        ScrollPane overflow = new ScrollPane(table);
        overflow.getStyleClass().add("byx-desk-scroll");
        overflow.setFitToWidth(true);
        overflow.setFitToHeight(true);
        overflow.setPrefViewportHeight(230);
        overflow.setMinSize(0, 250);
        overflow.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        overflow.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return overflow;
    }

    private static View read(VBox page, String id) {
        ScrollPane scroll = Kit.scroll(page);
        scroll.setId(id);
        return new View() {
            public Node node() { return scroll; }
            public void onSnapshot(Snapshot ignored) { }
        };
    }

    /** Keeps the approved view and its panels intact while permitting a smaller native window. */
    public static View fit(View source, double minWidth, double minHeight) {
        if (!(source.node() instanceof Region region)) throw new IllegalArgumentException("Region required");
        region.setMinSize(minWidth, minHeight);
        ScrollPane scroll = new ScrollPane(region);
        scroll.getStyleClass().add("byx-desk-scroll");
        scroll.setMinSize(0, 0);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return new View() {
            public Node node() { return scroll; }
            public void onSnapshot(Snapshot snapshot) { source.onSnapshot(snapshot); }
            public void onShow() { source.onShow(); }
            public void onHide() { source.onHide(); }
            public void dispose() { source.dispose(); }
        };
    }
}