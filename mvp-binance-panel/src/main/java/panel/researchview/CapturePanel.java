package panel.researchview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.design.ByxIcon;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.security.AccessDeniedException;
import panel.tradeview.DeskMode;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.util.Fmt;

/**
 * Capture V2 (somente leitura). Cabeçalho, processo/campanha, sessão, growth, storage, integridade científica,
 * estados do processo, linha do tempo e detalhes. Os nós são criados uma vez; cada resultado do monitor troca
 * texto e classe. Estados só os que o monitor reporta (RUNNING, STALE, STOPPED, UNKNOWN); o que o monitor não
 * reporta (eventos, arquivos, retry, continuidade, gaps, drift, schema) é "Not reported", nunca zero. Um único
 * timer de apresentação (1 s) existe entre start() e stop(); o marcador "now" respira só em RUNNING (FULL).
 */
public final class CapturePanel extends GridPane {
    private static final String NOT_REPORTED = "Not reported";
    private static final double EQUAL = 4000;

    private final MotionService motion;
    private final Clock clock;
    private final Label title = Fx.label("N/A", "byx-page-title");
    private final Label stateBadge = ByxBadge.of("UNKNOWN", ByxBadge.Tone.NEUTRAL);
    private final Label uptime = Fx.label("N/A", "byx-res-process");
    private final Label campaignElapsed = Fx.label("Uptime · campaign elapsed —", "byx-desk-secondary");
    private final Label session = Fx.label("N/A", "byx-res-session");
    private final Label campaign = Fx.label("Campaign —", "byx-desk-secondary");
    private final KvRow events = new KvRow("Events");
    private final KvRow files = new KvRow("Files");
    private final KvRow captured = new KvRow("Captured data");
    private final KvRow lastEvent = new KvRow("Last event");
    private final KvRow diskUsage = new KvRow("Disk usage");
    private final KvRow diskFree = new KvRow("Disk free");
    private final KvRow retry = new KvRow("Retry / recovery");
    private final List<KvRow> integrity = new ArrayList<>();
    private final KvRow warnings = new KvRow("Warnings");
    private final List<Label> legend = new ArrayList<>();
    private final KvRow pid = new KvRow("PID");
    private final KvRow started = new KvRow("Started UTC");
    private final KvRow checked = new KvRow("Last checked UTC");
    private final KvRow path = new KvRow("Path");
    private final KvRow storageChecked = new KvRow("Storage checked UTC");
    private final KvRow percent = new KvRow("Campaign progress");
    private final VBox detailsBody = new VBox();
    private final Button detailsToggle = new Button("Process / storage details");
    private final Label sessionBarText = Fx.label("", "byx-res-bar-text");
    private final StackPane sessionBar = new StackPane(sessionBarText);
    private final Region now = new Region();
    private final List<Label> trackNotes = new ArrayList<>();
    private final Axis axis = new Axis();
    private final ColumnConstraints sideColumn = new ColumnConstraints(380, 380, 380);
    private final List<HBox> trackRows = new ArrayList<>();
    private final Timeline timer;

    private CaptureSnapshot snapshot;
    private Runnable guard = () -> { };
    private boolean active;
    private DeskMode mode;
    private int shows;

    public CapturePanel(MotionService motion, Clock clock) {
        this.motion = motion;
        this.clock = clock;
        getStyleClass().add("byx-desk");
        setId("capture");
        setMinSize(0, 0);
        setPadding(new Insets(14, 20, 16, 20));
        setHgap(14);
        setVgap(14);
        setAlignment(Pos.TOP_LEFT);

        HBox header = new HBox(14, new VBox(ByxFonts.upper(Fx.label("Capture detail", "byx-label")), title), Fx.spacer(),
                ByxBadge.of("READ-ONLY", ByxBadge.Tone.ACCENT), stateBadge);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("byx-panel");
        header.setId("capture-header");
        header.setMinHeight(82);
        header.setPrefHeight(82);

        VBox process = panel("capture-process", ByxFonts.upper(Fx.label("Process · campaign", "byx-label")), uptime, campaignElapsed);
        session.setWrapText(true);
        session.setMaxWidth(Double.MAX_VALUE);
        VBox sessionCard = panel("capture-session", ByxFonts.upper(Fx.label("Session", "byx-label")), session, campaign);
        VBox growth = rows("capture-growth", "Growth", events, files, captured, lastEvent);
        VBox storage = rows("capture-storage", "Storage", diskUsage, diskFree, retry);

        VBox integrityPanel = rows("capture-integrity", "Scientific integrity");
        for (String k : new String[] {"Sequence continuity", "Gaps detected", "Clock drift", "Schema match"}) {
            KvRow r = new KvRow(k);
            r.set(NOT_REPORTED.toUpperCase(Locale.ROOT), false, "dim");
            integrity.add(r);
            integrityPanel.getChildren().add(r);
        }
        FlowPane chips = new FlowPane(8, 8);
        for (State st : new State[] {State.RUNNING, State.STALE, State.STOPPED, State.UNKNOWN}) { // ordem da referência (sem RECOVERING: o monitor não o reporta)
            Label chip = ByxBadge.of(st.name(), tone(st));
            chip.getProperties().put("state", st);
            legend.add(chip);
            chips.getChildren().add(chip);
        }
        VBox states = panel("capture-states", ByxFonts.upper(Fx.label("Process state", "byx-label")), chips);
        VBox.setMargin(chips, new Insets(10, 0, 0, 0));
        warnings.set(Fmt.NA, false, "dim");
        VBox warn = rows("capture-warnings", "Warnings", warnings);

        detailsToggle.getStyleClass().add("byx-res-details-toggle");
        detailsToggle.setMaxWidth(Double.MAX_VALUE);
        detailsToggle.setAlignment(Pos.CENTER_LEFT);
        detailsBody.getChildren().addAll(pid, started, checked, path, storageChecked, percent);
        Fx.shown(detailsBody, false);
        detailsToggle.setOnAction(e -> Fx.shown(detailsBody, !detailsBody.isManaged()));
        VBox details = panel("capture-details", detailsToggle, detailsBody);

        HBox banner = new HBox(12, ByxIcon.of("info", 20, null),
                Fx.label("Read-only monitoring. Start, stop and recovery are handled outside the app.", "byx-desk-secondary"));
        banner.setAlignment(Pos.CENTER_LEFT);
        ((Label) banner.getChildren().get(1)).setWrapText(true);
        banner.getStyleClass().add("byx-res-banner");
        banner.setId("capture-readonly");
        VBox side = new VBox(14, integrityPanel, states, warn, details, banner);
        side.setId("capture-side");

        VBox timeline = buildTimeline();

        ColumnConstraints a = new ColumnConstraints();
        a.setHgrow(Priority.ALWAYS);
        a.setMinWidth(0);
        a.setPrefWidth(EQUAL); // preferido igual e enorme: as duas colunas centrais encolhem por igual até caber (a referência usa 1fr 1fr)
        ColumnConstraints b = new ColumnConstraints();
        b.setHgrow(Priority.ALWAYS);
        b.setMinWidth(0);
        b.setPrefWidth(EQUAL);
        getColumnConstraints().addAll(a, b, sideColumn);
        for (int i = 0; i < 4; i++) {
            getRowConstraints().add(new RowConstraints());
        }
        GridPane.setConstraints(header, 0, 0, 3, 1);
        GridPane.setConstraints(process, 0, 1);
        GridPane.setConstraints(sessionCard, 1, 1);
        GridPane.setConstraints(growth, 0, 2);
        GridPane.setConstraints(storage, 1, 2);
        GridPane.setConstraints(side, 2, 1, 1, 3);
        GridPane.setConstraints(timeline, 0, 3, 2, 1);
        for (Node n : new Node[] {process, sessionCard, growth, storage, timeline, side}) {
            GridPane.setValignment(n, javafx.geometry.VPos.TOP);
        }
        getChildren().addAll(header, process, sessionCard, growth, storage, side, timeline);

        timer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> {
            try {
                guard.run();
                updateTimers();
            } catch (AccessDeniedException denied) {
                stop();
                clear();
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);

        widthProperty().addListener((o, x, w) -> applyMode(DeskMode.of(w.doubleValue())));
        applyMode(DeskMode.COMPACT);
        clear();
    }

    private static VBox panel(String id, Node... children) {
        VBox p = new VBox(0, children);
        p.getStyleClass().add("byx-panel");
        p.setId(id);
        p.setMinWidth(0);
        return p;
    }

    private static VBox rows(String id, String title, KvRow... rows) {
        Label head = Fx.label(title, "byx-section-title-sm", "byx-desk-panel-head");
        VBox p = panel(id, head);
        p.getChildren().addAll(rows);
        return p;
    }

    private static ByxBadge.Tone tone(State s) {
        return switch (s) {
            case RUNNING -> ByxBadge.Tone.POSITIVE;
            case STALE -> ByxBadge.Tone.WARNING;
            case STOPPED, UNKNOWN -> ByxBadge.Tone.NEUTRAL;
        };
    }

    // ------------------------------------------------------------------ timeline

    private VBox buildTimeline() {
        HBox head = new HBox(Fx.label("Timeline · this session", "byx-section-title-sm"), Fx.spacer(),
                Fx.label("Rotation · retry · failure · recovery", "byx-desk-t3"));
        head.setPadding(new Insets(0, 0, 10, 0));
        VBox t = panel("capture-timeline", head);
        // linha da sessão
        sessionBar.getStyleClass().add("byx-res-sessionbar");
        sessionBar.setAlignment(Pos.CENTER_LEFT);
        now.getStyleClass().add("byx-res-now");
        now.setId("capture-now");
        now.setMinSize(10, 10);
        now.setMaxSize(10, 10);
        StackPane track = new StackPane(sessionBar, now);
        StackPane.setAlignment(now, Pos.CENTER_RIGHT);
        StackPane.setMargin(now, new Insets(0, 10, 0, 0));
        HBox sessionRow = row("Session", track);
        HBox.setHgrow(track, Priority.ALWAYS);
        t.getChildren().add(sessionRow);
        for (String name : new String[] {"Rotation", "Retry", "Failure", "Recovery"}) {
            Region line = new Region();
            line.getStyleClass().add("byx-res-dotted");
            HBox.setHgrow(line, Priority.ALWAYS);
            Label note = Fx.label(NOT_REPORTED, "byx-desk-t3");
            note.setMinWidth(110);
            note.setPrefWidth(110);
            trackNotes.add(note);
            HBox r = row(name, line);
            r.getChildren().add(note);
            t.getChildren().add(r);
        }
        t.getChildren().add(axis);
        VBox.setMargin(axis, new Insets(6, 0, 0, 106));
        return t;
    }

    private HBox row(String name, Node track) {
        Label l = Fx.label(name, "byx-desk-secondary");
        l.setMinWidth(90);
        l.setPrefWidth(90);
        HBox r = new HBox(16, l, track);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("byx-res-trackrow");
        trackRows.add(r);
        return r;
    }

    /** Eixo de minutos: 0 e quatro marcas em 23,6 / 47,3 / 70,9 / 94 % (referência). */
    static final class Axis extends Pane {
        private final List<Label> labels = new ArrayList<>();
        private static final double[] AT = {0, .236, .473, .709, .94};

        Axis() {
            setMinHeight(22);
            setPrefHeight(22);
            for (int i = 0; i < AT.length; i++) {
                Label l = Fx.label("", "byx-desk-axis");
                labels.add(l);
                getChildren().add(l);
            }
        }

        void set(Long totalMinutes) {
            for (int i = 0; i < AT.length; i++) {
                Fx.text(labels.get(i), totalMinutes == null ? "" : i == 0 ? "0 min" : String.valueOf(Math.round(totalMinutes * AT[i] / AT[AT.length - 1])));
            }
        }

        List<Label> labels() {
            return labels;
        }

        @Override
        protected void layoutChildren() {
            for (int i = 0; i < AT.length; i++) {
                Label l = labels.get(i);
                l.resizeRelocate(getWidth() * AT[i], 0, l.prefWidth(-1), l.prefHeight(-1));
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void start(Runnable guard) {
        if (active) {
            return;
        }
        this.guard = guard;
        guard.run();
        active = true;
        timer.play();
        updateIndicator();
    }

    public void stop() {
        active = false;
        timer.stop();
        updateIndicator();
    }

    public boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    public void clear() {
        snapshot = null;
        Fx.text(title, Fmt.NA);
        setState(State.UNKNOWN);
        for (KvRow r : new KvRow[] {events, files, captured, lastEvent, diskUsage, diskFree, retry, pid, started, checked, path, storageChecked}) {
            r.set(Fmt.NA, true, "dim");
        }
        Fx.text(session, Fmt.NA);
        Fx.text(campaign, "Campaign —");
        warnings.set(Fmt.NA, false, "dim");
        updateTimers();
        updateIndicator();
    }

    public void setCurrentSession(String id) {
        Fx.text(session, Fmt.text(id));
    }

    public void show(CaptureSnapshot v) {
        snapshot = v;
        shows++;
        Fx.text(title, Fmt.text(v.symbol()) + " · " + (v.market() == null ? Fmt.NA : panel.tradeview.DeskModel.venue(v.market())));
        setState(v.state());
        events.set(NOT_REPORTED, false, "dim");
        files.set(NOT_REPORTED, false, "dim");
        captured.set(orNa(bytes(v.capturedBytes())), true, v.capturedBytes() == null ? "dim" : null);
        lastEvent.set(orNa(utc(v.lastUpdate())), true, v.lastUpdate() == null ? "dim" : null);
        String usage = diskUsage(v);
        diskUsage.set(orNa(usage), true, usage == null ? "dim" : null);
        diskFree.set(orNa(bytes(v.diskFreeBytes())), true, v.diskFreeBytes() == null ? "dim" : null);
        retry.set(NOT_REPORTED, false, "dim");
        Fx.text(campaign, "Campaign " + Fmt.text(v.campaignId()));
        warnings.set(v.warnings().isEmpty() ? "None" : String.join(" · ", v.warnings()), false, v.warnings().isEmpty() ? "dim" : "warn");
        pid.set(orNa(v.pid() == null ? null : v.pid().toString()), true, v.pid() == null ? "dim" : null);
        started.set(orNa(utc(v.processStartedAt())), true, v.processStartedAt() == null ? "dim" : null);
        checked.set(orNa(utc(v.checkedAt())), true, v.checkedAt() == null ? "dim" : null);
        path.set(orNa(v.storagePath()), true, v.storagePath() == null ? "dim" : null);
        storageChecked.set(orNa(utc(v.storageCheckedAt())), true, v.storageCheckedAt() == null ? "dim" : null);
        updateTimers();
        updateIndicator();
    }

    private void setState(State st) {
        Fx.text(stateBadge, st.name());
        Fx.tone(stateBadge, switch (st) {
            case RUNNING -> "tone-pos";
            case STALE -> "tone-wrn";
            case STOPPED, UNKNOWN -> null;
        }, "tone-pos", "tone-wrn");
        for (Label chip : legend) {
            Fx.cls(chip, "current", chip.getProperties().get("state") == st);
            Fx.cls(chip, "dim", chip.getProperties().get("state") != st);
        }
        stateBadge.setAccessibleText("Capture " + st.name());
    }

    /** Só relógio e barra; nenhum I/O. Chamado pelo timer de 1 s e a cada resultado do monitor. */
    public void updateTimers() {
        Instant t = clock.instant();
        Duration up = snapshot == null ? null : snapshot.continuousElapsed(t);
        Duration camp = snapshot == null ? null : snapshot.campaignElapsed(t);
        Fx.text(uptime, CaptureSnapshot.elapsedText(up));
        Fx.text(campaignElapsed, "Uptime · campaign elapsed " + (camp == null ? "—" : CaptureSnapshot.elapsedText(camp)));
        Double ratio = snapshot == null ? null : snapshot.progress(t);
        percent.set(ratio == null ? "N/A · TRANSITION" : String.format(Locale.ROOT, "%.1f%%", ratio * 100), true, ratio == null ? "dim" : null);
        State st = snapshot == null ? State.UNKNOWN : snapshot.state();
        boolean bar = snapshot != null && up != null && st != State.UNKNOWN;
        Fx.shown(sessionBar, bar);
        Fx.tone(sessionBar, st.name().toLowerCase(Locale.ROOT), "running", "stale", "stopped", "unknown");
        Fx.text(sessionBarText, bar ? switch (st) {
            case RUNNING -> "Running · " + CaptureSnapshot.elapsedText(up);
            case STALE -> "Stale · " + CaptureSnapshot.elapsedText(up);
            case STOPPED -> "Stopped · " + CaptureSnapshot.elapsedText(up);
            case UNKNOWN -> "";
        } : "");
        axis.set(up == null ? null : Math.max(1, up.toMinutes()));
        for (Label n : trackNotes) {
            Fx.text(n, NOT_REPORTED);
        }
    }

    private void updateIndicator() {
        boolean running = active && snapshot != null && snapshot.state() == State.RUNNING;
        motion.reference.setBreathing(now, running, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        Fx.visible(now, snapshot != null && snapshot.state() == State.RUNNING);
    }

    // ------------------------------------------------------------------ breakpoints

    void applyMode(DeskMode next) {
        if (next == mode) {
            return;
        }
        mode = next;
        double side = switch (next) {
            case COMPACT -> 380;
            case STANDARD -> 420;
            case EXPANDED -> 460;
        };
        sideColumn.setMinWidth(side);
        sideColumn.setPrefWidth(side);
        sideColumn.setMaxWidth(side);
        double row = switch (next) {
            case COMPACT -> 38;
            case STANDARD -> 48;
            case EXPANDED -> 60;
        };
        double bar = switch (next) {
            case COMPACT -> 28;
            case STANDARD -> 38;
            case EXPANDED -> 50;
        };
        for (HBox r : trackRows) {
            r.setMinHeight(row);
            r.setPrefHeight(row);
            r.setMaxHeight(row);
        }
        sessionBar.setMinHeight(bar);
        sessionBar.setPrefHeight(bar);
        sessionBar.setMaxHeight(bar);
    }

    // ------------------------------------------------------------------ formatação

    private static String orNa(String s) {
        return s == null ? Fmt.NA : s;
    }

    private static String utc(Instant time) {
        return time == null ? null : time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static String bytes(Long bytes) {
        if (bytes == null) {
            return null;
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
    }

    private static String diskUsage(CaptureSnapshot v) {
        if (v.diskFreeBytes() == null || v.diskTotalBytes() == null || v.diskTotalBytes() <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%.1f%%", Math.clamp(100.0 * (1.0 - v.diskFreeBytes() / (double) v.diskTotalBytes()), 0, 100));
    }

    // ------------------------------------------------------------------ inspeção

    public DeskMode mode() {
        return mode;
    }

    public CaptureSnapshot snapshot() {
        return snapshot;
    }

    Label stateBadge() {
        return stateBadge;
    }

    Label title() {
        return title;
    }

    Label uptime() {
        return uptime;
    }

    Label session() {
        return session;
    }

    Region now() {
        return now;
    }

    StackPane sessionBar() {
        return sessionBar;
    }

    Label sessionBarText() {
        return sessionBarText;
    }

    Axis axis() {
        return axis;
    }

    List<Label> legend() {
        return legend;
    }

    List<KvRow> integrity() {
        return integrity;
    }

    KvRow events() {
        return events;
    }

    KvRow retry() {
        return retry;
    }

    KvRow warnings() {
        return warnings;
    }

    List<Label> trackNotes() {
        return trackNotes;
    }

    Button detailsToggle() {
        return detailsToggle;
    }

    VBox detailsBody() {
        return detailsBody;
    }

    int shows() {
        return shows;
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
}
