package byx.service.auth;

import byx.service.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Limitador de tentativas do serviço (mesma política do L4 do painel: 4 falhas livres, depois 30 s, 60 s, 2 min… teto de 5 min, decaimento de
 * 30 min, no máximo 5000 linhas, linha bloqueada nunca é descartada). Persistente em arquivo com MAC (chave derivada da autoridade); assunto =
 * HMAC do identificador digitado (nunca o texto); mesmo caminho para conta existente ou não; tentativa durante o bloqueio não o prolonga;
 * relógio que volta é limitado ao teto. Arquivo adulterado (MAC/formato) ⇒ FALHA FECHADA: tudo fica no teto até o decaimento.
 */
public final class AuthRateLimiter {
    private record Row(int failures, long lastAt, long nextAllowedAt) {
    }

    private static final JsonMapper JSON = new JsonMapper();
    private final Path file;
    private final byte[] macKey;
    private final byte[] subjectKey;
    private final Clock clock;
    private final Map<String, Row> rows = new HashMap<>();
    private boolean tampered;
    private long tamperedAt;

    /** file == null: só memória (testes). */
    public AuthRateLimiter(Path file, byte[] macKey, byte[] subjectKey, Clock clock) {
        this.file = file;
        this.macKey = macKey.clone();
        this.subjectKey = subjectKey.clone();
        this.clock = clock;
        load();
    }

    public synchronized Optional<Duration> blockedFor(String scope, String key) {
        long now = clock.millis();
        if (tampered) {
            if (now - tamperedAt <= AuthLimits.RATE_DECAY.toMillis()) {
                return Optional.of(AuthLimits.RATE_CAP);
            }
            tampered = false;
            rows.clear();
        }
        Row r = live(subject(scope, key), now);
        if (r == null) {
            return Optional.empty();
        }
        long remaining = r.nextAllowedAt() - now;
        return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
    }

    public synchronized void recordFailure(String scope, String key) {
        long now = clock.millis();
        String s = subject(scope, key);
        Row r = live(s, now);
        int n = Math.min(1_000, (r == null ? 0 : r.failures()) + 1);
        long next = n > AuthLimits.RATE_FREE_FAILURES ? now + delayMs(n) : 0;
        if (rows.size() >= AuthLimits.RATE_MAX_ROWS && !rows.containsKey(s)) {
            rows.values().removeIf(x -> now - x.lastAt() > AuthLimits.RATE_DECAY.toMillis());
            if (rows.size() >= AuthLimits.RATE_MAX_ROWS) {
                rows.entrySet().removeIf(e -> e.getValue().nextAllowedAt() <= now && rows.size() >= AuthLimits.RATE_MAX_ROWS);
            }
        }
        if (rows.size() < AuthLimits.RATE_MAX_ROWS || rows.containsKey(s)) {
            rows.put(s, new Row(n, now, next));
        }
        save();
    }

    public synchronized void recordSuccess(String scope, String key) {
        if (rows.remove(subject(scope, key)) != null) {
            save();
        }
    }

    static long delayMs(int failures) {
        int step = Math.max(0, failures - AuthLimits.RATE_FREE_FAILURES - 1);
        long d = AuthLimits.RATE_FIRST_DELAY.toMillis() << Math.min(20, step);
        return Math.min(AuthLimits.RATE_CAP.toMillis(), Math.max(0, d));
    }

    private Row live(String subject, long now) {
        Row r = rows.get(subject);
        if (r == null) {
            return null;
        }
        long last = r.lastAt() < 0 || r.lastAt() > now ? now : r.lastAt();
        long next = r.nextAllowedAt() < 0 ? 0 : Math.min(r.nextAllowedAt(), now + AuthLimits.RATE_CAP.toMillis());
        Row clean = new Row(Math.max(0, Math.min(1_000, r.failures())), last, next);
        if (now - clean.lastAt() > AuthLimits.RATE_DECAY.toMillis()) {
            rows.remove(subject);
            return null;
        }
        if (!clean.equals(r)) {
            rows.put(subject, clean); // ancora o valor saneado (relógio que voltou)
            save();
        }
        return clean;
    }

    private String subject(String scope, String key) {
        String k = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.length() > 256) {
            k = k.substring(0, 256);
        }
        return HexFormat.of().formatHex(hmac(subjectKey, (scope + "\u0000" + k).getBytes(StandardCharsets.UTF_8)), 0, 16);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }

    private byte[] canonical() {
        ObjectNode o = JSON.createObjectNode();
        ArrayNode a = o.putArray("rows");
        List<Map.Entry<String, Row>> sorted = new ArrayList<>(rows.entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (var e : sorted) {
            ObjectNode n = a.addObject();
            n.put("s", e.getKey());
            n.put("f", e.getValue().failures());
            n.put("l", e.getValue().lastAt());
            n.put("n", e.getValue().nextAllowedAt());
        }
        try {
            return JSON.writeValueAsBytes(o);
        } catch (IOException e) {
            throw new IllegalStateException("encode");
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            byte[] canon = canonical();
            ObjectNode root = JSON.createObjectNode();
            root.put("format", 1);
            root.set("state", JSON.readTree(canon));
            root.put("mac", Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(macKey, canon)));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.toAbsolutePath().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(tmp, JSON.writeValueAsBytes(root));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Log.event("ratelimit_save_failed", "io_error"); // o espelho em memória continua impondo
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            JsonNode state = root.get("state");
            if (root.size() != 3 || root.path("format").asInt() != 1 || state == null || !state.path("rows").isArray() || state.size() != 1) {
                throw new IOException("format");
            }
            byte[] canon = JSON.writeValueAsBytes(state);
            byte[] declared = Base64.getUrlDecoder().decode(root.path("mac").asText());
            if (!MessageDigest.isEqual(declared, hmac(macKey, canon))) {
                throw new IOException("mac");
            }
            for (JsonNode n : state.get("rows")) {
                rows.put(n.path("s").asText(), new Row(n.path("f").asInt(), n.path("l").asLong(), n.path("n").asLong()));
            }
            if (rows.size() > AuthLimits.RATE_MAX_ROWS) {
                throw new IOException("size");
            }
        } catch (IOException | RuntimeException e) {
            rows.clear();
            tampered = true;
            tamperedAt = clock.millis();
            Log.event("ratelimit_tampered", "fail_closed");
        }
    }
}
