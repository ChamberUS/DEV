package byx.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Desafios de segundo fator (em memória). Contrato: código de 6 dígitos de SecureRandom; só o HMAC (chave por desafio) é guardado; USO ÚNICO;
 * validade ABSOLUTA contada do primeiro envio (reenvio troca o código mas NÃO renova a validade nem zera as tentativas); limite de
 * tentativas e de envios; intervalo mínimo entre envios; vinculado à conta E à sessão (um desafio de outra sessão/conta é "inválido");
 * o código nunca é devolvido ao chamador de IPC (só ao provedor de entrega).
 */
final class OtpChallenges {
    enum Outcome { OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS, NO_CHALLENGE, COOLDOWN, TOO_MANY_SENDS }

    record Issued(String challengeId, char[] code, long expiresAtMs, long resendAfterMs) {
    }

    static final class Cooldown extends Exception {
        final long retryAfterMs;

        Cooldown(long retryAfterMs, boolean tooMany) {
            super(tooMany ? "TOO_MANY_SENDS" : "COOLDOWN", null, false, false);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class Challenge {
        final String id;
        final String accountId;
        final String sessionId;
        final long expiresAtMs;
        byte[] key = new byte[32];
        byte[] mac;
        int attempts;
        int sends = 1;
        long lastSentMs;
        boolean used;

        Challenge(String id, String accountId, String sessionId, long expiresAtMs) {
            this.id = id;
            this.accountId = accountId;
            this.sessionId = sessionId;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> bySession = new HashMap<>();
    private final Clock clock;

    OtpChallenges(Clock clock) {
        this.clock = clock;
    }

    /** Emite (ou REENVIA) o desafio da sessão. O chamador entrega o código ao provedor e o zera. */
    synchronized Issued issue(String sessionId, String accountId) throws Cooldown {
        long now = clock.millis();
        Challenge c = bySession.get(sessionId);
        if (c != null && !c.used && now < c.expiresAtMs && c.attempts < AuthLimits.OTP_MAX_ATTEMPTS) {
            long wait = c.lastSentMs + AuthLimits.OTP_RESEND_COOLDOWN.toMillis() - now;
            if (wait > 0) {
                throw new Cooldown(wait, false);
            }
            if (c.sends >= AuthLimits.OTP_MAX_SENDS) {
                throw new Cooldown(c.expiresAtMs - now, true);
            }
            c.sends++; // reenvio: novo código; validade e tentativas continuam as do desafio original
        } else {
            byte[] idRaw = new byte[16];
            random.nextBytes(idRaw);
            c = new Challenge(Base64.getUrlEncoder().withoutPadding().encodeToString(idRaw), accountId, sessionId, now + AuthLimits.OTP_TTL.toMillis());
            bySession.put(sessionId, c);
        }
        char[] code = String.format("%06d", random.nextInt(1_000_000)).toCharArray();
        random.nextBytes(c.key);
        c.mac = mac(c.key, code);
        c.lastSentMs = now;
        return new Issued(c.id, code, c.expiresAtMs, now + AuthLimits.OTP_RESEND_COOLDOWN.toMillis());
    }

    synchronized Outcome verify(String sessionId, String accountId, String challengeId, String code) {
        Challenge c = bySession.get(sessionId);
        if (c == null || c.used || !c.accountId.equals(accountId) || !MessageDigest.isEqual(c.id.getBytes(StandardCharsets.UTF_8), String.valueOf(challengeId).getBytes(StandardCharsets.UTF_8))) {
            return Outcome.NO_CHALLENGE;
        }
        if (clock.millis() >= c.expiresAtMs) {
            bySession.remove(sessionId);
            return Outcome.EXPIRED;
        }
        if (c.attempts >= AuthLimits.OTP_MAX_ATTEMPTS) {
            return Outcome.TOO_MANY_ATTEMPTS;
        }
        c.attempts++;
        if (code == null || !code.matches("\\d{6}") || !MessageDigest.isEqual(c.mac, mac(c.key, code.toCharArray()))) {
            return Outcome.INVALID;
        }
        c.used = true;
        bySession.remove(sessionId);
        return Outcome.OK;
    }

    synchronized void cancelSession(String sessionId) {
        bySession.remove(sessionId);
    }

    synchronized void cancelAccount(String accountId) {
        bySession.values().removeIf(c -> c.accountId.equals(accountId));
    }

    private static byte[] mac(byte[] key, char[] code) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(new String(code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
