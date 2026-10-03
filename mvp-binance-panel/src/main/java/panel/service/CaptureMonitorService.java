package panel.service;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;
import panel.adapter.CaptureProcessProbe;
import panel.model.CaptureSnapshot;
import panel.security.AccessDeniedException;

/** One background reader; all authorization checks and publications run on FX. */
public final class CaptureMonitorService implements AutoCloseable {
    private final CaptureProcessProbe probe;
    private final Runnable gate;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capture-monitor"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private ScheduledFuture<?> task;
    private long generation;
    private Consumer<CaptureSnapshot> listener;
    public CaptureMonitorService(CaptureProcessProbe probe, Runnable gate) { this.probe = probe; this.gate = gate; }
    public void start(Consumer<CaptureSnapshot> listener) {
        gate.run(); stop(); this.listener = listener; long token = generation;
        task = worker.scheduleWithFixedDelay(() -> poll(token), 0, 5, TimeUnit.SECONDS);
    }
    public void refresh() {
        gate.run(); long token = generation;
        if (listener != null && queued.compareAndSet(false, true)) worker.execute(() -> {
            try { poll(token); } finally { queued.set(false); }
        });
    }
    private void poll(long token) {
        CaptureSnapshot result;
        try { result = probe.read(); }
        catch (Exception e) { result = CaptureSnapshot.unknown(Instant.now(), "Capture observation unavailable: " + e.getClass().getSimpleName()); }
        CaptureSnapshot snapshot = result;
        Platform.runLater(() -> {
            if (token != generation || listener == null) return;
            try { gate.run(); listener.accept(snapshot); }
            catch (AccessDeniedException e) { stop(); }
        });
    }
    public void stop() {
        generation++; listener = null;
        if (task != null) { task.cancel(false); task = null; }
    }
    @Override public void close() { stop(); worker.shutdownNow(); }
}
