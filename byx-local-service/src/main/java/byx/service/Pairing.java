package byx.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Prova mútua de posse do segredo de pareamento (HMAC-SHA256 padrão, sem primitiva própria). O segredo nunca trafega: cada lado
 * prova que o possui sobre nonces novos escolhidos pelos dois, com rótulos diferentes por direção (um lado não serve de oráculo
 * para forjar a prova do outro). Isto autentica quem lê o arquivo de pareamento, NÃO um aplicativo específico (ver SECURITY_FOUNDATION.md).
 */
public final class Pairing {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private Pairing() {
    }

    public static String encode(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    public static byte[] decode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static String serverProof(byte[] secret, String clientNonce, String serverNonce) {
        return proof(secret, "server", clientNonce, serverNonce);
    }

    public static String clientProof(byte[] secret, String clientNonce, String serverNonce) {
        return proof(secret, "client", clientNonce, serverNonce);
    }

    private static String proof(byte[] secret, String label, String cn, String sn) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(("byx-ipc-v1|" + label + "|" + cn + "|" + sn).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Comparação em tempo constante. */
    public static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
