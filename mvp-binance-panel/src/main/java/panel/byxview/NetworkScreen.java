package panel.byxview;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.v2.Kit;

/**
 * BYX Network V2. Estado, identidade, altura, bloco, frescor e sync vêm do snapshot real; o que o nó não informa é
 * "—". O movimento representa atividade real: aneis respiram só em AWAITING NODE e SYNCING, um único loop; HEALTHY é
 * estável, OFFLINE/STALE/DEGRADED/IDENTITY MISMATCH não têm movimento. Escondida ou descartada: zero loops e zero timers.
 */
public final class NetworkScreen implements View {
    private final ByxData data;
    private final MotionService motion;
    private final Clock clock;
    private final ScrollPane scroll;
    private final StackPane rings = new StackPane();
    private final Label stateText = Fx.label("", "byx-net-state");
    private final KvRow chain = new KvRow("Chain ID");
    private final KvRow height = new KvRow("Block height");
    private final KvRow latest = new KvRow("Latest block");
    private final KvRow fresh = new KvRow("Block freshness");
    private final KvRow rpc = new KvRow("RPC health");
    private final KvRow sync = new KvRow("Sync");
    private final KvRow env = new KvRow("Environment");
    private final KvRow identity = new KvRow("Network identity");
    private final Label blocksBadge = ByxBadge.of("NO FEED", ByxBadge.Tone.NEUTRAL);
    private final List<Label> cells = new ArrayList<>();
    private final Label blocksNote = Kit.muted("Blocks appear when the LOCALNET node answers.");
    private final List<Label> legend = new ArrayList<>();
    private final Label observedAddress = Kit.muted("No wallet linked");
    private final Label observedBalance = ByxBadge.of("BALANCE · —", ByxBadge.Tone.NEUTRAL);
    private final VBox adminBody = new VBox(10);
    private final Label adminBadge = ByxBadge.of("PERMISSION REQUIRED", ByxBadge.Tone.INFO);
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> render()));
    private NetworkModel.State state;
    private boolean visible;
    private boolean adminShown;
    private ByxSnapshot last;

    public NetworkScreen(MotionService motion, Clock clock, ByxData data) {
        this.motion = motion;
        this.clock = clock;
        this.data = data;
        timer.setCycleCount(Timeline.INDEFINITE);

        for (int radius : new int[] {50, 30, 10}) {
            Circle c = new Circle(radius);
            if (radius == 10) {
                c.getStyleClass().add("byx-net-core");
            } else {
                c.getStyleClass().add("byx-net-ring");
                if (radius == 30) {
                    c.getStyleClass().add("mid");
                }
            }
            rings.getChildren().add(c);
        }
        rings.getStyleClass().add("byx-net-rings");
        rings.setId("network-rings");
        rings.setMinSize(120, 120);
        rings.setMaxSize(120, 120);
        VBox emblem = new VBox(8, rings, stateText);
        emblem.setAlignment(Pos.CENTER);
        emblem.setMinWidth(200);
        VBox facts = new VBox(0, chain, height, latest, fresh, rpc, sync, env, identity);
        HBox nodeCard = new HBox(24, emblem, facts);
        nodeCard.setAlignment(Pos.CENTER_LEFT);
        nodeCard.getStyleClass().add("byx-panel");
        nodeCard.setId("network-node");
        HBox.setHgrow(facts, Priority.ALWAYS);

        GridPane table = new GridPane();
        table.setVgap(0);
        table.setHgap(12);
        String[] heads = {"Height", "Hash", "Txs", "Age"};
        int[] widths = {110, 0, 110, 110};
        for (int c = 0; c < 4; c++) {
            Label h = Fx.label(heads[c], "byx-table-head");
            table.add(h, c, 0);
            Label cell = Fx.label(NetworkModel.NONE, "byx-table-cell", "mono");
            cells.add(cell);
            table.add(cell, c, 1);
            ColumnConstraints cc = new ColumnConstraints();
            if (widths[c] == 0) {
                cc.setHgrow(Priority.ALWAYS);
            } else {
                cc.setMinWidth(widths[c]);
                cc.setPrefWidth(widths[c]);
            }
            table.getColumnConstraints().add(cc);
        }
        VBox blocks = Kit.panel(null, Kit.titled("Recent blocks", blocksBadge), table, blocksNote);
        blocks.setId("network-blocks");
        VBox left = new VBox(14, nodeCard, blocks);

        FlowPane states = new FlowPane(8, 8);
        for (NetworkModel.State s : NetworkModel.LEGEND) {
            Label b = ByxBadge.of(s.text, s.tone);
            b.setUserData(s);
            legend.add(b);
            states.getChildren().add(b);
        }
        VBox legendPanel = Kit.panel("Network states", states);
        VBox observed = Kit.panel("Observed wallet", observedAddress, observedBalance);
        observed.setId("network-observed");
        VBox admin = Kit.panel(null, Kit.titled("LOCALNET connection", adminBadge), adminBody);
        admin.setId("network-admin");
        VBox right = new VBox(14, legendPanel, observed, admin);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        ColumnConstraints main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        main.setMinWidth(0);
        ColumnConstraints side = new ColumnConstraints(380, 380, 380);
        grid.getColumnConstraints().addAll(main, side);
        HBox env = Kit.environment("LOCALNET", "Development environment", "TEST assets only. Nothing here has real value.");
        grid.add(env, 0, 0, 2, 1);
        grid.add(left, 0, 1);
        grid.add(right, 1, 1);
        VBox page = Kit.page(14);
        page.getChildren().add(grid);
        scroll = Kit.scroll(page);
        render();
    }

    NetworkModel.State state() {
        return state;
    }

    boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    Node rings() {
        return rings;
    }

    private void render() {
        ByxSnapshot s = data.network();
        NetworkModel.State st = NetworkModel.state(s);
        boolean changed = !s.equals(last);
        last = s;
        if (st != state) {
            state = st;
            Fx.text(stateText, st.text);
            Fx.cls(rings, "byx-net-off", st == NetworkModel.State.OFFLINE);
            Fx.cls(rings, "byx-net-bad", st == NetworkModel.State.IDENTITY_MISMATCH);
            Fx.cls(rings, "byx-net-warn", st == NetworkModel.State.STALE || st == NetworkModel.State.DEGRADED);
            for (Label b : legend) {
                Fx.cls(b, "byx-legend-dim", st != NetworkModel.State.AWAITING_NODE && b.getUserData() != st);
            }
            stateText.setAccessibleText("Network state: " + st.text);
        }
        applyLoop();
        if (changed) {
            chain.set(NetworkModel.value(s.chainId()), true, null);
            height.set(NetworkModel.value(s.height()), true, null);
            latest.set(NetworkModel.block(s), true, null);
            fresh.set(NetworkModel.freshness(s), false, s.freshness().equals("STALE") ? "warn" : null);
            rpc.set(NetworkModel.rpc(s), false, "OFFLINE".equals(s.connection()) ? "neg" : null);
            sync.set(NetworkModel.sync(s), false, null);
            env.set(NetworkModel.environment(s), false, null);
            identity.set(NetworkModel.identity(s), false, "UNVERIFIED".equals(s.identity()) && "ONLINE".equals(s.connection()) ? "neg" : null);
            boolean hasBlock = s.height() != null;
            Fx.text(blocksBadge, hasBlock ? "LATEST ONLY" : "NO FEED");
            Fx.text(cells.get(0), NetworkModel.value(s.height()));
            Fx.text(cells.get(1), hasBlock ? "Not reported" : NetworkModel.NONE);
            Fx.text(cells.get(2), hasBlock ? "Not reported" : NetworkModel.NONE);
            Fx.text(blocksNote, hasBlock ? "Only the latest observed block is available; the node history is not read."
                    : "Blocks appear when the LOCALNET node answers.");
            Fx.text(observedAddress, s.address() == null ? "No wallet linked" : s.address());
            Fx.cls(observedAddress, "byx-mono", s.address() != null);
            Fx.text(observedBalance, "BALANCE · " + NetworkModel.balance(s));
        }
        Fx.text(cells.get(3), NetworkModel.age(s, clock));
        syncAdmin();
    }

    /** Um loop só, só com atividade real e só com a tela visível. */
    private void applyLoop() {
        boolean on = visible && state != null && state.active();
        motion.reference.setBreathing(rings, on, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
    }

    private void syncAdmin() {
        boolean admin = data.admin();
        if (admin == adminShown) {
            return;
        }
        adminShown = admin;
        adminBody.getChildren().clear();
        adminBadge.setVisible(!admin);
        adminBadge.setManaged(!admin);
        if (!admin) {
            adminBody.getChildren().add(Kit.muted("Endpoint and technical details require an active admin session in Research."));
            return;
        }
        ByxConfig current = data.config();
        ByxField rest = ByxField.text("REST endpoint");
        ByxField rpcField = ByxField.text("RPC endpoint");
        ByxField chainField = ByxField.text("Expected chain ID");
        ByxField genesis = ByxField.text("Genesis SHA-256 (canonical)");
        ByxField address = ByxField.text("Observed address");
        if (current != null) {
            rest.input().setText(current.endpoint().toString());
            rpcField.input().setText(current.rpcEndpoint().toString());
            chainField.input().setText(current.expectedChainId());
            genesis.input().setText(current.genesisFingerprint());
            address.input().setText(current.observedAddress());
        }
        ByxButton apply = new ByxButton("Verify local test network", ByxButton.Variant.SECONDARY, motion);
        apply.setOnAction(e -> {
            try {
                data.configure(new ByxConfig(URI.create(rest.input().getText().trim()), URI.create(rpcField.input().getText().trim()),
                        "LOCALNET", chainField.input().getText().trim(), genesis.input().getText().trim(), "ubyx", "BYX", 6,
                        "BANK_METADATA", address.input().getText().trim()));
                rest.setError(null);
                data.refreshNetwork();
            } catch (RuntimeException ex) {
                rest.setError(ex.getMessage() == null ? "Invalid configuration" : ex.getMessage());
            }
        });
        adminBody.getChildren().addAll(Kit.muted("Session only. LOCALNET read-only node; nothing is signed or sent."), rest, rpcField, chainField,
                genesis, address, apply);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        if (visible) {
            render();
        }
    }

    @Override
    public void onShow() {
        visible = true;
        render();
        timer.play();
    }

    @Override
    public void onHide() {
        visible = false;
        timer.stop();
        applyLoop();
    }

    public void dispose() {
        onHide();
    }
}
