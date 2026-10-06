package panel.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import panel.security.Database;

/**
 * Limitador de tentativas com estado que SOBREVIVE a reinício (tabela {@code rate_limits} do banco local), por assunto.
 *
 * <ul>
 *   <li><b>Assunto</b> = HMAC-SHA256 (sal local aleatório guardado no banco) do identificador digitado, minúsculo e limitado a 256 caracteres.
 *       O banco guarda só essa impressão e contadores: nunca o texto digitado, senha ou OTP. Conta inexistente e existente seguem o MESMO
 *       caminho (o assunto é o texto digitado, não a conta), então a resposta não revela existência; e dois identificadores da mesma
 *       conta ("bob" e "bob@x") têm contadores separados, porque unificá-los revelaria que são a mesma conta.</li>
 *   <li><b>Atraso progressivo e NUNCA permanente:</b> depois de {@code freeFailures} falhas, 30 s, 60 s, 2 min… até o TETO (5 min). Tentativas feitas
 *       durante o bloqueio são recusadas sem verificar e <b>não aumentam</b> nem prolongam nada; o bloqueio termina sozinho no instante
 *       gravado. Falha antiga expira (decaimento de 30 min sem falhas) e sucesso zera.</li>
 *   <li><b>Sem DoS fácil:</b> o pior que alguém consegue contra outra conta é manter, no máximo, o teto de 5 min a cada rearme (e cada
 *       rearme exige uma falha real, verificada com Argon2). Não há bloqueio global: num app local não existe "origem" de rede, e um
 *       bloqueio por instalação seria exatamente o DoS de uma pessoa contra o único usuário local.</li>
 *   <li><b>Armazenamento limitado:</b> no máximo {@code maxRows} linhas; vencidas saem primeiro, depois as mais antigas JÁ desbloqueadas.
 *       Linha bloqueada nunca é descartada para dar lugar a outra.</li>
 *   <li><b>Relógio:</b> relógio que volta nunca estende um bloqueio além do teto; valores gravados corrompidos (negativos, no futuro
 *       distante) são saneados para dentro dos limites; relógio que avança só expira estados (quem controla o relógio já é o dono da máquina).</li>
 *   <li><b>Falha segura:</b> se o banco falhar, o espelho em memória continua impondo os limites (nunca "sem limite por erro de disco").</li>
 * </ul>
 */
public final class PersistentRateLimiter implements RateLimiter {
    /** Política. Padrão de login: 4 falhas livres, 5ª → 30 s, dobra até 5 min, decai em 30 min, 5000 linhas. */
    public record Policy(int freeFailures, Duration firstDelay, Duration cap, Duration decay, int maxRows) {
        public static Policy login() {
            return new Policy(4, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30), 5_000);
        }
    }

    private record Row(int failures, long lastAt, long nextAllowedAt) {
    }

    private static final int MAX_KEY = 256;
    private static final int MAX_FAILURES = 1_000;

    private final Database db;
    private final Clock clock;
    private final Policy policy;
    private final String scope;
    private final Map<String, Row> shadow = new HashMap<>();
    private byte[] pepper;

    public PersistentRateLimiter(Database db, Clock clock, Policy policy, String scope) {
        this.db = db;
        this.clock = clock;
        this.policy = policy;
        this.scope = scope;
    }

    // ---- API ---------------------------------------------------------------------------------------------------------------------

    @Override
    public synchronized Optional<Duration> blockedFor(String key) {
        long now = clock.millis();
        String subject = subject(key);
        Row r = load(subject, now);
        if (r == null) {
            return Optional.empty();
        }
        long remaining = r.nextAllowedAt() - now;
        return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
    }

    @Override
    public synchronized void recordFailure(String key) {
        long now = clock.millis();
        String subject = subject(key);
        Row r = load(subject, now);
        int n = Math.min(MAX_FAILURES, (r == null ? 0 : r.failures()) + 1);
        long next = n > policy.freeFailures() ? now + delayMs(n) : 0;
        save(subject, new Row(n, now, next), now);
    }

    @Override
    public synchronized void recordSuccess(String key) {
        String subject = subject(key);
        shadow.remove(subject);
        try {
            db.with(c -> {
                try (var ps = c.prepareStatement("DELETE FROM rate_limits WHERE subject=?")) {
                    ps.setString(1, subject);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            // o espelho já foi limpo; o disco será corrigido na próxima escrita
        }
    }

    // ---- política ----------------------------------------------------------------------------------------------------------------

    long delayMs(int failures) {
        int step = Math.max(0, failures - policy.freeFailures() - 1);
        long d = policy.firstDelay().toMillis() << Math.min(20, step);
        return Math.min(policy.cap().toMillis(), Math.max(0, d));
    }

    /** Linha vigente, já saneada; null se não há, se expirou (e então é apagada) ou se está vazia. */
    private Row load(String subject, long now) {
        Row stored = null;
        try {
            stored = db.with(c -> {
                try (var ps = c.prepareStatement("SELECT failures, last_at, next_allowed_at FROM rate_limits WHERE subject=?")) {
                    ps.setString(1, subject);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? new Row(rs.getInt(1), rs.getLong(2), rs.getLong(3)) : null;
                    }
                }
            });
        } catch (RuntimeException e) {
            // banco indisponível: segue só com o espelho (continua impondo)
        }
        Row mem = shadow.get(subject);
        Row r = stored == null ? mem : mem == null ? stored : mem.nextAllowedAt() >= stored.nextAllowedAt() ? mem : stored;
        if (r == null) {
            return null;
        }
        Row clean = sanitize(r, now);
        if (!clean.equals(r)) {
            save(subject, clean, now); // ancora o valor saneado: sem isso o teto "deslizaria" junto com um relógio que voltou
        }
        r = clean;
        if (now - r.lastAt() > policy.decay().toMillis()) {
            recordSuccess0(subject);
            return null;
        }
        return r;
    }

    /** Valores impossíveis (corrupção, relógio que voltou) são trazidos para dentro dos limites; nada vira bloqueio permanente. */
    private Row sanitize(Row r, long now) {
        int failures = Math.max(0, Math.min(MAX_FAILURES, r.failures()));
        long last = r.lastAt() < 0 || r.lastAt() > now ? now : r.lastAt();
        long ceiling = now + policy.cap().toMillis();
        long next = r.nextAllowedAt() < 0 ? 0 : Math.min(r.nextAllowedAt(), ceiling);
        return new Row(failures, last, next);
    }

    private void recordSuccess0(String subject) {
        shadow.remove(subject);
        try {
            db.with(c -> {
                try (var ps = c.prepareStatement("DELETE FROM rate_limits WHERE subject=?")) {
                    ps.setString(1, subject);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            // nada
        }
    }

    private void save(String subject, Row row, long now) {
        if (shadow.size() >= policy.maxRows() && !shadow.containsKey(subject)) {
            shadow.values().removeIf(x -> now - x.lastAt() > policy.decay().toMillis());
            if (shadow.size() >= policy.maxRows()) {
                shadow.entrySet().removeIf(e -> e.getValue().nextAllowedAt() <= now && shadow.size() >= policy.maxRows());
            }
        }
        if (shadow.size() < policy.maxRows() || shadow.containsKey(subject)) {
            shadow.put(subject, row);
        }
        try {
            db.with(c -> {
                try (var count = c.prepareStatement("SELECT COUNT(*) FROM rate_limits")) {
                    try (ResultSet rs = count.executeQuery()) {
                        if (rs.next() && rs.getLong(1) >= policy.maxRows()) {
                            try (var del = c.prepareStatement("DELETE FROM rate_limits WHERE last_at < ?")) {
                                del.setLong(1, now - policy.decay().toMillis());
                                del.executeUpdate();
                            }
                            try (var del = c.prepareStatement("DELETE FROM rate_limits WHERE subject IN (SELECT subject FROM rate_limits WHERE next_allowed_at <= ? ORDER BY last_at ASC LIMIT 100) AND subject<>?")) {
                                del.setLong(1, now);
                                del.setString(2, subject);
                                del.executeUpdate();
                            }
                        }
                    }
                }
                try (var ps = c.prepareStatement("INSERT INTO rate_limits(subject, failures, last_at, next_allowed_at) SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM rate_limits) < ? OR EXISTS (SELECT 1 FROM rate_limits WHERE subject=?) "
                        + "ON CONFLICT(subject) DO UPDATE SET failures=excluded.failures, last_at=excluded.last_at, next_allowed_at=excluded.next_allowed_at")) {
                    ps.setString(1, subject);
                    ps.setInt(2, row.failures());
                    ps.setLong(3, row.lastAt());
                    ps.setLong(4, row.nextAllowedAt());
                    ps.setInt(5, policy.maxRows());
                    ps.setString(6, subject);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            // o espelho em memória já impõe o limite
        }
    }

    // ---- impressão do assunto ------------------------------------------------------------------------------------------------------

    private String subject(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.length() > MAX_KEY) {
            k = k.substring(0, MAX_KEY);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((scope + "\u0000" + k).getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sal local, criado uma vez e guardado no banco (sobrevive a reinício). Sem banco, vale só para esta execução. */
    private byte[] pepper() {
        if (pepper != null) {
            return pepper;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        try {
            String stored = db.with(c -> {
                try (var ins = c.prepareStatement("INSERT OR IGNORE INTO meta(name, value) VALUES (?, ?)")) {
                    ins.setString(1, "rl_pepper");
                    ins.setString(2, Base64.getEncoder().encodeToString(fresh));
                    ins.executeUpdate();
                }
                try (var sel = c.prepareStatement("SELECT value FROM meta WHERE name=?")) {
                    sel.setString(1, "rl_pepper");
                    try (ResultSet rs = sel.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            byte[] decoded = stored == null ? null : Base64.getDecoder().decode(stored);
            pepper = decoded != null && decoded.length == 32 ? decoded : fresh;
        } catch (RuntimeException e) {
            pepper = fresh;
        }
        return pepper;
    }
}
