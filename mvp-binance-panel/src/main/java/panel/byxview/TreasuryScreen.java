package panel.byxview;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.model.Snapshot;
import panel.model.TreasuryAsset;
import panel.model.TreasurySnapshot;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * BYX Treasury V2, somente leitura. As quatro classes (REAL VERIFIED, TEST, PAPER, MANUAL_UNVERIFIED) são faixas
 * separadas e nenhum total é calculado: cada saldo aparece na sua unidade e categoria. BYX nunca é convertido para USD.
 * A leitura de cadeia roda fora da thread FX, a cada 30 s com a tela visível; escondida ou descartada, nada roda.
 */
public final class TreasuryScreen implements View {
    private final ByxData data;
    private final ScrollPane scroll;
    private final VBox lanes = new VBox(14);
    private final VBox allocation = new VBox(0);
    private final Label totalBadge = ByxBadge.of("VERIFIED TOTAL · —", ByxBadge.Tone.NEUTRAL);
    private final VBox facts = new VBox(0);
    private final Label statement = Kit.muted("");
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(30), e -> refresh()));
    private TreasurySnapshot snapshot;
    private boolean failed;
    private boolean busy;
    private boolean loaded;
    private long generation;

    public TreasuryScreen(ByxData data) {
        this.data = data;
        timer.setCycleCount(Timeline.INDEFINITE);
        VBox right = Kit.panel(null, Kit.titled("Allocation by category", totalBadge), allocation, facts, statement);
        right.setId("treasury-allocation");
        lanes.setId("treasury-lanes");
        lanes.setMinWidth(520);
        lanes.setPrefWidth(520);
        lanes.setMaxWidth(520);
        GridPane grid = new GridPane();
        grid.setHgap(14);
        ColumnConstraints left = new ColumnConstraints(520, 520, 520);
        ColumnConstraints main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        main.setMinWidth(0);
        grid.getColumnConstraints().addAll(left, main);
        grid.add(lanes, 0, 0);
        grid.add(right, 1, 0);
        VBox page = Kit.page(14);
        page.getChildren().add(Kit.environment("LOCALNET", "Development environment", "TEST assets only. Nothing here has real value."));
        page.getChildren().add(grid);
        scroll = Kit.scroll(page);
        scroll.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) {
                refresh();
            }
        });
        render();
    }

    boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    /** Aplica um snapshot já lido (QA e testes; o app só usa {@link #refresh()}). */
    void load(TreasurySnapshot s) {
        snapshot = s;
        failed = false;
        loaded = true;
        render();
    }

    TreasurySnapshot snapshot() {
        return snapshot;
    }

    boolean failed() {
        return failed;
    }

    /** Leitura de cadeia fora da thread FX; resultado tardio (tela escondida, sessão trocada) é descartado. */
    public void refresh() {
        if (busy || !data.sessionActive()) {
            return;
        }
        busy = true;
        long g = generation;
        CompletableFuture.supplyAsync(() -> {
            try {
                return data.refreshTreasury();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).whenComplete((s, error) -> javafx.application.Platform.runLater(() -> {
            if (g != generation) {
                return;
            }
            busy = false;
            loaded = true;
            failed = error != null;
            snapshot = error == null ? s : null;
            render();
        }));
    }

    private void render() {
        lanes.getChildren().clear();
        for (TreasuryModel.LaneState l : TreasuryModel.lanes(snapshot, failed)) {
            Label title = Fx.label(l.lane().title, "byx-section-title-sm");
            VBox copy = new VBox(4, title, Fx.label(l.lane().description, "byx-desk-secondary"));
            VBox value = new VBox(6, ByxBadge.of(l.badge(), l.tone()));
            value.setAlignment(Pos.CENTER_RIGHT);
            if (l.lines().isEmpty()) {
                value.getChildren().add(Fx.label(NetworkModel.NONE, "byx-big", "dim"));
            } else {
                l.lines().forEach(t -> value.getChildren().add(Fx.label(t, "byx-mono")));
            }
            HBox lane = new HBox(16, copy, Fx.spacer(), value);
            lane.setAlignment(Pos.CENTER_LEFT);
            lane.getStyleClass().addAll("byx-lane", l.lane().name().toLowerCase(java.util.Locale.ROOT));
            lane.setAccessibleText(l.lane().title + ": " + l.badge());
            lanes.getChildren().add(lane);
        }
        allocation.getChildren().clear();
        allocation.getChildren().add(headRow());
        for (TreasuryAsset.Category c : TreasuryAsset.Category.values()) {
            List<TreasuryAsset> assets = snapshot == null ? List.of() : snapshot.assets().stream().filter(a -> a.category() == c).toList();
            if (assets.isEmpty()) {
                allocation.getChildren().add(tableRow(TreasuryModel.label(c), ByxBadge.of("NOT CONFIGURED", ByxBadge.Tone.NEUTRAL), NetworkModel.NONE));
            }
            for (TreasuryAsset a : assets) {
                allocation.getChildren().add(tableRow(TreasuryModel.label(c),
                        ByxBadge.of(TreasuryModel.sourceBadge(a) + (a.testOnly() ? " · TEST" : ""), TreasuryModel.sourceTone(a.source())),
                        TreasuryModel.amount(a)));
            }
        }
        facts.getChildren().clear();
        if (snapshot != null) {
            facts.getChildren().addAll(Kit.row("Chain", snapshot.chainState() + " · " + Fmt.dateTime(snapshot.asOf()), true),
                    Kit.row("Active recorded grants", Integer.toString(snapshot.activeGrants()), true),
                    Kit.row("Observed allowance consumption", new BigDecimal(snapshot.observedConsumptionUbyx(), 6).toPlainString() + " BYX (TEST)", true));
            facts.getChildren().add(Kit.dim(snapshot.consumptionStatus()));
        } else {
            facts.getChildren().add(Kit.row("Chain", failed ? "UNAVAILABLE · offline or stale" : loaded ? "NOT CONFIGURED" : "NOT CONFIGURED · not read yet", false));
        }
        Fx.text(statement, "No real asset source is configured, so no total, backing or conversion is shown. BYX is never valued in USD here. "
                + "Manual entries are unverified and application fees, network gas and exchange fees stay separate. "
                + "No backing, redemption or yield promise.");
    }

    private static Node headRow() {
        HBox h = new HBox(12, cell("Category", "byx-table-head", 170), cell("Source", "byx-table-head", 190), cell("Value", "byx-table-head", 0));
        h.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));
        return h;
    }

    private static Node tableRow(String category, Label source, String value) {
        Label cat = Fx.label(category, "byx-table-cell");
        cat.setMinWidth(170);
        cat.setPrefWidth(170);
        HBox src = new HBox(source);
        src.setMinWidth(190);
        src.setPrefWidth(190);
        src.setAlignment(Pos.CENTER_LEFT);
        Label v = Fx.label(value, "byx-table-cell", "mono");
        HBox r = new HBox(12, cat, src, v);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("byx-table-row");
        return r;
    }

    private static Label cell(String text, String cls, double width) {
        Label l = Fx.label(text, cls);
        if (width > 0) {
            l.setMinWidth(width);
            l.setPrefWidth(width);
        }
        return l;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override
    public void onShow() {
        generation++;
        busy = false;
        refresh();
        timer.play();
    }

    @Override
    public void onHide() {
        generation++;
        busy = false;
        timer.stop();
    }

    public void dispose() {
        onHide();
        snapshot = null;
    }
}
