package panel.ui;

import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.adapter.CommandSpec;
import panel.app.AppContext;
import panel.model.DataSource;
import panel.model.HorizonStat;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.util.Fmt;

public class LabelsView extends PageView {
    private int selectedTab;

    public LabelsView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        page.getChildren().add(Ui.pageHeader("Labels", "Two separate families: price predictability vs economic executability"));
        TabPane tabs = new TabPane(new Tab("Pure Mid", pureMid(s)), new Tab("Execution", execution()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(selectedTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> selectedTab = b.intValue());
        page.getChildren().add(tabs);
    }

    private VBox pureMid(Snapshot s) {
        int total = s.labelsTotalSessions();
        boolean blocked = s.source == DataSource.MOCK || ctx.research.labelsRunning() || ctx.jobs.running();
        Button status = Ui.button("Label status", "ghost");
        status.setOnAction(e -> Dialogs.run(ctx, CommandSpec.LABEL_STATUS, null));
        Button run = Ui.button("Generate labels (TRAIN)", "primary");
        run.setOnAction(e -> Dialogs.run(ctx, CommandSpec.LABEL_RUN, null));
        Button agg = Ui.button("Aggregate", "ghost");
        agg.setOnAction(e -> Dialogs.run(ctx, CommandSpec.LABEL_AGGREGATE, null));
        status.setDisable(s.source == DataSource.MOCK);
        run.setDisable(blocked || s.labelState == StageState.READY);
        agg.setDisable(blocked || s.labelState != StageState.READY);
        if (ctx.research.labelsRunning()) {
            run.setTooltip(new Tooltip("A label job is already running."));
        }
        HBox actions = new HBox(8, Ui.label("PURE FORWARD MID LABELS", "card-title"), Ui.spacer(), status, run, agg);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        FlowPane kpis = new FlowPane(14, 14);
        kpis.getChildren().addAll(
                Ui.card("Schema", Ui.label(Fmt.text(s.labelSchema), "metric", "metric-purple", "metric-sm"), Ui.kv("Dataset", Fmt.shortHash(s.datasetId)), Ui.kv("Config hash", Fmt.shortHash(s.hashes.get("label config")))),
                Ui.card("Sessions complete", Ui.metric("", Fmt.ratio(s.labelDone, total), s.labelState.tone), Ui.kv("Missing", Fmt.num(s.labelMissing)), Ui.kv("Invalid / stale", Fmt.num(s.labelInvalid))),
                Ui.card("Volume", Ui.kv("Anchors", Fmt.num(s.labelAnchors)), Ui.kv("Total labels", Fmt.num(s.labelTotal)), Ui.kv("Aggregate", s.labelAggregateAvailable ? "Available" : "Not generated")));
        kpis.getChildren().forEach(n -> ((VBox) n).setPrefWidth(300));

        TableView<HorizonStat> table = new TableView<>(FXCollections.observableArrayList(s.horizonStats));
        table.getColumns().add(col("Horizon", h -> Fmt.horizon(h.horizonMs())));
        table.getColumns().add(col("Valid", h -> Fmt.num(h.valid())));
        table.getColumns().add(col("Invalid", h -> Fmt.num(h.invalid())));
        table.getColumns().add(col("Coverage", h -> h.coverage() == null ? Fmt.NA : String.format("%.2f%%", h.coverage() * 100)));
        table.getColumns().add(col("Median timing err", h -> ms(h.medianErrorMs())));
        table.getColumns().add(col("P95", h -> ms(h.p95ErrorMs())));
        table.getColumns().add(col("P99", h -> ms(h.p99ErrorMs())));
        table.getColumns().add(col("Max", h -> ms(h.maxErrorMs())));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(48 + 34 * Math.max(3, s.horizonStats.size()));
        table.setPlaceholder(Ui.label("No label data yet", "muted"));
        VBox tableCard = Ui.card("Per-horizon aggregate", table);
        boolean partial = s.horizonStats.stream().anyMatch(HorizonStat::partial);
        if (partial) {
            tableCard.getChildren().add(Ui.label("Partial: counts from " + s.labelDone + " / " + total + " completed sessions' metadata. Timing error stats come with label-aggregate.", "muted"));
        }

        List<String> cats = s.horizonStats.stream().map(h -> Fmt.horizon(h.horizonMs())).toList();
        boolean hasCoverage = s.horizonStats.stream().anyMatch(h -> h.coverage() != null);
        boolean hasError = s.horizonStats.stream().anyMatch(h -> h.medianErrorMs() != null);
        FlowPane charts = new FlowPane(14, 14);
        charts.getChildren().addAll(
                chart("Coverage by horizon", hasCoverage ? Charts.bar("coverage %", cats, s.horizonStats.stream().map(h -> h.coverage() == null ? null : h.coverage() * 100).toList()) : null),
                chart("Temporal error by horizon (median)", hasError ? Charts.bar("ms", cats, s.horizonStats.stream().map(HorizonStat::medianErrorMs).toList()) : null),
                chart("Forward return distribution", null),
                chart("Absolute forward movement", null),
                chart("Positive / Negative / Zero", null));
        return new VBox(14, actions, kpis, tableCard, charts);
    }

    private static VBox chart(String title, javafx.scene.Node chart) {
        VBox c = Ui.card(title, chart != null ? chart : Ui.emptyState("Waiting for label-aggregate output"));
        c.setPrefWidth(440);
        return c;
    }

    private static String ms(Double v) {
        return v == null ? Fmt.NA : String.format("%.1f ms", v);
    }

    private static TableColumn<HorizonStat, String> col(String t, Function<HorizonStat, String> f) {
        TableColumn<HorizonStat, String> c = new TableColumn<>(t);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(f.apply(d.getValue())));
        return c;
    }

    private VBox execution() {
        VBox v = new VBox(14,
                Ui.card("Economic execution labels",
                        Ui.label("Objective: economic executability. Not price predictability.", "muted"),
                        Ui.kv("Includes", "fees · VWAP · spread · slippage · latency · LONG/SHORT · notional"),
                        Ui.kvNode("Status", Ui.badge("NOT AVAILABLE IN PANEL", "muted")),
                        Ui.label("Execution labels live in the checkpoint outputs (long/short forward labels). The panel has no command to generate them in this phase and does not mix them with Pure Mid.", "muted")));
        return v;
    }
}
