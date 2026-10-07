package byx.service.account;

import java.time.Duration;

/**
 * Política de requisição da API privada futura (design; só leitura, logo todo GET é idempotente): requisições limitadas, uma por vez, intervalo mínimo por operação, janela de recebimento
 * curta gerada pelo serviço, 429/418 bloqueiam toda atividade pelo tempo que o provedor mandar (nunca menos que o piso), 5xx tem poucas tentativas com recuo exponencial limitado, 4xx não
 * repete. Nunca uma tempestade de tentativas e nunca uma operação com efeito colateral repetida (não existe operação mutável).
 */
public final class AccountRequestPolicy {
    public static final Duration MIN_INTERVAL_PER_OPERATION = Duration.ofSeconds(2);
    public static final int MAX_IN_FLIGHT = 1;
    public static final int MAX_ATTEMPTS_ON_5XX = 3;
    public static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    public static final Duration BACKOFF_CAP = Duration.ofSeconds(30);
    public static final Duration RATE_LIMIT_BLOCK_MIN = Duration.ofSeconds(60);
    public static final Duration RECV_WINDOW = Duration.ofMillis(5_000);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private AccountRequestPolicy() { }

    /** 4xx (exceto 429/418) nunca repete; 5xx repete até o limite; 429/418 não repete: bloqueia. */
    public static boolean retryable(int status, int attemptsSoFar) {
        return status >= 500 && status < 600 && attemptsSoFar < MAX_ATTEMPTS_ON_5XX;
    }

    public static boolean blocksAllActivity(int status) {
        return status == 429 || status == 418;
    }

    public static Duration backoff(int attemptsSoFar) {
        long ms = Math.min(BACKOFF_CAP.toMillis(), BACKOFF_BASE.toMillis() << Math.min(20, Math.max(0, attemptsSoFar - 1)));
        return Duration.ofMillis(Math.max(1, ms));
    }
}
