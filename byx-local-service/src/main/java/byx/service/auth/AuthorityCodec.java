package byx.service.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codificação CANÔNICA do estado de autoridade e seu MAC (HMAC-SHA256, primitiva padrão; sem cifra nem protocolo próprio). O MAC cobre os
 * bytes canônicos reconstruídos a partir dos VALORES decodificados (campos desconhecidos, duplicados ou fora do domínio são recusados),
 * com rótulo de domínio e versão de formato. Formato do arquivo: {"format":1,"state":{...},"mac":"<base64url>"}.
 */
final class AuthorityCodec {
    static final int FORMAT = 1;
    static final int MAX_ACCOUNTS = 10_000;
    private static final byte[] LABEL = "byx-authority-v1\u0000".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(512).maxNumberLength(20).build()).build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AuthorityCodec() {
    }

    static final class FormatException extends Exception {
        FormatException() {
            super("format_invalid", null, false, false);
        }
    }

    static byte[] canonical(AuthorityState s) {
        ObjectNode o = JSON.createObjectNode();
        o.put("version", s.version());
        ArrayNode arr = o.putArray("accounts");
        List<Account> sorted = new ArrayList<>(s.accounts());
        sorted.sort(Comparator.comparing(Account::id));
        for (Account a : sorted) {
            ObjectNode n = arr.addObject();
            n.put("id", a.id());
            n.put("username", a.username());
            n.put("role", a.role().name());
            n.put("enabled", a.enabled());
            n.put("credentialVersion", a.credentialVersion());
            n.put("passwordHash", a.passwordHash());
            n.put("createdAtMs", a.createdAtMs());
        }
        try {
            return JSON.writeValueAsBytes(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("encode");
        }
    }

    static byte[] mac(byte[] key, byte[] canonical) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update(LABEL);
            return m.doFinal(canonical);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }

    static byte[] encodeFile(AuthorityState s, byte[] key) {
        byte[] canon = canonical(s);
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("format", FORMAT);
            root.set("state", JSON.readTree(canon));
            root.put("mac", Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, canon)));
            return JSON.writeValueAsBytes(root);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("encode");
        }
    }

    /** Resultado do parse: estado + o MAC declarado no arquivo. */
    record Parsed(AuthorityState state, byte[] declaredMac) {
    }

    static Parsed parse(byte[] file) throws FormatException {
        try {
            JsonNode root = JSON.readTree(file);
            if (root == null || !root.isObject() || root.size() != 3 || !root.path("format").isInt() || root.path("format").asInt() != FORMAT || !root.path("mac").isTextual()) {
                throw new FormatException();
            }
            JsonNode st = root.get("state");
            if (st == null || !st.isObject() || st.size() != 2 || !st.path("version").isIntegralNumber() || !st.path("accounts").isArray()) {
                throw new FormatException();
            }
            long version = st.get("version").asLong();
            if (version < 1 || st.get("accounts").size() > MAX_ACCOUNTS) {
                throw new FormatException();
            }
            List<Account> accounts = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (JsonNode n : st.get("accounts")) {
                if (!n.isObject() || n.size() != 7) {
                    throw new FormatException();
                }
                String id = text(n, "id");
                String username = text(n, "username");
                String roleText = text(n, "role");
                String hash = text(n, "passwordHash");
                if (!id.matches("[0-9a-f]{32}") || !username.matches("[a-z0-9._-]{1,64}") || !hash.startsWith("$argon2id$") || hash.length() > 256
                        || !n.path("enabled").isBoolean() || !n.path("credentialVersion").isIntegralNumber() || !n.path("createdAtMs").isIntegralNumber()) {
                    throw new FormatException();
                }
                long cv = n.get("credentialVersion").asLong();
                long created = n.get("createdAtMs").asLong();
                if (cv < 1 || created < 0 || !ids.add(id) || !names.add(username)) {
                    throw new FormatException();
                }
                Role role;
                try {
                    role = Role.valueOf(roleText);
                } catch (IllegalArgumentException e) {
                    throw new FormatException();
                }
                accounts.add(new Account(id, username, role, n.get("enabled").asBoolean(), cv, hash, created));
            }
            return new Parsed(new AuthorityState(version, accounts), Base64.getUrlDecoder().decode(root.get("mac").asText()));
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new FormatException();
        }
    }

    private static String text(JsonNode n, String f) throws FormatException {
        JsonNode v = n.get(f);
        if (v == null || !v.isTextual()) {
            throw new FormatException();
        }
        return v.asText();
    }
}
