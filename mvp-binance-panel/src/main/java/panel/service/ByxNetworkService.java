package panel.service;

import java.time.*;
import java.util.concurrent.*;
import panel.adapter.ByxChainGateway;
import panel.model.*;
import panel.security.AdminGate;

public final class ByxNetworkService implements AutoCloseable {
    private final ByxChainGateway gateway;
    private final AdminGate gate;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "byx-read-only"); t.setDaemon(true); return t;
    });
    private ByxConfig config;
    private long generation;
    private boolean active = true;
    private CompletableFuture<ByxSnapshot> pending;
    private ByxSnapshot cached;
    public ByxNetworkService(ByxChainGateway gateway, AdminGate gate, Clock clock) {
        this.gateway = gateway; this.gate = gate; this.clock = clock;
        cached = ByxSnapshot.unknown(gateway.source(), "UNKNOWN", "UNKNOWN", "Not configured");
        worker.scheduleWithFixedDelay(this::refresh, 30, 30, TimeUnit.SECONDS);
    }
    public synchronized void configure(ByxConfig config) {
        gate.requireAdmin();
        generation++; this.config = config;
        cached = ByxSnapshot.unknown(gateway.source(), config.environment(), "UNKNOWN", "Awaiting identity verification");
    }
    synchronized ByxConfig walletConfig() { return config; }
    public synchronized ByxConfig details() { gate.requireAdmin(); return config; }
    public synchronized ByxSnapshot snapshot() {
        if (cached.updatedAt() != null && (cached.updatedAt().isBefore(clock.instant().minusSeconds(60))
                || cached.blockTime().isBefore(clock.instant().minusSeconds(60))))
            return cached.stale(cached.connection(), cached.message());
        return cached;
    }
    public synchronized CompletableFuture<ByxSnapshot> refresh() {
        if (pending != null && !pending.isDone()) return pending;
        if (!active || config == null) return CompletableFuture.completedFuture(snapshot());
        ByxConfig selected = config; long version = generation;
        pending = CompletableFuture.supplyAsync(() -> {
            ByxSnapshot result;
            try { result = gateway.read(selected); }
            catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                synchronized (this) {
                    result = cached.updatedAt() == null ? ByxSnapshot.unknown(gateway.source(), selected.environment(),
                            "OFFLINE", "Node unavailable or invalid response; balance UNKNOWN") : cached.stale("OFFLINE", "Node unavailable; cached data");
                }
            }
            synchronized (this) { if (version == generation) cached = result; return snapshot(); }
        }, worker);
        return pending;
    }
    public synchronized void start() { active = true; refresh(); }
    public synchronized void pause() {
        active = false; generation++;
        cached = ByxSnapshot.unknown(gateway.source(), "UNKNOWN", "UNKNOWN", "Session ended");
    }
    public synchronized void reset() {
        generation++; config = null;
        cached = ByxSnapshot.unknown(gateway.source(), "UNKNOWN", "UNKNOWN", "Not configured");
    }
    public void close() { reset(); worker.shutdownNow(); }
}
