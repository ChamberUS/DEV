package panel.service;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import panel.adapter.CaptureProcessProbe;
import panel.model.CaptureSnapshot;
import panel.security.AccessDeniedException;

/** One background reader: Service authorization and metadata I/O on the worker, fenced publication on FX. */
public final class CaptureMonitorService implements AutoCloseable {
    private final CaptureProcessProbe probe;
    private final Supplier<Runnable> authorization;
    private final BooleanSupplier canObserve;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capture-monitor"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();
    private ScheduledFuture<?> task;
    private volatile long generation;
    private volatile Consumer<CaptureSnapshot> listener;
    public CaptureMonitorService(CaptureProcessProbe probe, Supplier<Runnable> authorization, BooleanSupplier canObserve) {
        this.probe = probe; this.authorization = authorization; this.canObserve = canObserve;
    }
    private void presentationGate() {
        if (!canObserve.getAsBoolean()) throw new AccessDeniedException("Administrator session required");
    }
    public void start(Consumer<CaptureSnapshot> listener) {
        presentationGate(); stop(); this.listener = listener; long token = generation;
        Runnable gate = authorization.get(); // capture the initiating session before queuing
        task = worker.scheduleWithFixedDelay(() -> poll(token, gate), 0, 5, TimeUnit.SECONDS);
    }
    public void refresh() {
        presentationGate(); long token = generation;
        Runnable gate = authorization.get();
        if (listener != null && queued.compareAndSet(false, true)) worker.execute(() -> {
            try { poll(token, gate); } finally { queued.set(false); }
        });
    }
    private void poll(long token, Runnable gate) {
        if (token != generation || listener == null) return;
        CaptureSnapshot result;
        try {
            gate.run(); // verified Service state, off FX
            if (token != generation || listener == null) return;
            result = probe.read();
        }
        catch (Exception e) { result = CaptureSnapshot.unknown(Instant.now(), "Capture observation unavailable: " + e.getClass().getSimpleName()); }
        CaptureSnapshot snapshot = result;
        Platform.runLater(() -> {
            if (token != generation || listener == null) return;
            try { presentationGate(); listener.accept(snapshot); }
            catch (AccessDeniedException e) { stop(); }
        });
    }
    public void stop() {
        generation++; listener = null;
        if (task != null) { task.cancel(false); task = null; }
    }
    @Override public void close() { stop(); worker.shutdownNow(); }
}
