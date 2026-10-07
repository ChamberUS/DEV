package byx.service.chain;

import java.util.concurrent.atomic.AtomicLong;

/** Saúde por módulo, separada do estado do nó: um módulo com erro não derruba a chain para OFFLINE. Contadores locais, voláteis, sem parâmetros nem identificadores. */
public final class ModuleHealth {
    public enum State { AVAILABLE, UNAVAILABLE, DEGRADED, NOT_EXPOSED, UNKNOWN }

    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile long lastLatencyMs = -1;
    private volatile long lastSuccessMs;
    private volatile long attempts;
    private volatile boolean lastOk;
    private volatile ReadFailure lastFailure;

    void record(boolean answered, ReadFailure failure, long latencyMs, long now) {
        attempts++;
        lastLatencyMs = latencyMs;
        lastOk = answered;
        if (answered) {
            ok.incrementAndGet();
            lastSuccessMs = now;
            lastFailure = null;
        } else {
            failed.incrementAndGet();
            lastFailure = failure;
        }
    }

    public record Snapshot(ReadOp.Module module, State state, long ok, long failed, long lastLatencyMs, long lastSuccessAgeMs, ReadFailure lastFailure) { }

    Snapshot snapshot(ReadOp.Module module, long now) {
        State s;
        if (module == ReadOp.Module.FEESPLIT) {
            s = State.NOT_EXPOSED;
        } else if (attempts == 0) {
            s = State.UNKNOWN;
        } else if (lastOk) {
            s = State.AVAILABLE;
        } else {
            s = lastSuccessMs > 0 && now - lastSuccessMs < 60_000 ? State.DEGRADED : State.UNAVAILABLE;
        }
        return new Snapshot(module, s, ok.get(), failed.get(), lastLatencyMs, lastSuccessMs == 0 ? -1 : Math.max(0, now - lastSuccessMs), lastFailure);
    }
}
