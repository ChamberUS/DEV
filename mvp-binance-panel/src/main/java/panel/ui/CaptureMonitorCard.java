package panel.ui;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import javafx.animation.FadeTransition;
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
    private final FlowPane details = new FlowPane(14, 14);
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
        HBox heading = new HBox(10, Ui.label("CONTINUOUS CAPTURE", "card-title"), Ui.spacer(), dot, status);
        heading.setAlignment(Pos.CENTER_LEFT);
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox campaign = new VBox(8, Ui.label("CURRENT CAMPAIGN", "muted"), elapsed, progress, percent);
        VBox continuous = new VBox(8, Ui.label("CONTINUOUS UPTIME", "muted"), uptime);
        HBox timers = new HBox(40, continuous, campaign);
        HBox.setHgrow(continuous, Priority.ALWAYS); HBox.setHgrow(campaign, Priority.ALWAYS);
        getChildren().addAll(Ui.card("Capture monitor", heading, market, timers), details);
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
        details.getChildren().setAll(
            tile("Process", Ui.kv("PID", value.pid() == null ? null : value.pid().toString()),
                Ui.kv("Started UTC", utc(value.processStartedAt())), Ui.kv("Last checked UTC", utc(value.checkedAt()))),
            tile("Campaign", Ui.kv("Campaign ID", value.campaignId()), Ui.kv("Started UTC", utc(value.campaignStartedAt())),
                Ui.kv("Target", "24h / 86400 s"), Ui.kv("Symbol", value.symbol()), Ui.kv("Market", value.market()),
                Ui.kv("Sessions / latest session", null)),
            tile("Storage", Ui.kv("Path", value.storagePath()), Ui.kv("Captured data", bytes(value.capturedBytes())),
                Ui.kv("Disk free", bytes(value.diskFreeBytes())), Ui.kv("Disk usage", diskUsage(value)),
                Ui.kv("Storage checked UTC", utc(value.storageCheckedAt()))),
            tile("Health", Ui.kv("Supervisor", value.state().name()), Ui.kv("Recorder health", null),
                Ui.kv("Last update UTC", utc(value.lastUpdate())),
                Ui.kv("Warnings", String.join("\n", value.warnings()))),
            tile("Scientific integrity", Ui.kv("Sequence continuity", "NOT REPORTED"), Ui.kv("Gaps detected", "NOT REPORTED"),
                    Ui.kv("Clock drift", "NOT REPORTED"), Ui.kv("Schema match", "NOT REPORTED")),
            tile("Timeline", Ui.label("Retry / recovery history is not reported by the current monitor.", "muted")));
        updateTimers(); updateIndicator();
    }
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
        State currentState = snapshot == null ? State.UNKNOWN : snapshot.state();
        if (!dot.getChildren().isEmpty() && run == animated && currentState == indicatorState) return;
        indicatorState = currentState;
        dot.getChildren().clear();
        Label light = Ui.label("●", snapshot != null && snapshot.state() == State.RUNNING ? "metric-ok" : "muted");
        dot.getChildren().add(light); animated = run;
        if (run) motion.loop(light, () -> {
            FadeTransition fade = new FadeTransition(Duration.seconds(2), light);
            fade.setFromValue(1); fade.setToValue(0.45); fade.setAutoReverse(true); return fade;
        });
    }
    private static VBox tile(String title, javafx.scene.Node... children) {
        VBox card = Ui.card(title, children); card.setPrefWidth(370); return card;
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
