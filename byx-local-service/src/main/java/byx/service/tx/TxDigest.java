package byx.service.tx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Codificação canônica explícita (campo a campo, prefixo de tamanho de 4 bytes big-endian) + SHA-256. Nunca serialização Java. */
final class TxDigest {
    private TxDigest() { }

    static final class Canonical {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Canonical text(String s) {
            return bytes(s.getBytes(StandardCharsets.UTF_8));
        }

        Canonical bytes(byte[] b) {
            out.write(b.length >>> 24);
            out.write(b.length >>> 16);
            out.write(b.length >>> 8);
            out.write(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        byte[] done() {
            return out.toByteArray();
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
