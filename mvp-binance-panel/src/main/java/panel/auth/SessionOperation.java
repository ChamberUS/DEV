package panel.auth;

/** Worker action with an atomic, cache-only completion owned by its authentication epoch. */
public interface SessionOperation extends Runnable {
    boolean deliver(Runnable callback);
}
