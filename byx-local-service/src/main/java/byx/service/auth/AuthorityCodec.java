package byx.service.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codificação CANÔNICA do estado de autoridade e seu MAC (HMAC-SHA256, primitiva padrão; sem cifra nem protocolo próprio). O MAC cobre os
 * bytes canônicos reconstruídos a partir dos VALORES decodificados (campos desconhecidos, duplicados ou fora do domínio são recusados),
 * com rótulo de domínio e versão de formato. O ARQUIVO é um snapshot CIFRADO (AES-256-GCM, JCA): ver {@link #seal}/{@link #open}.
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
        final String code;

        FormatException() {
            this("format_invalid");
        }

        FormatException(String code) {
            super(code, null, false, false); // sem causa nem pilha: nada do conteúdo vaza por exceção
            this.code = code;
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

    // ---- snapshot cifrado: magic(4) | formatVersion(2) | authorityVersion(8) | nonce(12) | ciphertext+tag(GCM, 128 bits) ----------------------
    static final byte[] MAGIC = {'B', 'Y', 'X', 'A'};
    static final int FORMAT_VERSION = 2;
    static final int HEADER = 4 + 2 + 8;
    static final int NONCE = 12;
    static final int TAG_BITS = 128;
    static final int MIN_FILE = HEADER + NONCE + TAG_BITS / 8 + 2;
    static final int MAX_FILE = 8 * 1024 * 1024;
    private static final byte[] AAD_LABEL = "byx-authority-aead-v1\u0000".getBytes(StandardCharsets.UTF_8);

    /** O cabeçalho (magic, versão de formato, versão da autoridade) participa do AAD: trocá-lo invalida a etiqueta. */
    private static byte[] aad(byte[] header) {
        byte[] out = new byte[AAD_LABEL.length + header.length];
        System.arraycopy(AAD_LABEL, 0, out, 0, AAD_LABEL.length);
        System.arraycopy(header, 0, out, AAD_LABEL.length, header.length);
        return out;
    }

    /** Cifra o estado canônico (AES-256-GCM, nonce NOVO de 96 bits por gravação, SecureRandom). Zera o texto claro depois. */
    static byte[] seal(AuthorityState s, byte[] encKey, SecureRandom random) {
        byte[] plain = canonical(s);
        byte[] header = new byte[HEADER];
        System.arraycopy(MAGIC, 0, header, 0, 4);
        header[4] = (byte) (FORMAT_VERSION >>> 8);
        header[5] = (byte) FORMAT_VERSION;
        for (int i = 0; i < 8; i++) {
            header[6 + i] = (byte) (s.version() >>> (56 - 8 * i));
        }
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(header));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[HEADER + NONCE + ct.length];
            System.arraycopy(header, 0, out, 0, HEADER);
            System.arraycopy(nonce, 0, out, HEADER, NONCE);
            System.arraycopy(ct, 0, out, HEADER + NONCE, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("seal");
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    /** Resultado de abrir: o estado e o MAC (HMAC com a chave de MAC) do estado canônico, para a comparação com a âncora. */
    record Opened(AuthorityState state, byte[] headMac) {
    }

    /** Valida limites ANTES de decifrar; qualquer falha de autenticação é "decrypt_failed" (sem detalhe). */
    static Opened open(byte[] file, byte[] encKey, byte[] macKey) throws FormatException {
        if (file.length < MIN_FILE || file.length > MAX_FILE) {
            throw new FormatException("format_invalid");
        }
        for (int i = 0; i < 4; i++) {
            if (file[i] != MAGIC[i]) {
                throw new FormatException("format_invalid");
            }
        }
        if ((((file[4] & 0xFF) << 8) | (file[5] & 0xFF)) != FORMAT_VERSION) {
            throw new FormatException("format_invalid");
        }
        long headerVersion = 0;
        for (int i = 0; i < 8; i++) {
            headerVersion = (headerVersion << 8) | (file[6 + i] & 0xFFL);
        }
        byte[] header = java.util.Arrays.copyOfRange(file, 0, HEADER);
        byte[] plain = null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new GCMParameterSpec(TAG_BITS, java.util.Arrays.copyOfRange(file, HEADER, HEADER + NONCE)));
            c.updateAAD(aad(header));
            plain = c.doFinal(file, HEADER + NONCE, file.length - HEADER - NONCE);
            AuthorityState st = parseState(plain);
            if (st.version() != headerVersion) {
                throw new FormatException("format_invalid");
            }
            return new Opened(st, mac(macKey, canonical(st)));
        } catch (GeneralSecurityException e) {
            throw new FormatException("decrypt_failed");
        } finally {
            if (plain != null) {
                java.util.Arrays.fill(plain, (byte) 0);
            }
        }
    }

    /** Parse estrito do estado canônico (texto claro já autenticado). */
    static AuthorityState parseState(byte[] plain) throws FormatException {
        try {
            JsonNode st = JSON.readTree(plain);
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
            return new AuthorityState(version, accounts);
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
