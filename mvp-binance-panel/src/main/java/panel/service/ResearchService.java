package panel.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import panel.adapter.ResearchBackend;
import panel.model.DataSource;
import panel.model.JobRecord;
import panel.model.JobState;
import panel.model.LogEntry;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.process.ExternalProcessDetector;

/** Mantém o Snapshot atual, relendo em background (polling + após cada job). */
public class ResearchService {
    public final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>(loadingSnapshot());

    private final Settings settings;
    private final ResearchBackend real;
    private final ResearchBackend mock;
    private final JobManager jobs;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "poller");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> task;
    private java.util.List<String> lastWarnings = java.util.List.of();

    private static Snapshot loadingSnapshot() {
        Snapshot s = new Snapshot();
        s.loading = true;
        return s;
    }

    public ResearchService(Settings settings, ResearchBackend real, ResearchBackend mock, JobManager jobs) {
        this.settings = settings;
        this.real = real;
        this.mock = mock;
        this.jobs = jobs;
    }

    public void start() {
        reschedule();
    }

    public void reschedule() {
        if (task != null) {
            task.cancel(false);
        }
        task = poller.scheduleWithFixedDelay(this::poll, 0, Math.max(2, settings.pollSeconds), TimeUnit.SECONDS);
    }

    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();

    public void refresh() {
        if (refreshQueued.compareAndSet(false, true)) poller.execute(() -> {
            try {
                if (real instanceof panel.adapter.BackendGateway gateway) gateway.invalidate();
                poll();
            } finally { refreshQueued.set(false); }
        });
    }

    public void close() { poller.shutdownNow(); }

    private void poll() {
        try {
            var externals = settings.dataSource == DataSource.REAL ? ExternalProcessDetector.find() : java.util.List.<ExternalProcessDetector.External>of();
            Snapshot s = (settings.dataSource == DataSource.MOCK ? mock : real).load(settings);
            Platform.runLater(() -> publish(s, externals));
        } catch (RuntimeException e) {
            Snapshot s = new Snapshot();
            s.backendNote = "BACKEND OFFLINE";
            s.warnings.add("Backend refresh unavailable (" + e.getClass().getSimpleName() + ")");
            Platform.runLater(() -> publish(s, java.util.List.of()));
        }
    }

    private void publish(Snapshot s, java.util.List<ExternalProcessDetector.External> externals) {
        jobs.externals.setAll(externals);
        if (!s.warnings.equals(lastWarnings)) {
            s.warnings.stream().filter(w -> !lastWarnings.contains(w)).forEach(w -> jobs.log(LogEntry.Level.WARN, w));
            lastWarnings = java.util.List.copyOf(s.warnings);
        }
        for (JobRecord j : jobs.jobs) {
            if (j.state.get() == JobState.RUNNING && j.spec != null && j.spec.subcommand.startsWith("label-run")) {
                j.progress.set(panel.util.Fmt.ratio(s.labelDone, s.labelsTotalSessions()) + " sessions");
            }
        }
        snapshot.set(s);
    }

    /** Há geração de labels em andamento (job do painel ou processo externo). */
    public boolean labelsRunning() {
        return jobs.jobs.stream().anyMatch(j -> j.state.get() == JobState.RUNNING && j.spec.subcommand.startsWith("label-run"))
                || jobs.externals.stream().anyMatch(e -> e.command().contains("label-run"));
    }
}
