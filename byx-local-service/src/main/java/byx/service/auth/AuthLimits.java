package byx.service.auth;

import java.time.Duration;

/** TODOS os prazos e limites de autenticação, num só lugar (e testados). Nada de sessão eterna. */
public final class AuthLimits {
    /** Tempo máximo de uma sessão, desde o login. */
    public static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);
    /** Inatividade máxima entre duas requisições autorizadas. */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);
    /** Duração da elevação administrativa (propriedade TEMPORÁRIA da sessão, nunca do papel). */
    public static final Duration ADMIN_ELEVATION_TIMEOUT = Duration.ofMinutes(5);
    /** Janela em que um segundo fator concluído conta como "recente" (para elevação e operações que o exigem). */
    public static final Duration RECENT_MFA_WINDOW = Duration.ofMinutes(10);
    /** OTP: validade ABSOLUTA do desafio (reenvio não a renova), tentativas, reenvios e intervalo mínimo entre envios. */
    public static final Duration OTP_TTL = Duration.ofMinutes(5);
    public static final int OTP_MAX_ATTEMPTS = 5;
    public static final int OTP_MAX_SENDS = 3;
    public static final Duration OTP_RESEND_COOLDOWN = Duration.ofSeconds(30);
    /** Limitador de tentativas (mesma política do L4 do painel). */
    public static final int RATE_FREE_FAILURES = 4;
    public static final Duration RATE_FIRST_DELAY = Duration.ofSeconds(30);
    public static final Duration RATE_CAP = Duration.ofMinutes(5);
    public static final Duration RATE_DECAY = Duration.ofMinutes(30);
    public static final int RATE_MAX_ROWS = 5_000;
    /** Senha: tamanho mínimo exigido ao DEFINIR e máximo aceito (bytes UTF-8, para limitar o custo do Argon2). */
    public static final int PASSWORD_MIN_CHARS = 12;
    public static final int PASSWORD_MAX_BYTES = 256;
    /** Sessões simultâneas no serviço (memória limitada). */
    public static final int MAX_SESSIONS = 64;

    private AuthLimits() {
    }
}
