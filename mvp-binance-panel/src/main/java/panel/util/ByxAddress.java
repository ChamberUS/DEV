package panel.util;

/** Validação LOCAL de endereço BYX (bech32, prefixo "byx", checksum, 20 ou 32 bytes): inválido falha aqui, sem nenhuma consulta e sem "correção" silenciosa. Não guarda nem assina nada. */
public final class ByxAddress {
    private static final String PREFIX = "byx";
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private ByxAddress() { }

    public static boolean isValid(String s) {
        if (s == null || s.length() < 14 || s.length() > 90 || !s.equals(s.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        int sep = s.lastIndexOf('1');
        if (sep != PREFIX.length() || !s.startsWith(PREFIX) || s.length() - sep - 1 < 7) {
            return false;
        }
        int[] data = new int[s.length() - sep - 1];
        for (int i = 0; i < data.length; i++) {
            int v = CHARSET.indexOf(s.charAt(sep + 1 + i));
            if (v < 0) {
                return false;
            }
            data[i] = v;
        }
        if (polymod(expand(), data) != 1) {
            return false;
        }
        int payload5 = data.length - 6;
        int bytes = payload5 * 5 / 8;
        int pad = payload5 * 5 % 8;
        return (bytes == 20 || bytes == 32) && pad < 5 && (pad == 0 || (data[payload5 - 1] & ((1 << pad) - 1)) == 0);
    }

    /** Só para exibição: abrevia no meio ("byx14uzu3j…qev7z"). O modelo guarda o valor COMPLETO; copiar deve copiar o completo. */
    public static String abbreviate(String full) {
        return full == null || full.length() <= 20 ? full : full.substring(0, 10) + "…" + full.substring(full.length() - 6);
    }

    private static int[] expand() {
        int[] r = new int[PREFIX.length() * 2 + 1];
        for (int i = 0; i < PREFIX.length(); i++) {
            r[i] = PREFIX.charAt(i) >> 5;
            r[i + PREFIX.length() + 1] = PREFIX.charAt(i) & 31;
        }
        return r;
    }

    private static int polymod(int[] hrp, int[] data) {
        int chk = 1;
        for (int[] part : new int[][] {hrp, data}) {
            for (int v : part) {
                int top = chk >>> 25;
                chk = (chk & 0x1ffffff) << 5 ^ v;
                for (int i = 0; i < 5; i++) {
                    if ((top >>> i & 1) == 1) {
                        chk ^= GEN[i];
                    }
                }
            }
        }
        return chk;
    }
}
