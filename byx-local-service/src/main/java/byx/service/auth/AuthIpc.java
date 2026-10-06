package byx.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contrato IPC TIPADO de autenticação. Cada operação tem um conjunto FECHADO de campos (qualquer campo a mais é recusado) e valida o
 * formato antes de chegar à autoridade. A UI nunca envia papel, userId, admin, mfa nem estado de sessão: só credenciais e o token opaco.
 * NÃO existem (e a lista é testada): auth.execute, auth.querySql, auth.setRole, auth.setMfa, auth.impersonate, auth.override, auth.debugLogin.
 * A chave do peer vem do kernel (pid+pidversion), nunca do quadro.
 */
public final class AuthIpc {
    /** Operações e seus campos (além de v, id, op). */
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "auth.password", Set.of("username", "password"),
            "auth.beginSecondFactor", Set.of("session"),
            "auth.verifySecondFactor", Set.of("session", "challenge", "code"),
            "auth.sessionStatus", Set.of("session"),
            "auth.logout", Set.of("session"),
            "auth.adminElevation", Set.of("session"),
            "auth.changePassword", Set.of("session", "current", "next"));
    public static final Set<String> OPERATIONS = FIELDS.keySet();

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int PASSWORD_MAX = 256;

    /** Resposta de aplicação: ok + (result | code). */
    public record Reply(boolean ok, String code, Map<String, Object> data) {
    }

    private final AuthService auth;

    public AuthIpc(AuthService auth) {
        this.auth = auth;
    }

    public static boolean handles(String op) {
        return op != null && op.startsWith("auth.");
    }

    public Reply handle(long peerKey, String op, JsonNode req) {
        Set<String> allowed = FIELDS.get(op);
        if (allowed == null) {
            return new Reply(false, "unsupported_operation", Map.of()); // inclui auth.execute, auth.setRole etc.: não existem
        }
        if (peerKey == 0L) {
            return new Reply(false, "peer_unavailable", Map.of());
        }
        Iterator<String> names = req.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!n.equals("v") && !n.equals("id") && !n.equals("op") && !allowed.contains(n)) {
                return bad();
            }
        }
        for (String f : allowed) {
            JsonNode v = req.get(f);
            if (v == null || !v.isTextual()) {
                return bad();
            }
        }
        try {
            return switch (op) {
                case "auth.password" -> {
                    String u = text(req, "username");
                    String p = text(req, "password");
                    if (!USERNAME.matcher(u).matches() || p.isEmpty() || p.length() > PASSWORD_MAX) {
                        yield bad();
                    }
                    char[] pw = p.toCharArray();
                    try {
                        yield from(auth.login(peerKey, u, pw));
                    } finally {
                        java.util.Arrays.fill(pw, '\0');
                    }
                }
                case "auth.beginSecondFactor" -> token(req) == null ? bad() : from(auth.beginSecondFactor(peerKey, token(req)));
                case "auth.verifySecondFactor" -> {
                    String t = token(req);
                    String ch = text(req, "challenge");
                    String code = text(req, "code");
                    yield t == null || !CHALLENGE.matcher(ch).matches() || !CODE.matcher(code).matches() ? bad() : from(auth.verifySecondFactor(peerKey, t, ch, code));
                }
                case "auth.sessionStatus" -> token(req) == null ? bad() : from(auth.sessionStatus(peerKey, token(req)));
                case "auth.logout" -> token(req) == null ? bad() : from(auth.logout(peerKey, token(req)));
                case "auth.adminElevation" -> token(req) == null ? bad() : from(auth.adminElevation(peerKey, token(req)));
                case "auth.changePassword" -> {
                    String t = token(req);
                    String cur = text(req, "current");
                    String next = text(req, "next");
                    if (t == null || cur.isEmpty() || cur.length() > PASSWORD_MAX || next.length() > PASSWORD_MAX) {
                        yield bad();
                    }
                    char[] c = cur.toCharArray();
                    char[] n = next.toCharArray();
                    try {
                        yield from(auth.changePassword(peerKey, t, c, n));
                    } finally {
                        java.util.Arrays.fill(c, '\0');
                        java.util.Arrays.fill(n, '\0');
                    }
                }
                default -> new Reply(false, "unsupported_operation", Map.of());
            };
        } catch (RuntimeException e) {
            return new Reply(false, "internal_error", Map.of()); // nunca a mensagem da exceção
        }
    }

    private static String text(JsonNode req, String f) {
        return req.get(f).asText();
    }

    private static String token(JsonNode req) {
        String t = text(req, "session");
        return TOKEN.matcher(t).matches() ? t : null;
    }

    private static Reply bad() {
        return new Reply(false, "bad_request", Map.of());
    }

    private static Reply from(AuthService.Result r) {
        return r.ok() ? new Reply(true, "OK", r.data()) : new Reply(false, r.code().name(), r.data());
    }

    /** Escreve a resposta no objeto de resposta do protocolo (ok/result ou ok=false/error). */
    public static void write(Reply r, ObjectNode resp) {
        resp.put("ok", r.ok());
        ObjectNode target = r.ok() ? resp.putObject("result") : resp.putObject("error");
        if (!r.ok()) {
            target.put("code", r.code());
        }
        for (Map.Entry<String, Object> e : r.data().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                target.put(e.getKey(), b);
            } else if (v instanceof Number n) {
                target.put(e.getKey(), n.longValue());
            } else {
                target.put(e.getKey(), String.valueOf(v));
            }
        }
    }
}
