package panel.ipc.contracts;

import java.util.Set;

/**
 * Isolated decision algebra, NOT an application attestation or a Service authorization provider.
 * A future trusted native verifier must establish live code identity; observations never do so.
 * Existing SocketChannel PeerVerifier/macOS policy remains unchanged and unadapted.
 */
public final class IdentityGate {
    private IdentityGate() { }

    public enum Status { VERIFIED, UNSUPPORTED, MISSING, INVALID, STALE, UNTRUSTED_APPLICATION }
    public enum Observation { USER, SID, PID, JAVA_EXECUTABLE, EXECUTABLE_PATH, LOCAL_FILES, CONNECTED }

    /** Instance binding is opaque, not a PID or a caller claim that the gate attests. */
    public record Evidence(long generation, String instanceBinding, Set<Observation> observations) {
        public Evidence { observations = Set.copyOf(observations); }
    }

    /** Verdict from a separately trusted verifier; time is nonnegative monotonic elapsed ticks, not wall time. */
    public record Result(Status status, Evidence binding, long validUntilNanos) { }

    @FunctionalInterface
    public interface Verifier { Result verify(Evidence evidence); }

    /**
     * Snapshot of an identity prerequisite, never a reusable capability. Callers must recheck
     * lifecycle validity at use; this grants neither pairing, a user session nor an operation.
     */
    public record Decision(Status status) {
        public Decision { java.util.Objects.requireNonNull(status); }
        public boolean identitySatisfied() { return status == Status.VERIFIED; }
    }

    public static Decision evaluate(Verifier verifier, Evidence evidence, long generation, long nowNanos) {
        if (verifier == null) return new Decision(Status.UNSUPPORTED);
        if (evidence == null) return new Decision(Status.MISSING);
        if (generation <= 0 || evidence.generation() <= 0 || evidence.instanceBinding() == null
                || evidence.instanceBinding().isBlank() || nowNanos < 0) return new Decision(Status.INVALID);
        if (evidence.generation() != generation) return new Decision(Status.STALE);
        final Result result;
        try { result = verifier.verify(evidence); }
        catch (RuntimeException | LinkageError e) { return new Decision(Status.INVALID); }
        if (result == null || result.status() == null) return new Decision(Status.INVALID);
        if (result.status() != Status.VERIFIED) return new Decision(result.status());
        if (!evidence.equals(result.binding())) return new Decision(Status.INVALID);
        if (result.validUntilNanos() <= nowNanos) return new Decision(Status.STALE);
        return new Decision(Status.VERIFIED);
    }
}
