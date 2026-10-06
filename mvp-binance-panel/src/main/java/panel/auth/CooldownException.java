package panel.auth;

import java.time.Duration;

/** O serviço pediu para esperar antes de reenviar um código (intervalo mínimo, limite de envios ou limitador de tentativas). */
public class CooldownException extends RuntimeException {
    public final Duration retryAfter;

    public CooldownException(Duration retryAfter) {
        super("Wait " + Math.max(1, retryAfter.toSeconds()) + " s before requesting another code.");
        this.retryAfter = retryAfter;
    }
}
