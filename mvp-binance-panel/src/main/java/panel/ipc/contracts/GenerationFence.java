package panel.ipc.contracts;

/**
 * Pure, synchronized lifecycle model; not a Service epoch, peer key, credential or session issuer.
 * Positive generations scope callbacks/leases; strictly increasing event sequences scope a stream.
 * No integration into existing MarketFeedClient/SessionStore. Restart conveys no identity authority.
 */
public final class GenerationFence {
    private long generation;
    private long sequence;
    private boolean closed;

    public GenerationFence() { this(1); }
    public GenerationFence(long initial) {
        if (initial <= 0) throw new IllegalArgumentException("invalid_generation");
        generation = initial;
    }

    public synchronized long current() { return generation; }
    public synchronized boolean isCurrent(long candidate) {
        return !closed && candidate > 0 && candidate == generation;
    }

    public synchronized long restart() {
        if (closed) throw new IllegalStateException("fence_closed");
        if (generation == Long.MAX_VALUE) {
            closed = true; // Never wrap to an old generation or preserve a live lease on exhaustion.
            throw new IllegalStateException("generation_exhausted");
        }
        sequence = 0;
        return ++generation;
    }

    public synchronized boolean acceptEvent(long candidate, long nextSequence) {
        if (!isCurrent(candidate) || nextSequence <= sequence) return false;
        sequence = nextSequence;
        return true;
    }

    public synchronized void close() { closed = true; }
}
