package panel.ui;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import javafx.util.Duration;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.security.AccessDeniedException;
import panel.util.Fmt;

/** Presentation timer only: no filesystem or process access. */
public final class CaptureMonitorCard extends VBox {
    private final MotionService motion;
    private final Clock clock;
    private final Label status = Ui.badge("UNKNOWN", "muted");
    private final HBox dot = new HBox();
    private final Label market = Ui.label("N/A", "muted-lg");
    private final Label uptime = Ui.label("N/A", "metric", "metric-info");
    private final Label elapsed = Ui.label("N/A / 24:00:00", "metric", "metric-muted");
    private final Label percent = Ui.label("N/A", "mono");
    private final ProgressBar progress = new ProgressBar(0);
    private final GridPane details = new GridPane();
    private final Label currentSession = Ui.label("N/A", "mono");
    private final javafx.scene.layout.Region now = new javafx.scene.layout.Region();
    private final VBox timelineCard;
    private final Timeline timer;
    private final ChangeListener<MotionPreference> preferenceListener = (o,a,b) -> updateIndicator();
    private CaptureSnapshot snapshot;
    private Runnable guard = () -> { };
    private boolean active;
    private boolean animated;
    private State indicatorState;

    public CaptureMonitorCard(MotionService motion, Clock clock) {
        this.motion = motion; this.clock = clock;
        setSpacing(14);
        HBox heading = new HBox(14, new VBox(0, Ui.label("CAPTURE DETAIL", "card-title"), market), Ui.spacer(),
                Ui.badge("READ-ONLY", "purple"), dot, status);
        heading.setAlignment(Pos.CENTER_LEFT);
        now.getStyleClass().add("capture-now"); now.setId("capture-now");
        HBox timeline = new HBox(12, Ui.label("Timeline · this session", "card-title"), Ui.spacer(), Ui.label("Now", "muted"), now);
        timeline.setAlignment(Pos.CENTER_LEFT);
        timelineCard = Ui.card("Timeline", timeline,
                Ui.label("Retry / recovery history is not reported by the current monitor.", "muted"));
        progress.setMaxWidth(Double.MAX_VALUE);
        details.setHgap(14); details.setVgap(14);
        ColumnConstraints left = new ColumnConstraints(); left.setHgrow(Priority.ALWAYS); left.setMinWidth(0);
        ColumnConstraints middle = new ColumnConstraints(); middle.setHgrow(Priority.ALWAYS); middle.setMinWidth(0);
        details.getColumnConstraints().addAll(left, middle, new ColumnConstraints(360));
        getChildren().addAll(heading, details);
        heading.getStyleClass().add("card");
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            try { guard.run(); updateTimers(); }
            catch (AccessDeniedException denied) { stop(); clear(); }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        updateIndicator();
    }
    public void start(Runnable guard) {
        if (active) return;
        this.guard = guard; guard.run(); active = true;
        motion.preference.addListener(preferenceListener); updateIndicator(); timer.play();
    }
    public void stop() {
        active = false; timer.stop(); motion.preference.removeListener(preferenceListener); updateIndicator();
    }
    public void clear() { snapshot = null; market.setText("N/A"); status.setText("UNKNOWN"); status.getStyleClass().setAll("badge", "badge-muted"); details.getChildren().clear(); updateTimers(); updateIndicator(); }
    public void show(CaptureSnapshot value) {
        snapshot = value;
        String tone = switch(value.state()) { case RUNNING -> "ok"; case STALE -> "warn"; case STOPPED -> "bad"; case UNKNOWN -> "muted"; };
        status.setText(value.state().name()); status.getStyleClass().setAll("badge", "badge-" + tone);
        market.setText(Fmt.text(value.symbol()) + " · " + Fmt.text(value.market()));
        details.getChildren().removeIf(node -> node != timelineCard);
        details.add(Ui.card("Uptime", uptime, Ui.label("Campaign elapsed", "muted"), elapsed), 0, 0);
        details.add(Ui.card("Current session", currentSession, Ui.kv("Campaign", value.campaignId())), 1, 0);
        details.add(Ui.card("Growth", Ui.kv("Events", "NOT REPORTED"), Ui.kv("Files", "NOT REPORTED"),
                Ui.kv("Captured data", bytes(value.capturedBytes())), Ui.kv("Last event", utc(value.lastUpdate()))), 0, 1);
        details.add(Ui.card("Storage", Ui.kv("Disk usage", diskUsage(value)), Ui.kv("Disk free", bytes(value.diskFreeBytes())),
                Ui.kv("Retry / recovery", "NOT REPORTED")), 1, 1);
        var process = new javafx.scene.control.TitledPane("Process / storage details", new VBox(8,
                Ui.kv("PID", value.pid() == null ? null : value.pid().toString()), Ui.kv("Started UTC", utc(value.processStartedAt())),
                Ui.kv("Last checked UTC", utc(value.checkedAt())), Ui.kv("Path", value.storagePath()),
                Ui.kv("Storage checked UTC", utc(value.storageCheckedAt())), progress, percent));
        process.setExpanded(false);
        details.add(Ui.card("Process state", Ui.badge(value.state().name(), tone), Ui.label("SCIENTIFIC INTEGRITY", "card-title"),
                Ui.kv("Sequence continuity", "NOT REPORTED"), Ui.kv("Gaps detected", "NOT REPORTED"), Ui.kv("Clock drift", "NOT REPORTED"),
                Ui.kv("Schema match", "NOT REPORTED"), Ui.kv("Warnings", String.join("\n", value.warnings())), process,
                Ui.label("Monitoring only. Start, stop and recovery are handled outside the app.", "muted")), 2, 0, 1, 3);
        if (!details.getChildren().contains(timelineCard)) details.add(timelineCard, 0, 2, 2, 1);
        updateTimers(); updateIndicator();
    }
    public void setCurrentSession(String session) { currentSession.setText(Fmt.text(session)); }
    public void updateTimers() {
        Instant now = clock.instant();
        uptime.setText(CaptureSnapshot.elapsedText(snapshot == null ? null : snapshot.continuousElapsed(now)));
        elapsed.setText(CaptureSnapshot.elapsedText(snapshot == null ? null : snapshot.campaignElapsed(now)) + " / 24:00:00");
        Double ratio = snapshot == null ? null : snapshot.progress(now);
        progress.setProgress(ratio == null ? 0 : ratio);
        percent.setText(ratio == null ? "N/A · TRANSITION" : String.format(Locale.ROOT, "%.1f%%", ratio * 100));
    }
    private void updateIndicator() {
        boolean run = active && snapshot != null && snapshot.state() == State.RUNNING && motion.full();
        motion.reference.setBreathing(now, active && snapshot != null && snapshot.state() == State.RUNNING,
                panel.motion.MotionTokens.LIVE, panel.motion.MotionTokens.CSS_EASE_IN_OUT);
        now.setVisible(snapshot != null && snapshot.state() == State.RUNNING);
        State currentState = snapshot == null ? State.UNKNOWN : snapshot.state();
        if (!dot.getChildren().isEmpty() && run == animated && currentState == indicatorState) return;
        indicatorState = currentState;
        dot.getChildren().clear();
        Label light = Ui.label("●", snapshot != null && snapshot.state() == State.RUNNING ? "metric-ok" : "muted");
        dot.getChildren().add(light); animated = run;

    }
    private static String utc(Instant time) { return time == null ? null : time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(); }
    private static String bytes(Long bytes) {
        if (bytes == null) return null;
        double value = bytes; String[] units = {"B", "KiB", "MiB", "GiB", "TiB"}; int i = 0;
        while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
    }
    private static String diskUsage(CaptureSnapshot value) {
        if (value.diskFreeBytes() == null || value.diskTotalBytes() == null || value.diskTotalBytes() <= 0) return null;
        return String.format(Locale.ROOT, "%.1f%%", Math.clamp(100.0 * (1.0 - value.diskFreeBytes() / (double)value.diskTotalBytes()), 0, 100));
    }
}
