package panel.researchview;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.design.ByxIcon;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.tradeview.DeskMode;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;

/**
 * Research Overview V2 (referência screens/research). Cabeçalho do dataset, research guard (TRAIN aberto,
 * VALIDATION trancada, FINAL_HOLDOUT selado: três tratamentos diferentes, nenhum botão), pipeline científico,
 * KPIs, dataset de treino, faixa de sessões, próximo passo, atenção e captura. Tudo vem do Snapshot real.
 * Responsivo por reorganização: 1440/1600 mostram só os nomes das etapas; 1920 mostra o resumo real de cada uma,
 * a coluna lateral de 420 e a faixa de sessões mais alta. Um snapshot só toca os nós afetados.
 */
public final class ResearchOverview extends GridPane implements View {
    /** Fonte de dados do Overview (o contexto do app a fornece; stubs nos testes). */
    public interface Source {
        Snapshot snapshot();

        boolean labelsRunning();

        int failedJobs();
    }

    private final MotionService motion;
    private final Source source;
    private final Consumer<String> navigate;

    private final Label datasetLabel = ByxFonts.upper(Fx.label("Dataset N/A", "byx-label"));
    private final Label partition = ByxBadge.of("PARTITION · N/A", ByxBadge.Tone.NEUTRAL);
    private final Label schema = ByxBadge.of("SCHEMA · N/A", ByxBadge.Tone.NEUTRAL);
    private final List<GuardTile> guardTiles = new ArrayList<>();
    private final Label stageBadge = ByxBadge.of("STAGE · NO DATASET · IDLE", ByxBadge.Tone.ACCENT);
    private final HBox pipelineRow = new HBox(8);
    private final List<StepTile> tiles = new ArrayList<>();
    private final List<Kpi> kpis = new ArrayList<>();
    private final List<KvRow> datasetRows = new ArrayList<>();
    private final GridPane datasetGrid = new GridPane();
    private final Label sessionSummary = Fx.label("", "byx-desk-secondary");
    private final HBox strip = new HBox(5);
    private final List<Region> segments = new ArrayList<>();
    private final Label emptySessions = Fx.label("No TRAIN session states reported.", "byx-desk-t3");
    private final Label nextTitle = Fx.label("", "byx-section-title");
    private final Button nextButton = new Button("Open hypotheses");
    private final Label warning = ByxBadge.of("No warnings", ByxBadge.Tone.NEUTRAL);
    private final Label failedJobs = Fx.label("", "byx-desk-secondary");
    private final KvRow captureState = new KvRow("State");
    private final KvRow captureUptime = new KvRow("Uptime");
    private final KvRow captureSession = new KvRow("Session");
    private final VBox sessionsPanel = new VBox(12);
    private final VBox left = new VBox(14);
    private final ColumnConstraints sideColumn = new ColumnConstraints(360, 360, 360);
    private final javafx.scene.control.ScrollPane scroll;

    private DeskMode mode;
    private String nextTarget = "hypotheses";
    private Region breathing;
    private Region liveSegment;
    private int lastFingerprint;
    private boolean built;
    private int applied;
    private int skipped;

    public ResearchOverview(MotionService motion, Source source, Consumer<String> navigate) {
        this.motion = motion;
        this.source = source;
        this.navigate = navigate;
        getStyleClass().add("byx-desk");
        setId("research");
        setMinSize(0, 0);
        setPadding(new Insets(14, 20, 16, 20));
        setHgap(14);
        setVgap(14);
        setAlignment(Pos.TOP_LEFT);

        // cabeçalho (82)
        VBox titles = new VBox(datasetLabel, Fx.label("MVP Binance", "byx-page-title"));
        HBox header = new HBox(16, titles, Fx.spacer(), partition, schema);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("byx-panel");
        header.setId("research-header");
        header.setMinHeight(82);
        header.setPrefHeight(82);

        // research guard (92)
        HBox guard = new HBox(14);
        guard.setAlignment(Pos.CENTER_LEFT);
        guard.getStyleClass().addAll("byx-panel", "byx-res-guard");
        guard.setId("research-guard");
        guard.setMinHeight(92);
        guard.setPrefHeight(92);
        guard.getChildren().add(Fx.label("RESEARCH GUARD", "byx-label"));
        for (ResearchModel.Gate g : ResearchModel.guard(new Snapshot())) {
            GuardTile t = new GuardTile(g);
            guardTiles.add(t);
            HBox.setHgrow(t, Priority.ALWAYS);
            guard.getChildren().add(t);
        }

        // pipeline
        HBox pipelineHead = new HBox(Fx.label("Scientific pipeline", "byx-section-title-sm"), Fx.spacer(), stageBadge);
        pipelineHead.setAlignment(Pos.CENTER_LEFT);
        VBox pipeline = new VBox(14, pipelineHead, pipelineRow);
        pipeline.getStyleClass().add("byx-panel");
        pipeline.setId("research-pipeline");
        pipeline.setMinHeight(108);

        // KPIs
        HBox kpiRow = new HBox(14);
        kpiRow.setId("research-kpis");
        for (String title : new String[] {"Capture", "Sessions", "Anchors", "Hypotheses"}) {
            Kpi k = new Kpi(title);
            kpis.add(k);
            HBox.setHgrow(k, Priority.ALWAYS);
            k.setMaxWidth(Double.MAX_VALUE);
            k.setPrefWidth(1);
            kpiRow.getChildren().add(k);
        }

        // dataset de treino
        VBox dataset = new VBox(4, Fx.label("Train dataset", "byx-section-title-sm"));
        for (String key : new String[] {"Label schema", "Horizons", "Feature schema", "Features complete"}) {
            datasetRows.add(new KvRow(key));
        }
        datasetGrid.setHgap(40);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setHgrow(Priority.ALWAYS);
            c.setPercentWidth(50);
            datasetGrid.getColumnConstraints().add(c);
        }
        dataset.getChildren().add(datasetGrid);
        dataset.getStyleClass().add("byx-panel");
        dataset.setId("research-dataset");

        // sessões
        HBox sessionHead = new HBox(Fx.label("Sessions", "byx-section-title-sm"), Fx.spacer(), sessionSummary);
        sessionHead.setAlignment(Pos.CENTER_LEFT);
        strip.setId("research-session-segments");
        emptySessions.setVisible(false);
        emptySessions.setManaged(false);
        sessionsPanel.getChildren().addAll(sessionHead, strip, emptySessions);
        sessionsPanel.getStyleClass().add("byx-panel");
        sessionsPanel.setId("research-sessions");

        left.getChildren().addAll(kpiRow, dataset, sessionsPanel);

        // coluna lateral
        nextButton.getStyleClass().add("byx-res-next");
        nextButton.setOnAction(e -> navigate.accept(nextTarget)); // só navega: o roteador decide (gate real)
        VBox next = new VBox(6, ByxFonts.upper(Fx.label("Next allowed step", "byx-label")), nextTitle, nextButton);
        next.getStyleClass().add("byx-panel");
        next.setId("research-next");
        VBox.setMargin(nextButton, new Insets(8, 0, 0, 0));
        HBox warnRow = new HBox(warning);
        warnRow.setPadding(new Insets(8, 0, 8, 0));
        VBox attention = new VBox(0, ByxFonts.upper(Fx.label("Attention", "byx-label")), warnRow, failedJobs);
        attention.getStyleClass().add("byx-panel");
        attention.setId("research-attention");
        attention.setFocusTraversable(true);
        attention.setAccessibleText("Attention · open Logs");
        attention.setOnMouseClicked(e -> navigate.accept("logs"));
        attention.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                navigate.accept("logs");
            }
        });
        VBox capture = new VBox(0, Fx.label("Capture", "byx-section-title-sm"), captureState, captureUptime, captureSession);
        capture.getChildren().getFirst().getStyleClass().add("byx-desk-panel-head");
        capture.getStyleClass().add("byx-panel");
        capture.setId("research-capture");
        VBox side = new VBox(14, next, attention, capture);
        side.setId("research-right");

        ColumnConstraints mainColumn = new ColumnConstraints();
        mainColumn.setHgrow(Priority.ALWAYS);
        mainColumn.setMinWidth(0);
        getColumnConstraints().addAll(mainColumn, sideColumn);
        RowConstraints top = new RowConstraints();
        RowConstraints bottom = new RowConstraints();
        bottom.setVgrow(Priority.ALWAYS);
        bottom.setMinHeight(0);
        getRowConstraints().addAll(new RowConstraints(), new RowConstraints(), new RowConstraints(), bottom);
        GridPane.setConstraints(header, 0, 0, 2, 1);
        GridPane.setConstraints(guard, 0, 1, 2, 1);
        GridPane.setConstraints(pipeline, 0, 2, 2, 1);
        GridPane.setConstraints(left, 0, 3);
        GridPane.setConstraints(side, 1, 3);
        GridPane.setValignment(left, javafx.geometry.VPos.TOP);
        GridPane.setValignment(side, javafx.geometry.VPos.TOP);
        getChildren().addAll(header, guard, pipeline, left, side);
        top.setVgrow(Priority.NEVER);

        scroll = V2Scroll.wrap(this);
        widthProperty().addListener((o, a, w) -> applyMode(DeskMode.of(w.doubleValue())));
        applyMode(DeskMode.COMPACT);
    }

    // ------------------------------------------------------------------ layout por breakpoint

    public DeskMode mode() {
        return mode;
    }

    void applyMode(DeskMode next) {
        if (next == mode) {
            return;
        }
        mode = next;
        boolean expanded = next == DeskMode.EXPANDED;
        double side = expanded ? 420 : 360;
        sideColumn.setMinWidth(side);
        sideColumn.setPrefWidth(side);
        sideColumn.setMaxWidth(side);
        pipelineRow.setSpacing(expanded ? 14 : 8);
        for (StepTile t : tiles) {
            t.setExpanded(expanded);
        }
        strip.setSpacing(expanded ? 6 : 5);
        for (Region seg : segments) {
            sizeSegment(seg);
        }
        datasetGrid.setHgap(expanded ? 40 : 0);
        datasetGrid.getColumnConstraints().get(0).setPercentWidth(expanded ? 50 : 100);
        datasetGrid.getColumnConstraints().get(1).setPercentWidth(expanded ? 50 : 0);
        datasetGrid.getChildren().clear();
        for (int i = 0; i < datasetRows.size(); i++) {
            GridPane.setConstraints(datasetRows.get(i), expanded ? i % 2 : 0, expanded ? i / 2 : i);
            datasetGrid.getChildren().add(datasetRows.get(i));
        }
        if (built) {
            lastFingerprint = 0;
            built = false;
            onSnapshot(null);
        }
    }

    private void sizeSegment(Region seg) {
        double h = mode == DeskMode.EXPANDED ? 44 : 30;
        seg.setMinHeight(h);
        seg.setPrefHeight(h);
        seg.setMaxHeight(h);
    }

    // ------------------------------------------------------------------ View

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        Snapshot s = source.snapshot();
        boolean labelsRunning = source.labelsRunning();
        int failed = source.failedJobs();
        int fp = ResearchModel.fingerprint(s, labelsRunning, failed);
        if (built && fp == lastFingerprint) {
            skipped++;
            return;
        }
        built = true;
        lastFingerprint = fp;
        applied++;
        update(s, labelsRunning, failed);
    }

    private void update(Snapshot s, boolean labelsRunning, int failed) {
        Fx.text(datasetLabel, ResearchModel.datasetLabel(s).toUpperCase(java.util.Locale.ROOT));
        Fx.text(partition, ResearchModel.partitionBadge(s));
        Fx.text(schema, ResearchModel.schemaBadge(s));
        List<ResearchModel.Gate> gates = ResearchModel.guard(s);
        for (int i = 0; i < gates.size(); i++) {
            guardTiles.get(i).apply(gates.get(i));
        }
        Fx.text(stageBadge, ResearchModel.stageBadge(s, labelsRunning));

        List<ResearchModel.Step> steps = ResearchModel.steps(s, labelsRunning);
        while (tiles.size() < steps.size()) {
            StepTile t = new StepTile(navigate);
            t.setExpanded(mode == DeskMode.EXPANDED);
            tiles.add(t);
            HBox.setHgrow(t, Priority.ALWAYS);
            t.setMaxWidth(Double.MAX_VALUE);
            t.setPrefWidth(1);
            pipelineRow.getChildren().add(t);
        }
        Region wasBreathing = breathing;
        breathing = null;
        for (int i = 0; i < steps.size(); i++) {
            tiles.get(i).apply(steps.get(i));
            if (steps.get(i).running()) {
                breathing = tiles.get(i).bar();
            }
        }
        // uma única animação para o recorder RUNNING: criada com o estado, parada quando ele termina
        if (wasBreathing != null && wasBreathing != breathing) {
            motion.reference.setBreathing(wasBreathing, false, MotionTokens.PIPELINE, MotionTokens.CSS_EASE);
        }
        if (breathing != null) {
            motion.reference.setBreathing(breathing, true, MotionTokens.PIPELINE, MotionTokens.CSS_EASE);
        }

        List<ResearchModel.Kpi> model = ResearchModel.kpis(s);
        for (int i = 0; i < kpis.size(); i++) {
            kpis.get(i).apply(model.get(i));
        }
        List<String[]> rows = ResearchModel.datasetRows(s);
        for (int i = 0; i < rows.size(); i++) {
            datasetRows.get(i).set(rows.get(i)[1], false, "N/A".equals(rows.get(i)[1]) ? "dim" : null);
        }
        updateSessions(ResearchModel.sessions(s));

        ResearchModel.Next next = ResearchModel.next(s, labelsRunning);
        nextTarget = next.target();
        Fx.text(nextTitle, next.title());
        Fx.text(nextButton, next.button());
        Fx.text(warning, ResearchModel.warningText(s.warnings.size()));
        Fx.tone(warning, s.warnings.isEmpty() ? null : "tone-wrn", "tone-wrn");
        Fx.text(failedJobs, ResearchModel.failedJobsText(failed));
        String[] cap = ResearchModel.captureRows(s);
        captureState.set(cap[0], false, "N/A".equals(cap[0]) ? "dim" : null);
        captureUptime.set(cap[1], true, "N/A".equals(cap[1]) ? "dim" : null);
        captureSession.set(cap[2], true, "N/A".equals(cap[2]) ? "dim" : null);
    }

    /** Pool de células: só cresce/encolhe quando o número de sessões muda; o resto troca de classe. */
    private void updateSessions(ResearchModel.Sessions sessions) {
        Fx.text(sessionSummary, sessions.summary());
        boolean empty = sessions.segments().isEmpty();
        Fx.shown(emptySessions, empty);
        while (segments.size() < sessions.segments().size()) {
            Region seg = new Region();
            seg.getStyleClass().add("byx-res-seg");
            HBox.setHgrow(seg, Priority.ALWAYS);
            seg.setMaxWidth(Double.MAX_VALUE);
            seg.setPrefWidth(1);
            seg.setMinWidth(0);
            sizeSegment(seg);
            segments.add(seg);
            strip.getChildren().add(seg);
        }
        while (segments.size() > sessions.segments().size()) {
            Region gone = segments.removeLast();
            if (gone == liveSegment) {
                motion.reference.setBreathing(gone, false, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
                liveSegment = null;
            }
            strip.getChildren().remove(gone);
        }
        Region live = null;
        for (int i = 0; i < segments.size(); i++) {
            ResearchModel.Seg seg = sessions.segments().get(i);
            Region r = segments.get(i);
            Fx.tone(r, seg.state().name().toLowerCase(java.util.Locale.ROOT), "complete", "capturing", "failed", "pending");
            r.setAccessibleText(seg.id() + " · " + seg.state());
            if (seg.state() == ResearchModel.SegState.CAPTURING) {
                live = r;
            }
        }
        // movimento só para a sessão realmente ativa; uma animação, que acompanha a célula
        if (liveSegment != null && liveSegment != live) {
            motion.reference.setBreathing(liveSegment, false, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        }
        if (live != null) {
            motion.reference.setBreathing(live, true, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        }
        liveSegment = live;
    }

    // ------------------------------------------------------------------ inspeção (testes e QA)

    int applied() {
        return applied;
    }

    int skipped() {
        return skipped;
    }

    List<GuardTile> guardTiles() {
        return guardTiles;
    }

    List<StepTile> tiles() {
        return tiles;
    }

    List<Kpi> kpis() {
        return kpis;
    }

    List<Region> segments() {
        return segments;
    }

    Region liveSegment() {
        return liveSegment;
    }

    Button nextButton() {
        return nextButton;
    }

    Label warningBadge() {
        return warning;
    }

    Label failedJobsLabel() {
        return failedJobs;
    }

    Label stageBadge() {
        return stageBadge;
    }

    Label sessionSummary() {
        return sessionSummary;
    }

    List<KvRow> datasetRows() {
        return datasetRows;
    }

    KvRow captureState() {
        return captureState;
    }

    int nodeCount() {
        return count(this);
    }

    private static int count(Node n) {
        int c = 1;
        if (n instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                c += count(child);
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ componentes

    /** Um ambiente do guard: TRAIN aberto (verde), VALIDATION trancada (vermelho + cadeado), FINAL_HOLDOUT selado (hachura). */
    static final class GuardTile extends StackPane {
        private final Label name = Fx.label("", "byx-section-title-sm");
        private final Label state = Fx.label("", "byx-res-guard-state");
        private final Label note = Fx.label("", "byx-desk-secondary");
        private final StackPane icon = new StackPane();

        GuardTile(ResearchModel.Gate gate) {
            getStyleClass().add("byx-res-gate");
            Node glyph = ByxIcon.of(switch (gate.kind()) {
                case OPEN -> "unlock";
                case LOCKED -> "lock";
                case SEALED -> "shield";
                case UNKNOWN -> "info";
            }, 18, null);
            icon.getChildren().add(glyph);
            icon.getStyleClass().add("byx-res-gate-icon");
            icon.setMinSize(32, 32);
            icon.setMaxSize(32, 32);
            HBox content = new HBox(12, icon, new VBox(name, state), Fx.spacer(), note);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setPadding(new Insets(0, 14, 0, 12));
            if (gate.kind() == ResearchModel.GuardKind.SEALED) {
                getChildren().add(new Hatch(8, Color.web("#ffffff0a"), 10));
            }
            getChildren().add(content);
            setMinHeight(68);
            setPrefHeight(68);
            setMaxWidth(Double.MAX_VALUE);
            setPrefWidth(1);
            setMinWidth(0);
            apply(gate);
        }

        void apply(ResearchModel.Gate gate) {
            Fx.text(name, gate.name());
            Fx.text(state, gate.state());
            Fx.text(note, gate.note());
            Fx.tone(this, gate.kind().name().toLowerCase(java.util.Locale.ROOT), "open", "locked", "sealed", "unknown");
            Fx.tone(state, gate.kind().name().toLowerCase(java.util.Locale.ROOT), "open", "locked", "sealed", "unknown");
            setAccessibleText(gate.name() + " " + gate.state());
        }

        Label stateLabel() {
            return state;
        }

        ResearchModel.GuardKind kind() {
            return getStyleClass().contains("open") ? ResearchModel.GuardKind.OPEN : getStyleClass().contains("locked")
                    ? ResearchModel.GuardKind.LOCKED : getStyleClass().contains("sealed") ? ResearchModel.GuardKind.SEALED : ResearchModel.GuardKind.UNKNOWN;
        }
    }

    /** Uma etapa do pipeline: barra (6/8 px) + nome (+ resumo real em EXPANDED). Clicar só navega. */
    static final class StepTile extends VBox {
        private final StackPane barBox = new StackPane();
        private final Region bar = new Region();
        private final Label name = Fx.label("", "byx-section-title-sm");
        private final Label summary = Fx.label("", "byx-desk-secondary");
        private Hatch hatch;
        private String target = "";
        private ResearchModel.Step step;

        StepTile(Consumer<String> navigate) {
            super(10);
            getStyleClass().add("byx-res-step");
            bar.getStyleClass().add("byx-res-bar");
            barBox.getChildren().add(bar);
            getChildren().addAll(barBox, name, summary);
            setFocusTraversable(true);
            setOnMouseClicked(e -> navigate.accept(target));
            setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    navigate.accept(target);
                }
            });
            setMinWidth(0);
        }

        void setExpanded(boolean expanded) {
            double h = expanded ? 8 : 6;
            barBox.setMinHeight(h);
            barBox.setPrefHeight(h);
            barBox.setMaxHeight(h);
            Fx.shown(summary, expanded);
        }

        void apply(ResearchModel.Step s) {
            step = s;
            target = s.target();
            Fx.text(name, s.name());
            Fx.text(summary, s.summary());
            String state = s.state().name().toLowerCase(java.util.Locale.ROOT);
            Fx.tone(bar, state, "complete", "current", "pending", "locked", "failed");
            Fx.cls(bar, "live", s.running());
            Fx.cls(name, "dim", s.state() == ResearchModel.StepState.PENDING || s.state() == ResearchModel.StepState.LOCKED);
            boolean locked = s.state() == ResearchModel.StepState.LOCKED;
            if (locked && hatch == null) {
                hatch = new Hatch(4, Color.web("#566079"), 4);
                barBox.getChildren().add(hatch);
            }
            if (hatch != null) {
                Fx.shown(hatch, locked);
            }
            setAccessibleText(s.name() + " · " + s.state() + " · " + s.summary());
        }

        Region bar() {
            return bar;
        }

        ResearchModel.Step step() {
            return step;
        }

        Label summaryLabel() {
            return summary;
        }

        boolean hatched() {
            return hatch != null && hatch.isVisible();
        }

        String target() {
            return target;
        }
    }

    /** KPI: título pequeno, valor grande mono, nota. */
    static final class Kpi extends VBox {
        private final Label value = Fx.label("N/A", "byx-res-big");
        private final Label note = Fx.label("", "byx-desk-secondary");

        Kpi(String title) {
            super(0);
            getStyleClass().addAll("byx-panel", "byx-res-kpi");
            getChildren().addAll(ByxFonts.upper(Fx.label(title, "byx-label")), value, note);
            setId("research-kpi-" + title.toLowerCase(java.util.Locale.ROOT));
        }

        void apply(ResearchModel.Kpi k) {
            Fx.text(value, k.value());
            Fx.tone(value, k.tone(), "pos", "dim", "purple");
            Fx.text(note, k.note());
        }

        Label value() {
            return value;
        }

        Label note() {
            return note;
        }
    }
}
