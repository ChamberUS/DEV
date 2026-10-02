package panel.auth;

import java.time.Duration;
import java.util.Optional;

public interface RateLimiter {
    /** Tempo restante de bloqueio para a chave, se houver. */
    Optional<Duration> blockedFor(String key);

    void recordFailure(String key);

    void recordSuccess(String key);
}
