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
    private final VBox status = new VBox(8);
    private final VBox admin = new VBox(8);
    private final Label message = Ui.label("", "muted");
    private final Timeline timer;
    private boolean controlsVisible;
    public ByxNetworkView(AppContext ctx) {
        this.ctx = ctx;
        body.getChildren().addAll(Ui.label("BYX Network", "h1"),
                Ui.label("LOCALNET / ATIVOS DE TESTE / SEM VALOR FINANCEIRO", "muted"),
                Ui.label("Read-only · Observed address is not proof of ownership · No private keys", "muted"),
                status, admin, message);
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> render()));
        timer.setCycleCount(Timeline.INDEFINITE);
        root.visibleProperty().addListener((o, a, visible) -> { if (visible) { render(); timer.play(); } else timer.stop(); });
        root.sceneProperty().addListener((o, a, scene) -> { if (scene == null) timer.stop(); });
        render();
    }
    private void render() {
        ByxSnapshot s = ctx.byx.snapshot();
        status.getChildren().setAll(Ui.kv("Source / Network", s.source() + " / " + s.environment()),
                Ui.kv("Bot execution", s.execution()), Ui.kv("Connection / Identity", s.connection() + " / " + s.identity()),
                Ui.kv("Freshness / Syncing", s.freshness() + " / " + (s.syncing() == null ? "UNKNOWN" : s.syncing())),
                Ui.kv("Chain ID", s.chainId()), Ui.kv("Block height / time", s.height() + " / " + s.blockTime()),
                Ui.kv("Observed address", s.address()), Ui.kv("Balance", s.formattedBalance()),
                Ui.kv("Last successful update", s.updatedAt() == null ? "UNKNOWN" : s.updatedAt().toString()),
                Ui.kv("Status", s.message()), Ui.kv("Transactions", "Unavailable in V1; pagination/indexer not verified"));
        boolean allowed = ctx.adminAccess.hasValidAdminSession();
        if (allowed != controlsVisible) {
            admin.getChildren().clear(); controlsVisible = allowed;
            if (allowed) controls();
        }
        message.setText(allowed ? "" : "Endpoint and technical details require an active AdminSession in Research.");
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
