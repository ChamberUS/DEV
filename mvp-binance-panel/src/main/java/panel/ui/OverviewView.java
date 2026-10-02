package panel.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.JobRecord;
import panel.model.JobState;
import panel.model.PipelineStage;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.service.PipelineService;
import panel.ui.motion.Skeleton;
import panel.util.Fmt;

public class OverviewView extends PageView {
    public OverviewView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        if (s.loading) {
            VBox sk = new VBox(14, Skeleton.bar(260, 30), Skeleton.bar(1100, 56), Skeleton.bar(1100, 110), Skeleton.bar(1100, 220));
            page.getChildren().add(sk);
            return;
        }
        boolean running = ctx.research.labelsRunning();
        List<PipelineStage> stages = PipelineService.stages(s, running);

        VBox head = new VBox(2, Ui.label("MVP Binance", "h0"), Ui.label("Research Control Center", "muted-lg"));
        HBox meta = new HBox(8,
                Ui.label("Dataset", "muted"), Ui.label(Fmt.shortHash(s.datasetId), "mono"),
                Ui.label("Partition", "muted"), Ui.badge(s.partition, "info"),
                Ui.label("Schema", "muted"), Ui.badge(s.labelSchema == null ? "N/A" : s.labelSchema, s.labelSchema == null ? "muted" : "purple"),
                Ui.label("Backend", "muted"), Ui.badge(s.backendOnline ? "ONLINE" : "OFFLINE", s.backendOnline ? "ok" : "bad"));
        meta.setAlignment(Pos.CENTER_RIGHT);
        HBox top = new HBox(16, head, Ui.spacer(), meta);
        top.setAlignment(Pos.CENTER_LEFT);

        page.getChildren().addAll(top, guardStrip(), currentStage(s, running), pipeline(stages), cards(s, running));
    }

    static HBox guardStrip() {
        HBox g = new HBox(10, Ui.label("RESEARCH GUARD", "card-title"),
                guard("TRAIN", "ACTIVE", "ok"), guard("VALIDATION", "LOCKED", "bad"), guard("FINAL HOLDOUT", "SEALED", "bad"));
        g.setAlignment(Pos.CENTER_LEFT);
        g.getStyleClass().add("guard-strip");
        return g;
    }

    private static HBox guard(String name, String state, String tone) {
        HBox b = new HBox(6, Ui.label(name, "guard-name"), Ui.badge(state, tone));
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    private HBox currentStage(Snapshot s, boolean running) {
        HBox b = new HBox(12, Ui.label("CURRENT STAGE", "card-title"), Ui.label(PipelineService.currentStage(s, running), "stage-name"),
                Ui.badge(running ? "RUNNING" : "IDLE", running ? "warn" : "muted"));
        b.setAlignment(Pos.CENTER_LEFT);
        b.getStyleClass().add("card");
        return b;
    }

    private VBox pipeline(List<PipelineStage> stages) {
        javafx.scene.layout.TilePane grid = new javafx.scene.layout.TilePane(6, 6);
        grid.setPrefTileWidth(150);
        for (int i = 0; i < stages.size(); i++) {
            PipelineStage st = stages.get(i);
            javafx.scene.control.Label summary = Ui.label(st.summary(), "muted");
            summary.setWrapText(true);
            VBox tile = new VBox(4, Ui.label(st.title(), "stage-title"), Ui.label(st.state().icon + "  " + st.state().name(), "stage-" + st.state().tone), summary);
            tile.getStyleClass().addAll("stage", "stage-border-" + st.state().tone);
            tile.setOnMouseClicked(e -> ctx.navigate.accept(st.target()));
            Ui.hoverLift(tile);
            grid.getChildren().add(tile);
        }
        return Ui.card("Research pipeline", grid);
    }

    private FlowPane cards(Snapshot s, boolean running) {
        FlowPane flow = new FlowPane(14, 14);
        var c = s.capture;
        flow.getChildren().add(sized(Ui.card("Data capture",
                Ui.kvNode("Status", Ui.badge(c.recorder() == null ? "N/A" : c.recorder(), c.recorder() == null ? "muted" : "ok")),
                Ui.kv("Current session", c.currentSession()),
                Ui.kv("Last event", Fmt.dateTime(c.lastEvent())),
                Ui.kv("Uptime", c.sessionDuration()),
                Ui.kv("Events", Fmt.num(c.events())),
                Ui.kv("Disk usage", c.diskWritten()))));
        flow.getChildren().add(sized(Ui.card("Train dataset",
                Ui.metric("Sessions", Fmt.num(s.sessionCount), "info"),
                Ui.kv("Anchors", Fmt.num(s.anchorCount)),
                Ui.kv("Checkpoint rows", Fmt.num(s.checkpointSamples)),
                Ui.kv("Dataset hash", Fmt.shortHash(s.datasetId)))));
        flow.getChildren().add(sized(Ui.card("Pure mid labels",
                Ui.metric("Complete", Fmt.ratio(s.labelDone, s.labelsTotalSessions()), s.labelState.tone),
                Ui.kv("Schema", s.labelSchema),
                Ui.kv("Horizons", s.horizons.isEmpty() ? null : String.valueOf(s.horizons.size())),
                Ui.kv("Total labels", Fmt.num(s.labelTotal)),
                Ui.kvNode("State", Ui.stateBadge(running && s.labelState != StageState.READY ? StageState.RUNNING : s.labelState)))));
        flow.getChildren().add(sized(Ui.card("Features",
                Ui.kvNode("Status", Ui.stateBadge(s.featureState)),
                Ui.kv("Complete", Fmt.ratio(s.featureDone, s.labelsTotalSessions())),
                Ui.kv("Schema", s.featureSchema),
                Ui.kv("Rows", Fmt.num(s.anchorCount)))));
        long done = s.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        flow.getChildren().add(sized(Ui.card("Research",
                Ui.metric("Hypotheses", done + " / " + s.hypotheses.size() + " completed", "purple"),
                Ui.kv("Aggregate labels", s.labelAggregateAvailable ? "Available" : "Not generated"),
                Ui.kv("Frozen spec", s.frozenSpecStatus),
                Ui.kv("Validation readiness", s.validationReadiness),
                Ui.kv("Validation", s.validationStatus), Ui.kv("FINAL_HOLDOUT", s.finalHoldout))));
        long failed = ctx.jobs.jobs.stream().filter(j -> j.state.get() == JobState.FAILED).count();
        Instant last = ctx.jobs.jobs.stream().filter(j -> j.finishedAt.get() != null).map(j -> j.finishedAt.get()).max(Instant::compareTo).orElse(null);
        flow.getChildren().add(sized(Ui.card("System",
                Ui.metric("Running jobs", String.valueOf(ctx.jobs.jobs.stream().filter(JobRecord::active).count() + ctx.jobs.externals.size()), "warn"),
                Ui.kv("External processes", String.valueOf(ctx.jobs.externals.size())),
                Ui.kv("Failed jobs", String.valueOf(failed)),
                Ui.kv("Last completed", last == null ? null : Fmt.dateTime(last)),
                Ui.kv("Warnings", String.valueOf(s.warnings.size())))));
        return flow;
    }

    private static VBox sized(VBox card) {
        card.setPrefWidth(300);
        card.setMinWidth(260);
        return card;
    }
}
