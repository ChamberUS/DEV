package panel.homeview;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where the app opens after sign-in and whether the one-step welcome was shown, scoped by ACCOUNT (never global).
 *
 * <p><b>Not persisted, on purpose.</b> Every preference write is denied by {@code ServerAuthorization} in this build (SETTINGS_PERSIST), and
 * D-11 (a user preference channel) does not exist yet. So the value lives for the process only and the screens say so
 * ({@link #persistent()} is false). It is purely presentational: it picks which page opens first and can never widen what an account may
 * open; the router gate still decides every destination.
 */
public final class LandingPreference {
    public enum Start { HOME, TERMINAL }

    private final Map<String, Start> chosen = new HashMap<>();
    private final Set<String> welcomed = new HashSet<>();

    /** Stable key for an account. The id comes from the authenticated session, never from user-typed text. */
    public static String key(long userId) {
        return "uid:" + userId;
    }

    /** Home is the default for everyone who has not chosen otherwise. */
    public synchronized Start startFor(String account) {
        return chosen.getOrDefault(account, Start.HOME);
    }

    public synchronized boolean hasChoice(String account) {
        return chosen.containsKey(account);
    }

    public synchronized void choose(String account, Start start) {
        chosen.put(account, start == null ? Start.HOME : start);
    }

    public synchronized boolean welcomeSeen(String account) {
        return welcomed.contains(account);
    }

    public synchronized void markWelcomeSeen(String account) {
        welcomed.add(account);
    }

    /** The welcome is replayed only on explicit request: clearing the flag lets the caller open it once. */
    public synchronized void forgetWelcome(String account) {
        welcomed.remove(account);
    }

    public boolean persistent() {
        return false;
    }

    /** Route to open first. Terminal falls back to Home when the Terminal route does not exist for the session. */
    public synchronized String route(String account, java.util.function.Predicate<String> routeExists) {
        if (startFor(account) == Start.TERMINAL && routeExists.test("t-desk")) {
            return "t-desk";
        }
        return "t-home";
    }
}
