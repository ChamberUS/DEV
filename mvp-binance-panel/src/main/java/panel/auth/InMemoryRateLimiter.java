package panel.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Bloqueio temporário após N falhas seguidas. Estado só em memória (app local). */
public class InMemoryRateLimiter implements RateLimiter {
    private static final class State {
        int failures;
        Instant blockedUntil;
    }

    private final Map<String, State> states = new HashMap<>();
    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;

    public InMemoryRateLimiter(int maxFailures, Duration lockout, Clock clock) {
        this.maxFailures = maxFailures;
        this.lockout = lockout;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<Duration> blockedFor(String key) {
        State s = states.get(key);
        if (s == null || s.blockedUntil == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(s.blockedUntil)) {
            states.remove(key);
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, s.blockedUntil));
    }

    @Override
    public synchronized void recordFailure(String key) {
        State s = states.computeIfAbsent(key, k -> new State());
        if (++s.failures >= maxFailures) {
            s.blockedUntil = clock.instant().plus(lockout);
            s.failures = 0;
        }
    }

    @Override
    public synchronized void recordSuccess(String key) {
        states.remove(key);
    }
}
