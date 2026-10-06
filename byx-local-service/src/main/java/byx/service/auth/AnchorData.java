package byx.service.auth;

import java.util.Arrays;

/**
 * Âncora de integridade: chave MAC (32 B), versão monotônica do estado e MAC do estado vigente (32 B). Vive SOMENTE onde o serviço a
 * guarda sozinho (keychain de proteção de dados). Codificação fixa de 72 bytes: key | version (8 B big-endian) | headMac.
 */
public record AnchorData(byte[] key, long version, byte[] headMac) {
    public static final int SIZE = 72;

    public AnchorData {
        if (key.length != 32 || headMac.length != 32 || version < 0) {
            throw new IllegalArgumentException("anchor");
        }
        key = key.clone();
        headMac = headMac.clone();
    }

    public byte[] encode() {
        byte[] out = new byte[SIZE];
        System.arraycopy(key, 0, out, 0, 32);
        for (int i = 0; i < 8; i++) {
            out[32 + i] = (byte) (version >>> (56 - 8 * i));
        }
        System.arraycopy(headMac, 0, out, 40, 32);
        return out;
    }

    public static AnchorData decode(byte[] b) {
        if (b.length != SIZE) {
            throw new IllegalArgumentException("anchor");
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[32 + i] & 0xFFL);
        }
        return new AnchorData(Arrays.copyOfRange(b, 0, 32), v, Arrays.copyOfRange(b, 40, 72));
    }

    @Override
    public String toString() {
        return "AnchorData[redacted]";
    }
}
