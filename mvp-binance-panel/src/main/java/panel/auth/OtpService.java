package panel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** OTP de 6 dígitos: SecureRandom, validade, uso único, limite de tentativas e cooldown de reenvio. Só guarda HMAC. */
public class OtpService {
    public enum Channel { EMAIL, SMS }

    public enum Result { OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS, NO_CHALLENGE }

    public static class CooldownException extends RuntimeException {
        public final Duration retryAfter;

        public CooldownException(Duration retryAfter) {
            super("Please wait " + (retryAfter.toSeconds() + 1) + "s before requesting a new code.");
            this.retryAfter = retryAfter;
        }
    }

    private static final class Challenge {
        byte[] key;
        byte[] mac;
        Instant expiresAt;
        Instant sentAt;
        int attempts;
        boolean used;
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final Clock clock;
    private final Duration validity;
    private final Duration cooldown;
    private final int maxAttempts;

    public OtpService(Clock clock) {
        this(clock, Duration.ofMinutes(5), Duration.ofSeconds(30), 5);
    }

    public OtpService(Clock clock, Duration validity, Duration cooldown, int maxAttempts) {
        this.clock = clock;
        this.validity = validity;
        this.cooldown = cooldown;
        this.maxAttempts = maxAttempts;
    }

    /** Gera um novo código (invalida o anterior). O chamador entrega ao provider; nunca deve logá-lo. */
    public synchronized String issue(long userId, Channel channel) {
        String k = key(userId, channel);
        Instant now = clock.instant();
        Challenge old = challenges.get(k);
        if (old != null && now.isBefore(old.sentAt.plus(cooldown))) {
            throw new CooldownException(Duration.between(now, old.sentAt.plus(cooldown)));
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        Challenge c = new Challenge();
        c.key = new byte[32];
        random.nextBytes(c.key);
        c.mac = mac(c.key, code);
        c.sentAt = now;
        c.expiresAt = now.plus(validity);
        challenges.put(k, c);
        return code;
    }

    public synchronized Result verify(long userId, Channel channel, String code) {
        Challenge c = challenges.get(key(userId, channel));
        if (c == null || c.used) {
            return Result.NO_CHALLENGE;
        }
        if (!clock.instant().isBefore(c.expiresAt)) {
            challenges.remove(key(userId, channel));
            return Result.EXPIRED;
        }
        if (c.attempts >= maxAttempts) {
            return Result.TOO_MANY_ATTEMPTS;
        }
        c.attempts++;
        if (code == null || !code.matches("\\d{6}") || !MessageDigest.isEqual(c.mac, mac(c.key, code))) {
            return Result.INVALID;
        }
        c.used = true;
        return Result.OK;
    }

    public synchronized void clear(long userId) {
        for (Channel ch : Channel.values()) {
            challenges.remove(key(userId, ch));
        }
    }

    private static String key(long userId, Channel channel) {
        return userId + ":" + channel;
    }

    private static byte[] mac(byte[] key, String code) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(code.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
