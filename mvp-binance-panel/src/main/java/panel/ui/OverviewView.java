package panel.ui;

import java.util.List;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.JobState;
import panel.model.PipelineStage;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.service.PipelineService;
import panel.util.Fmt;

public class OverviewView extends PageView {
    public OverviewView(AppContext ctx) { super(ctx); }

    @Override protected void build(Snapshot s, VBox page) {
        boolean running = ctx.research.labelsRunning();
        var headline = new HBox(20, new VBox(4, Ui.label("DATASET " + Fmt.shortHash(s.datasetId), "card-title"),
                Ui.label("Research Overview", "stage-title")), Ui.spacer(), guardStrip());
        headline.getStyleClass().add("card");
        headline.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var capture = s.capture;
        long complete = s.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        var metrics = Ui.columns(
                Ui.card("Capture", Ui.metric("Status", Fmt.text(capture.recorder()), "info"), Ui.label("Uptime " + Fmt.text(capture.sessionDuration()), "muted")),
                Ui.card("Sessions", Ui.metric("TRAIN", Fmt.num(s.sessionCount), "muted")),
                Ui.card("Anchors", Ui.metric("TRAIN", Fmt.num(s.anchorCount), "muted"), Ui.label(Fmt.num(s.checkpointSamples) + " rows", "muted")),
                Ui.card("Hypotheses", Ui.metric("Complete", s.datasetId == null ? "N/A" : complete + " / " + s.hypotheses.size(), "purple"),
                        Ui.label(Fmt.text(s.frozenSpecStatus), "muted")));
        var dataset = Ui.card("Train dataset", Ui.kv("Label schema", s.labelSchema), Ui.kv("Feature schema", s.featureSchema),
                Ui.kv("Features complete", Fmt.ratio(s.featureDone, s.labelsTotalSessions())), Ui.kv("Labels complete", Fmt.ratio(s.labelDone, s.labelsTotalSessions())));
        VBox sessions = Ui.card("Sessions", Ui.label(s.sessions.isEmpty() ? "No TRAIN sessions reported" : s.sessions.size() + " sessions reported", "muted"));
        var openSessions = Ui.button("Open sessions", "ghost"); openSessions.setOnAction(e -> ctx.navigate.accept("sessions"));
        sessions.getChildren().add(openSessions);
        sessions.setPrefHeight(250);
        var next = Ui.button("Open hypotheses", "primary"); next.setOnAction(e -> ctx.navigate.accept("hypotheses"));
        long failed = ctx.jobs.jobs.stream().filter(j -> j.state.get() == JobState.FAILED).count();
        var logs = Ui.button("Open logs", "ghost"); logs.setOnAction(e -> ctx.navigate.accept("logs"));
        VBox attention = Ui.card("Attention", Ui.badge(s.warnings.size() + " WARNINGS", s.warnings.isEmpty() ? "muted" : "warn"),
                Ui.label(failed + " failed jobs", "muted"), logs);
        VBox environments = Ui.card("Environments", Ui.kv("TRAIN", "Open"), Ui.kv("VALIDATION", s.validationStatus),
                Ui.kv("FINAL_HOLDOUT", s.finalHoldout), Ui.label("Locked environments cannot be opened from this screen.", "muted"));
        var main = new VBox(14, metrics, dataset, sessions);
        var side = new VBox(14, Ui.card("Next allowed step", Ui.label(PipelineService.currentStage(s, running), "stage-title"), next), attention, environments);
        GridPane columns = Ui.columns(main, side);
        columns.getColumnConstraints().get(0).setPercentWidth(75);
        columns.getColumnConstraints().get(1).setPercentWidth(25);
        page.getChildren().addAll(headline, pipeline(PipelineService.stages(s, running), s, running), columns);
    }

    static HBox guardStrip() {
        HBox g = new HBox(10, Ui.label("RESEARCH GUARD", "card-title"),
                Ui.badge("TRAIN OPEN", "ok"), Ui.badge("VALIDATION LOCKED", "bad"), Ui.badge("FINAL HOLDOUT SEALED", "muted"));
        g.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        g.getStyleClass().add("guard-strip");
        return g;
    }

    private VBox pipeline(List<PipelineStage> stages, Snapshot s, boolean running) {
        stages = stages.stream().filter(st -> !java.util.Set.of("sessions", "paper").contains(st.target())).toList();
        GridPane grid = new GridPane(); grid.getStyleClass().add("pipeline-grid");
        for (int i = 0; i < stages.size(); i++) {
            var column = new ColumnConstraints(); column.setPercentWidth(100.0 / stages.size()); column.setMinWidth(0);
            grid.getColumnConstraints().add(column);
            PipelineStage stage = stages.get(i);
            var line = new javafx.scene.layout.Region(); line.getStyleClass().addAll("pipeline-line", "pipeline-" + stage.state().tone);
            String name = stage.target().equals("dataset") ? "Dataset" : stage.target().equals("labels") ? "Labels" : stage.title();
            VBox tile = new VBox(6, line, Ui.label(name, "pipeline-label"));
            tile.setAccessibleText(name + " · " + stage.state().name() + " · " + stage.summary());
            javafx.scene.control.Tooltip.install(tile, new javafx.scene.control.Tooltip(stage.state().name() + " · " + stage.summary()));
            tile.setOnMouseClicked(e -> ctx.navigate.accept(stage.target()));
            grid.add(tile, i, 0);
        }
        return Ui.card("Scientific pipeline · " + PipelineService.currentStage(s, running) + " · " + (running ? "RUNNING" : "IDLE"), grid);
    }
}
