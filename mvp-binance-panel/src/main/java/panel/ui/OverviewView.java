package panel.ui;

import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import panel.app.AppContext;
import panel.model.*;
import panel.motion.MotionTokens;
import panel.service.PipelineService;
import panel.util.Fmt;

public class OverviewView extends PageView {
    private final Label datasetId = Ui.label("DATASET N/A", "card-title");
    private final Label stage = Ui.badge("", "purple");
    private final List<Label> metricValues = new ArrayList<>();
    private final List<Label> metricNotes = new ArrayList<>();
    private final List<Label> datasetValues = new ArrayList<>();
    private final List<Region> pipelineLines = new ArrayList<>();
    private final List<Label> pipelineNames = new ArrayList<>();
    private final GridPane segments = new GridPane();
    private final Label sessionSummary = Ui.label("", "muted");
    private final Label nextTitle = Ui.label("", "research-next-title");
    private final Label warning = Ui.badge("", "muted");
    private final Label failedJobs = Ui.label("", "muted", "research-attention-text");
    private boolean built;
    private String sessionKey;

    public OverviewView(AppContext ctx) {
        super(ctx);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    }

    @Override public void onSnapshot(Snapshot s) {
        if (!built) { build(s, body); built = true; }
        update(s);
    }

    @Override protected void build(Snapshot s, VBox page) {
        HBox headline = new HBox(16, new VBox(0, datasetId, Ui.label("MVP Binance", "research-title")), Ui.spacer(), guardStrip());
        headline.getStyleClass().add("card"); headline.setId("research-header"); headline.setAlignment(Pos.CENTER_LEFT);
        HBox pipelineHead = new HBox(Ui.label("SCIENTIFIC PIPELINE", "card-title"), Ui.spacer(), stage);
        pipelineHead.setAlignment(Pos.CENTER_LEFT);
        GridPane pipelineGrid = new GridPane(); pipelineGrid.setHgap(8);
        List<PipelineStage> stages = visibleStages(s);
        for (int i = 0; i < stages.size(); i++) {
            var column = new ColumnConstraints(); column.setPercentWidth(100.0 / stages.size()); column.setMinWidth(0);
            pipelineGrid.getColumnConstraints().add(column);
            Region line = new Region(); line.getStyleClass().add("pipeline-line"); line.setId("pipeline-" + stages.get(i).target());
            Label name = Ui.label("", "pipeline-label");
            VBox tile = new VBox(8, line, name); tile.setMinWidth(0);
            int index = i; tile.setOnMouseClicked(e -> ctx.navigate.accept(visibleStages(ctx.research.snapshot.get()).get(index).target()));
            tile.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER) ctx.navigate.accept(stages.get(index).target()); });
            tile.setFocusTraversable(true); pipelineGrid.add(tile, i, 0); pipelineLines.add(line); pipelineNames.add(name);
        }
        VBox pipeline = new VBox(14, pipelineHead, pipelineGrid); pipeline.getStyleClass().add("card"); pipeline.setId("research-pipeline");
        GridPane left = new GridPane(); left.setHgap(14); left.setVgap(14);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(25); column.setMinWidth(0);
            left.getColumnConstraints().add(column);
            Label value = Ui.label("N/A", "metric", "research-kpi-value");
            Label note = Ui.label("", "muted", "research-note");
            VBox kpi = new VBox(0, Ui.label(new String[] {"CAPTURE", "SESSIONS", "ANCHORS", "HYPOTHESES"}[i], "card-title"), value, note);
            VBox.setMargin(value, new javafx.geometry.Insets(6, 0, 0, 0));
            kpi.getStyleClass().add("card"); kpi.setId("research-kpi-" + i); kpi.setMinWidth(0);
            metricValues.add(value); metricNotes.add(note); left.add(kpi, i, 0);
        }
        VBox dataset = new VBox(0, Ui.label("TRAIN DATASET", "card-title")); dataset.getStyleClass().add("card"); dataset.setId("research-dataset");
        VBox.setMargin(dataset.getChildren().getFirst(), new javafx.geometry.Insets(0, 0, 6, 0));
        for (String key : new String[] {"Label schema", "Feature schema", "Features complete"}) {
            Label value = Ui.label("N/A", "mono"); datasetValues.add(value); dataset.getChildren().add(referenceRow(key, value));
        }
        dataset.setOnContextMenuRequested(e -> {
            MenuItem detail = new MenuItem("Open Dataset / label completion details"); detail.setOnAction(event -> ctx.navigate.accept("dataset"));
            new ContextMenu(detail).show(dataset, e.getScreenX(), e.getScreenY());
        });
        left.add(dataset, 0, 1, 4, 1);
        Button sessionsLink = new Button("SESSIONS"); sessionsLink.getStyleClass().add("sessions-link");
        sessionsLink.setOnAction(e -> ctx.navigate.accept("sessions")); sessionsLink.setTooltip(new Tooltip("Open TRAIN session details"));
        HBox sessionHead = new HBox(sessionsLink, Ui.spacer(), sessionSummary); sessionHead.setAlignment(Pos.CENTER_LEFT);
        segments.setHgap(5); segments.setId("research-session-segments");
        VBox sessions = new VBox(12, sessionHead, segments); sessions.getStyleClass().add("card"); sessions.setId("research-sessions");
        left.add(sessions, 0, 2, 4, 1);
        RowConstraints sessionRow = new RowConstraints(); sessionRow.setVgrow(Priority.ALWAYS); sessionRow.setMinHeight(0);
        left.getRowConstraints().addAll(new RowConstraints(), new RowConstraints(), sessionRow);
        Button next = Ui.button("Open hypotheses", "primary"); next.setOnAction(e -> ctx.navigate.accept("hypotheses"));
        VBox nextCard = new VBox(6, Ui.label("NEXT ALLOWED STEP", "card-title"), nextTitle, next);
        VBox.setMargin(next, new javafx.geometry.Insets(6, 0, 0, 0)); nextCard.getStyleClass().add("card"); nextCard.setId("research-next");
        VBox attention = new VBox(8, Ui.label("ATTENTION", "card-title"), warning, failedJobs); attention.getStyleClass().add("card"); attention.setId("research-attention");
        attention.setOnMouseClicked(e -> ctx.navigate.accept("logs")); attention.setFocusTraversable(true);
        attention.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER) ctx.navigate.accept("logs"); });
        attention.setAccessibleText("Attention · open Logs");
        Label note = Ui.label("Locked environments cannot be opened from this screen.", "muted", "research-environment-note");
        VBox environment = new VBox(0, Ui.label("ENVIRONMENTS", "card-title"), referenceRow("TRAIN", Ui.label("Open", "mono")),
                referenceRow("VALIDATION", Ui.label("Locked", "mono")), referenceRow("FINAL HOLDOUT", Ui.label("Sealed", "mono")), note);
        VBox.setMargin(environment.getChildren().getFirst(), new javafx.geometry.Insets(0, 0, 6, 0));
        VBox.setMargin(note, new javafx.geometry.Insets(10, 0, 0, 0));
        environment.getStyleClass().add("card"); environment.setId("research-environments"); VBox.setVgrow(environment, Priority.ALWAYS);
        VBox side = new VBox(14, nextCard, attention, environment); side.setId("research-right");
        GridPane layout = new GridPane(); layout.setHgap(14); layout.setVgap(14);
        ColumnConstraints mainColumn = new ColumnConstraints(); mainColumn.setHgrow(Priority.ALWAYS); mainColumn.setMinWidth(0);
        layout.getColumnConstraints().addAll(mainColumn, new ColumnConstraints(330));
        RowConstraints last = new RowConstraints(); last.setVgrow(Priority.ALWAYS); last.setMinHeight(0);
        layout.getRowConstraints().addAll(new RowConstraints(), new RowConstraints(), last);
        layout.add(headline, 0, 0, 2, 1); layout.add(pipeline, 0, 1, 2, 1); layout.add(left, 0, 2); layout.add(side, 1, 2);
        VBox.setVgrow(layout, Priority.ALWAYS); page.getChildren().add(layout);
    }

    private void update(Snapshot s) {
        boolean labelsRunning = ctx.research.labelsRunning();
        datasetId.setText((s.source == DataSource.MOCK ? "MOCK · " : "") + "DATASET " + Fmt.shortHash(s.datasetId));
        String currentStage = PipelineService.currentStage(s, labelsRunning);
        stage.setText("STAGE · " + currentStage + " · " + (labelsRunning ? "RUNNING" : "IDLE"));
        boolean capturing = "RUNNING".equals(s.capture.recorder());
        long completed = s.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        metricValues.get(0).setText(capturing ? "LIVE" : Fmt.text(s.capture.recorder()));
        metricValues.get(0).getStyleClass().setAll("metric", "research-kpi-value", capturing ? "metric-ok" : "metric-muted");
        metricValues.get(1).setText(Fmt.num(s.sessionCount)); metricValues.get(2).setText(Fmt.num(s.anchorCount));
        metricValues.get(3).setText(s.datasetId == null ? "N/A" : completed + "/" + s.hypotheses.size());
        metricValues.get(3).getStyleClass().setAll("metric", "research-kpi-value", "metric-purple");
        metricNotes.get(0).setText("Uptime " + Fmt.text(s.capture.sessionDuration()));
        metricNotes.get(1).setText(s.sessionCount != null && s.labelDone != null && s.sessionCount.equals(s.labelDone)
                ? "All labeled" : "Labels " + Fmt.ratio(s.labelDone, s.labelsTotalSessions()));
        metricNotes.get(2).setText(Fmt.num(s.checkpointSamples) + " rows"); metricNotes.get(3).setText(Fmt.text(s.frozenSpecStatus));
        datasetValues.get(0).setText(Fmt.text(s.labelSchema) + (s.horizons.isEmpty() ? "" : " · " + s.horizons.size() + " horizons"));
        datasetValues.get(1).setText(Fmt.text(s.featureSchema)); datasetValues.get(2).setText(Fmt.ratio(s.featureDone, s.labelsTotalSessions()));
        nextTitle.setText(currentStage.equals("HYPOTHESIS RESEARCH") ? "Run hypotheses on TRAIN" : currentStage);
        long failed = ctx.jobs.jobs.stream().filter(j -> j.state.get() == JobState.FAILED).count();
        warning.setText(s.warnings.size() + (s.warnings.size() == 1 ? " WARNING" : " WARNINGS"));
        warning.getStyleClass().setAll("badge", s.warnings.isEmpty() ? "badge-muted" : "badge-warn");
        failedJobs.setText(failed + " failed jobs. Details in Logs.");
        List<PipelineStage> stages = visibleStages(s);
        for (int i = 0; i < stages.size(); i++) {
            PipelineStage item = stages.get(i); Region line = pipelineLines.get(i); Label name = pipelineNames.get(i);
            boolean activeCapture = item.target().equals("capture") && capturing;
            line.getStyleClass().setAll("pipeline-line", activeCapture ? "pipeline-capture-live" : "pipeline-" + item.state().tone);
            if (item.state() == StageState.LOCKED) line.getStyleClass().add("pipeline-hatch");
            ctx.motion.reference.setBreathing(line, activeCapture, MotionTokens.PIPELINE, MotionTokens.CSS_EASE);
            name.setText(item.target().equals("dataset") ? "Dataset" : item.target().equals("labels") ? "Labels" : item.title());
            name.getStyleClass().setAll("pipeline-label", item.state() == StageState.READY || activeCapture ? "pipeline-available" : "pipeline-unavailable");
            line.getParent().setAccessibleText(name.getText() + " · " + item.state() + " · " + item.summary());
        }
        String nextSessionKey = s.sessions.stream().map(session -> session.id() + ":" + session.overall()).toList()
                + s.capture.currentSession() + capturing;
        if (!nextSessionKey.equals(sessionKey)) { sessionKey = nextSessionKey; updateSessions(s, capturing); }
    }

    private void updateSessions(Snapshot s, boolean capturing) {
        Map<String, SessionInfo> sessions = new LinkedHashMap<>();
        for (SessionInfo session : s.sessions) if (session.id() != null) sessions.putIfAbsent(session.id(), session);
        String active = capturing ? s.capture.currentSession() : null;
        if (active != null && !active.isBlank()) sessions.putIfAbsent(active, null);
        segments.getChildren().clear(); segments.getColumnConstraints().clear();
        long ready = 0, captureCount = 0;
        int index = 0;
        for (var entry : sessions.entrySet()) {
            boolean live = entry.getKey().equals(active);
            SessionInfo session = entry.getValue();
            String state = live ? "CAPTURING" : session.overall().name();
            if (live) captureCount++; else if (session.overall() == SessionInfo.Overall.COMPLETE) ready++;
            Region block = new Region(); block.getStyleClass().addAll("session-segment", "session-" + state.toLowerCase(java.util.Locale.ROOT));
            block.setAccessibleText(entry.getKey() + " · " + state); block.setId(live ? "research-active-session" : null);
            Tooltip.install(block, new Tooltip(block.getAccessibleText()));
            block.setOnMouseClicked(e -> ctx.navigate.accept("sessions"));
            if (live) ctx.motion.reference.breathe(block, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(100.0 / sessions.size()); column.setMinWidth(0);
            segments.getColumnConstraints().add(column); segments.add(block, index++, 0);
        }
        long other = sessions.size() - ready - captureCount;
        sessionSummary.setText(sessions.isEmpty() ? "Session states unavailable" : ready + " ready · " + captureCount + " capturing" + (other == 0 ? "" : " · " + other + " other"));
        if (sessions.isEmpty()) segments.add(Ui.label("No TRAIN session states reported. Open Sessions for details.", "muted"), 0, 0);
    }

    private List<PipelineStage> visibleStages(Snapshot s) {
        return PipelineService.stages(s, ctx.research.labelsRunning()).stream().filter(st -> !Set.of("sessions", "paper").contains(st.target())).toList();
    }

    private static HBox referenceRow(String key, Label value) {
        HBox row = new HBox(12, Ui.label(key, "muted"), Ui.spacer(), value); row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("reference-row"); return row;
    }

    static HBox guardStrip() {
        Label holdout = Ui.badge("FINAL HOLDOUT SEALED", "muted"); holdout.getStyleClass().add("holdout-hatch");
        Label train = Ui.badge("TRAIN OPEN", "ok");
        Region trainDot = new Region(); trainDot.getStyleClass().add("market-feed-dot");
        trainDot.setStyle("-fx-background-color: -ok;"); train.setGraphic(trainDot); train.setGraphicTextGap(6);
        HBox guard = new HBox(16, Ui.label("RESEARCH GUARD", "card-title"), train, Ui.badge("VALIDATION LOCKED", "bad"), holdout);
        guard.setAlignment(Pos.CENTER_LEFT); guard.getStyleClass().add("guard-strip"); return guard;
    }
}
