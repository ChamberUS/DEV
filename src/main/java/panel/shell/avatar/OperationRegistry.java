package panel.shell.avatar;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Registry of REAL pending operations and the presentation derived from them (L2 of the avatar). Pure: the clock is injected, nothing here
 * is JavaFX, and nothing here starts, cancels, retries or judges an operation.
 *
 * <ul>
 *   <li>Each operation has its own {@link Operations.Token}; {@code end} affects exactly that operation. A token from before
 *       {@link #clearAll()} (logout/login) or from another registry is ignored, so a late callback can never finish or overwrite the
 *       status of a newer, unrelated operation.</li>
 *   <li>LOADING appears only after an operation has been pending {@code showDelayMs}, stays at least {@code minShowMs}, and counts only
 *       operations that have not hit the presentation watchdog.</li>
 *   <li>The watchdog ({@code deadlineMs}) is PRESENTATION ONLY: it raises the "did not finish" look for {@code errorHoldMs} and stops
 *       counting that operation toward LOADING. It never cancels, retries or marks anything as failed: a custody/signing result that is
 *       still UNKNOWN stays UNKNOWN in its own owner; the avatar merely stops claiming it is busy.</li>
 *   <li>A failure of one operation is shown even if another continues; afterwards the presentation returns to LOADING if still pending.</li>
 * </ul>
 */
public final class OperationRegistry implements Operations {
    public enum Presentation { NONE, LOADING, ERROR }

    private static final class Op implements Token {
        final long id;
        final long epoch;
        final String name;
        final long began;
        final long deadline;
        boolean watchdogFired;

        Op(long id, long epoch, String name, long began, long deadline) {
            this.id = id;
            this.epoch = epoch;
            this.name = name;
            this.began = began;
            this.deadline = deadline;
        }
    }

    private final LongSupplier clockMs;
    private final MascotTokens tokens;
    private final Map<Long, Op> active = new LinkedHashMap<>();
    private long nextId = 1;
    private long epoch = 1;
    private long errorUntil = -1;
    private String errorWhy;
    private long loadingSince = -1;
    private long loadingHoldUntil = -1;
    private Runnable onChange = () -> { };

    public OperationRegistry(LongSupplier clockMs, MascotTokens tokens) {
        this.clockMs = clockMs;
        this.tokens = tokens;
    }

    /** Called (possibly from any thread) after begin/end/clearAll so the owner can wake its single timer. Must be cheap and non-blocking. */
    public synchronized void setOnChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    @Override
    public Token begin(String name) {
        Runnable notify;
        Op op;
        synchronized (this) {
            long now = clockMs.getAsLong();
            op = new Op(nextId++, epoch, name == null ? "operation" : name, now, now + tokens.deadlineMs());
            active.put(op.id, op);
            notify = onChange;
        }
        notify.run();
        return op;
    }

    @Override
    public void end(Token token, boolean ok, String why) {
        Runnable notify;
        synchronized (this) {
            if (!(token instanceof Op op) || op.epoch != epoch || active.remove(op.id) == null) {
                return; // stale (before clearAll), foreign or repeated: no effect on anything
            }
            if (!ok && !op.watchdogFired) {
                long now = clockMs.getAsLong();
                errorUntil = Math.max(errorUntil, now + tokens.errorHoldMs());
                errorWhy = why;
            }
            notify = onChange;
        }
        notify.run();
    }

    /** Session change (login/logout): forgets everything, including a visible error, and invalidates every outstanding token. */
    public void clearAll() {
        Runnable notify;
        synchronized (this) {
            epoch++;
            active.clear();
            errorUntil = -1;
            errorWhy = null;
            loadingSince = -1;
            loadingHoldUntil = -1;
            notify = onChange;
        }
        notify.run();
    }

    public synchronized int pending() {
        return active.size();
    }

    public synchronized String errorReason() {
        return errorWhy;
    }

    /** Current presentation. Idempotent for a given instant; call it from the animation tick. */
    public synchronized Presentation presentation() {
        long now = clockMs.getAsLong();
        for (Op op : active.values()) {
            if (!op.watchdogFired && now >= op.deadline) {
                op.watchdogFired = true; // presentation only; the operation itself is untouched and stays registered until its owner ends it
                errorUntil = Math.max(errorUntil, now + tokens.errorHoldMs());
                errorWhy = "DID_NOT_FINISH";
            }
        }
        if (now < errorUntil) {
            loadingSince = -1;
            return Presentation.ERROR;
        }
        boolean eligible = false;
        for (Op op : active.values()) {
            if (!op.watchdogFired && now - op.began >= tokens.showDelayMs()) {
                eligible = true;
                break;
            }
        }
        if (eligible) {
            if (loadingSince < 0) {
                loadingSince = now;
                loadingHoldUntil = now + tokens.minShowMs();
            }
            return Presentation.LOADING;
        }
        if (loadingSince >= 0 && now < loadingHoldUntil) {
            return Presentation.LOADING; // minimum visible time: no flicker when the work ends right after the rings appeared
        }
        loadingSince = -1;
        return Presentation.NONE;
    }

    /** True while the presentation may still change with time alone (delay, minimum, hold or watchdog pending). */
    public synchronized boolean timeSensitive() {
        long now = clockMs.getAsLong();
        return !active.isEmpty() || now < errorUntil || loadingSince >= 0 && now < loadingHoldUntil;
    }
}
