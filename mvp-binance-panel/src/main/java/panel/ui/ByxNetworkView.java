package panel.ui;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.net.URI;
import panel.app.AppContext;
import panel.model.*;

public final class ByxNetworkView implements View {
    private final AppContext ctx;
    private final VBox body = Ui.page();
    private final ScrollPane root = Ui.scroll(body);
    private final VBox status = new VBox(0);
    private final VBox technical = new VBox(8);
    private final javafx.scene.layout.StackPane circles = new javafx.scene.layout.StackPane();
    private final Label nodeState = Ui.label("Awaiting node", "card-title");
    private final VBox admin = new VBox(8);
    private final VBox observed = new VBox(8);
    private final TableView<String[]> blocks = panel.ui.trader.TTable.of(new String[] {"Height", "Hash", "Txs", "Block time"},
            java.util.List.of(), "Recent history unavailable · waiting for the node", 180);
    private final Label message = Ui.label("", "muted");
    private final Timeline timer;
    private boolean controlsVisible;
    public ByxNetworkView(AppContext ctx) {
        this.ctx = ctx;
        var states = new javafx.scene.layout.FlowPane(8, 8,
                Ui.badge("HEALTHY", "ok"), Ui.badge("SYNCING", "info"), Ui.badge("STALE", "warn"),
                Ui.badge("DEGRADED", "warn"), Ui.badge("OFFLINE", "bad"), Ui.badge("IDENTITY MISMATCH", "bad"));
        var configuration = new TitledPane("Admin · LOCALNET configuration", admin);
        configuration.setExpanded(false);
        for (int radius : new int[] {50, 30, 10}) {
            javafx.scene.shape.Circle circle = new javafx.scene.shape.Circle(radius);
            circle.getStyleClass().add(radius == 30 ? "network-inner" : radius == 10 ? "network-core" : "network-outer");
            circle.setStrokeWidth(3); circles.getChildren().add(circle);
        }
        circles.setMinSize(120, 120); circles.setMaxSize(120, 120); circles.setId("network-waiting-circles");
        VBox emblem = new VBox(8, circles, nodeState); emblem.setAlignment(javafx.geometry.Pos.CENTER); emblem.setMinWidth(200);
        var node = new javafx.scene.layout.HBox(24, emblem, status); node.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(status, javafx.scene.layout.Priority.ALWAYS); node.getStyleClass().add("card");
        VBox main = new VBox(14, node,
                Ui.card("Latest observed block · history unavailable", blocks));
        TitledPane networkDetails = new TitledPane("Network details", technical); networkDetails.setExpanded(false);
        VBox side = new VBox(14, Ui.card("Network states · reference", states),
                Ui.card("Observed wallet · no ownership claim", observed), networkDetails, configuration, message);
        var columns = Ui.columns(main, side);
        columns.getColumnConstraints().get(0).setPercentWidth(-1);
        columns.getColumnConstraints().get(0).setHgrow(javafx.scene.layout.Priority.ALWAYS);
        columns.getColumnConstraints().get(1).setPercentWidth(-1);
        columns.getColumnConstraints().get(1).setMinWidth(380);
        columns.getColumnConstraints().get(1).setPrefWidth(380);
        columns.getColumnConstraints().get(1).setMaxWidth(380);
        body.getChildren().addAll(Ui.testEnvironment(), columns);
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> render()));
        timer.setCycleCount(Timeline.INDEFINITE);
        root.visibleProperty().addListener((o, a, visible) -> { if (visible) { render(); timer.play(); } else timer.stop(); });
        root.sceneProperty().addListener((o, a, scene) -> { if (scene == null) timer.stop(); });
        render();
    }
    private void render() {
        ByxSnapshot s = ctx.byx.snapshot();
        boolean awaiting = s.connection().equals("UNKNOWN") && s.height() == null;
        nodeState.setText(awaiting ? "Awaiting node" : s.connection());
        ctx.motion.reference.setBreathing(circles, awaiting, panel.motion.MotionTokens.LIVE, panel.motion.MotionTokens.CSS_EASE_IN_OUT);
        status.getChildren().setAll(networkRow("Chain ID", s.chainId()), networkRow("Block height", s.height()),
                networkRow("Latest block", panel.util.Fmt.dateTime(s.blockTime())), networkRow("Block freshness", s.freshness()),
                networkRow("RPC health", s.connection()), networkRow("Sync", s.syncing() == null ? "UNKNOWN" : s.syncing().toString()));
        technical.getChildren().setAll(Ui.kv("Source / Network", s.source() + " / " + s.environment()),
                Ui.kv("Bot execution", s.execution()), Ui.kv("Connection / Identity", s.connection() + " / " + s.identity()),
                Ui.kv("Freshness / Syncing", s.freshness() + " / " + (s.syncing() == null ? "UNKNOWN" : s.syncing())),
                Ui.kv("Chain ID", s.chainId()), Ui.kv("Block height / time", panel.util.Fmt.text(s.height()) + " / " + panel.util.Fmt.dateTime(s.blockTime())),
                Ui.kv("Observed address", s.address()), Ui.kv("Balance", s.formattedBalance()),
                Ui.kv("Last successful update", s.updatedAt() == null ? "UNKNOWN" : s.updatedAt().toString()),
                Ui.kv("Status", s.message()), Ui.kv("Transactions", "Unavailable in V1; pagination/indexer not verified"));
        observed.getChildren().setAll(Ui.kv("Address", s.address()), Ui.kv("BYX balance · TEST", s.formattedBalance()));
        panel.ui.trader.TTable.update(blocks, s.height() == null ? java.util.List.of() :
                java.util.List.<String[]>of(new String[] {s.height(), "N/A", "N/A", panel.util.Fmt.dateTime(s.blockTime())}));
        boolean allowed = ctx.adminAccess.hasValidAdminSession();
        if (allowed != controlsVisible) {
            admin.getChildren().clear(); controlsVisible = allowed;
            if (allowed) controls();
        }
        message.setText(allowed ? "" : "Endpoint and technical details require an active AdminSession in Research.");
    }
    private static Node networkRow(String key, String value) {
        var row = new javafx.scene.layout.HBox(12, Ui.label(key, "muted"), Ui.spacer(), Ui.label(panel.util.Fmt.text(value), "mono"));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT); row.getStyleClass().add("reference-row"); return row;
    }
    private void controls() {
        ByxConfig current = ctx.byx.details();
        TextField endpoint = new TextField(current == null ? "" : current.endpoint().toString());
        TextField rpc = new TextField(current == null ? "" : current.rpcEndpoint().toString());
        TextField chain = new TextField(current == null ? "" : current.expectedChainId());
        TextField genesis = new TextField(current == null ? "" : current.genesisFingerprint());
        TextField address = new TextField(current == null ? "" : current.observedAddress());
        ComboBox<String> environment = new ComboBox<>(); environment.getItems().add("LOCALNET");
        if (current != null) environment.setValue(current.environment());
        Label error = Ui.label("", "muted");
        Button apply = Ui.button("Verify local test network", "primary");
        apply.setOnAction(e -> {
            try {
                ctx.byx.configure(new ByxConfig(URI.create(endpoint.getText().trim()), URI.create(rpc.getText().trim()),
                        environment.getValue(), chain.getText().trim(), genesis.getText().trim(),
                        "ubyx", "BYX", 6, "BANK_METADATA", address.getText().trim()));
                ctx.byx.refresh(); error.setText("Verification pending");
            } catch (RuntimeException ex) { error.setText(ex.getMessage()); }
        });
        admin.getChildren().setAll(Ui.card("Admin · Local test configuration (session only)",
                Ui.kvNode("REST endpoint", endpoint), Ui.kvNode("RPC endpoint", rpc), Ui.kvNode("Environment", environment),
                Ui.kvNode("Expected chain ID", chain), Ui.kvNode("Genesis SHA-256 (canonical)", genesis),
                Ui.kvNode("Observed address", address), Ui.kv("Asset / decimal source", "ubyx → BYX / 6 / BANK_METADATA"), apply, error));
    }
    public Node node() { return root; }
    public void onSnapshot(Snapshot ignored) { render(); }
}
