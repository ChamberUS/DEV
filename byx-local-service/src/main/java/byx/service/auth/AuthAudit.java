package byx.service.auth;

import byx.service.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Auditoria de segurança GERADA PELO SERVIÇO (estado SEPARADO de credenciais, autorização, sessão e limitador): só códigos fixos e o id opaco da conta.
 * Nunca nome digitado, senha, OTP, token, segredo ou verificador. O painel não consegue fabricar um evento de segurança confiável: não existe operação
 * de IPC para escrever auditoria.
 * <p>
 * Persistente (opcional): arquivo 0600 só de acréscimo, uma linha por evento {@code seq|atMs|EVENTO|ator|macAnterior|mac}, com cadeia HMAC-SHA256 (chave
 * derivada da autoridade): editar, apagar ou reordenar linhas quebra a cadeia, o que é detectado ao abrir (evento AUDIT_CHAIN_BROKEN e nova cadeia; o
 * arquivo quebrado é preservado). Falha ao gravar NÃO derruba a autenticação (código fixo no log), mas não é silenciosa.
 */
public final class AuthAudit {
    public record Entry(long atMs, String event, String actor) {
    }

    private static final int MAX = 1_000;
    private static final long ROTATE_BYTES = 8L * 1024 * 1024;
    private static final String ZERO = "0".repeat(64);
    private final Deque<Entry> ring = new ArrayDeque<>();
    private final Clock clock;
    private final Path file;
    private final byte[] key;
    private long seq;
    private String prev = ZERO;
    private boolean chainBroken;

    /** Só memória (testes). */
    public AuthAudit(Clock clock) {
        this(clock, null, null);
    }

    public AuthAudit(Clock clock, Path file, byte[] macKey) {
        this.clock = clock;
        this.file = file;
        this.key = macKey == null ? null : macKey.clone();
        if (file != null) {
            open();
        }
    }

    private static String mac(byte[] key, String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update("byx-audit-v1\u0000".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(m.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }

    /** Resultado da verificação da cadeia: linhas válidas, ok e último mac. */
    public record Verification(boolean ok, long lines, String lastMac) {
    }

    public static Verification verify(Path file, byte[] macKey) {
        long n = 0;
        String prev = ZERO;
        try {
            if (!Files.exists(file)) {
                return new Verification(true, 0, ZERO);
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\\|", -1);
                if (f.length != 6 || !f[4].equals(prev) || !f[0].equals(Long.toString(n + 1)) || !mac(macKey, f[0] + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|" + f[4]).equals(f[5])) {
                    return new Verification(false, n, prev);
                }
                prev = f[5];
                n++;
            }
            return new Verification(true, n, prev);
        } catch (IOException | RuntimeException e) {
            return new Verification(false, n, prev);
        }
    }

    private void open() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Verification v = verify(file, key);
            if (!v.ok()) {
                chainBroken = true;
                Path broken = file.resolveSibling(file.getFileName() + ".broken-" + clock.millis());
                Files.move(file, broken);
                Log.event("auth_audit_chain", "broken");
                seq = 0;
                prev = ZERO;
            } else {
                seq = v.lines();
                prev = v.lastMac();
            }
        } catch (IOException | RuntimeException e) {
            Log.event("auth_audit_chain", "unreadable");
        }
        if (chainBroken) {
            record("AUDIT_CHAIN_BROKEN", "-");
        }
    }

    synchronized void record(String event, String actor) {
        long now = clock.millis();
        String a = actor == null || !actor.matches("[0-9a-f]{32}") ? "-" : actor; // só o id opaco da conta; nada digitado entra
        String e = event.matches("[A-Z_]{1,40}") ? event : "EVENT_INVALID";
        if (ring.size() >= MAX) {
            ring.pollFirst();
        }
        ring.addLast(new Entry(now, e, a));
        Log.event("auth_audit", e);
        if (file != null) {
            try {
                long n = seq + 1;
                String body = n + "|" + now + "|" + e + "|" + a + "|" + prev;
                String m = mac(key, body);
                if (!Files.exists(file)) {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else if (Files.size(file) > ROTATE_BYTES) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".rotated-" + now));
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    n = 1; // nova cadeia: o arquivo anterior fica íntegro e preservado
                    prev = ZERO;
                    body = n + "|" + now + "|" + e + "|" + a + "|" + prev;
                    m = mac(key, body);
                }
                Files.writeString(file, body + "|" + m + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                seq = n;
                prev = m;
            } catch (IOException | RuntimeException ex) {
                Log.event("auth_audit_write", "failed");
            }
        }
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(ring);
    }
}
