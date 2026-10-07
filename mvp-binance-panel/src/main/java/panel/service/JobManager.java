package panel.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import panel.adapter.CommandAdapter;
import panel.adapter.CommandSpec;
import panel.model.JobRecord;
import panel.model.JobState;
import panel.model.LogEntry;
import panel.process.ExternalProcessDetector;
import panel.process.ProcessRunner;

/** Fila central de jobs. Um job por vez, sempre em thread de fundo; a UI só observa. */
public class JobManager {
    private static final int MAX_LOG = 5000;

    public final ObservableList<JobRecord> jobs = FXCollections.observableArrayList();
    public final ObservableList<LogEntry> logs = FXCollections.observableArrayList();
    public final ObservableList<ExternalProcessDetector.External> externals = FXCollections.observableArrayList();

    private final CommandAdapter adapter;
    private final Supplier<Path> workdir;
    private final Runnable onFinished;
    private final Runnable gate;
    private final panel.security.ServerAuthorizer authorizer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "job-worker");
        t.setDaemon(true);
        return t;
    });
    private final ProcessRunner runner = new ProcessRunner();
    private int seq;

    public JobManager(CommandAdapter adapter, Supplier<Path> workdir, Runnable onFinished, Runnable gate) {
        this(adapter, workdir, onFinished, gate, panel.security.ServerAuthorization.DENY_ALL);
    }

    /** Só para testes de domínio: a composição de produção usa o construtor acima (DENY_ALL). */
    public JobManager(CommandAdapter adapter, Supplier<Path> workdir, Runnable onFinished, Runnable gate, panel.security.ServerAuthorizer authorizer) {
        this.authorizer = java.util.Objects.requireNonNull(authorizer);
        this.gate = gate;
        this.adapter = adapter;
        this.workdir = workdir;
        this.onFinished = onFinished;
    }

    /** Deve ser chamado na thread da UI. Lança IllegalArgumentException/IllegalStateException se o comando for recusado. */
    public JobRecord submit(CommandSpec spec, String sessionId) {
        authorizer.require(panel.security.ServerOperation.RESEARCH_JOB_SUBMIT);
        gate.run();
        if (spec.heavy && !externals.isEmpty()) {
            throw new IllegalStateException("Another label job is already running (pid " + externals.get(0).pid() + "). Wait for it to finish.");
        }
        if (!adapter.available()) {
            throw new IllegalStateException("adaptive-trader CLI not available. Check Settings.");
        }
        List<String> cmd = adapter.build(spec, sessionId);
        String title = spec.needsSession ? spec.title + " · " + sessionId : spec.title;
        JobRecord job = new JobRecord("job-" + (++seq), spec, title, String.join(" ", cmd), false);
        jobs.add(0, job);
        log(LogEntry.Level.COMMAND, "$ " + job.command);
        worker.submit(() -> execute(job, cmd));
        return job;
    }

    private void execute(JobRecord job, List<String> cmd) {
        if (job.state.get() == JobState.CANCELLED) {
            return;
        }
        Platform.runLater(() -> {
            job.startedAt.set(Instant.now());
            job.state.set(JobState.RUNNING);
        });
        int code;
        try {
            code = runner.run(cmd, workdir.get(), line -> log(LogEntry.Level.INFO, line), line -> log(classify(line), line));
        } catch (IOException e) {
            log(LogEntry.Level.ERROR, "Failed to start: " + e.getMessage());
            code = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        int exit = code;
        Platform.runLater(() -> {
            job.exitCode.set(exit);
            job.finishedAt.set(Instant.now());
            if (job.state.get() != JobState.CANCELLED) {
                job.state.set(exit == 0 ? JobState.SUCCESS : JobState.FAILED);
            }
            logNow(exit == 0 ? LogEntry.Level.INFO : LogEntry.Level.ERROR, job.title + " finished with exit code " + exit);
            onFinished.run();
        });
    }

    private static LogEntry.Level classify(String line) {
        String u = line.toUpperCase();
        if (u.contains("ERROR") || u.contains("TRACEBACK") || u.contains("EXCEPTION")) {
            return LogEntry.Level.ERROR;
        }
        return u.contains("WARN") ? LogEntry.Level.WARN : LogEntry.Level.INFO;
    }

    public void cancel(JobRecord job) {
        authorizer.require(panel.security.ServerOperation.RESEARCH_JOB_CANCEL);
        gate.run();
        if (job.state.get() == JobState.QUEUED) {
            job.state.set(JobState.CANCELLED);
            job.finishedAt.set(Instant.now());
        } else if (job.state.get() == JobState.RUNNING) {
            job.state.set(JobState.CANCELLED);
            runner.cancel();
        }
    }

    public boolean running() {
        return jobs.stream().anyMatch(JobRecord::active);
    }

    public void log(LogEntry.Level level, String text) {
        Platform.runLater(() -> logNow(level, text));
    }

    private void logNow(LogEntry.Level level, String text) {
        logs.add(new LogEntry(Instant.now(), level, text));
        if (logs.size() > MAX_LOG) {
            logs.remove(0, logs.size() - MAX_LOG);
        }
    }
}
