package byx.service.chain;

/** Validação LOCAL de endereço bech32 (checksum BIP-173, como o Cosmos SDK) com prefixo fixo; nenhuma correção silenciosa. Falha local = nenhuma requisição de rede. */
public final class Bech32 {
    static final String PREFIX = "byx";
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private Bech32() { }

    /** Endereço BYX válido: minúsculo, prefixo "byx", separador "1", checksum correto e payload de 20 ou 32 bytes. */
    public static boolean isValidAddress(String s) {
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
        if (polymod(expandHrp(PREFIX), data) != 1) {
            return false;
        }
        int payload5 = data.length - 6;
        int bytes = payload5 * 5 / 8;
        int padBits = payload5 * 5 % 8;
        return (bytes == 20 || bytes == 32) && padBits < 5 && lastPaddingZero(data, payload5, padBits);
    }

    private static boolean lastPaddingZero(int[] data, int payload5, int padBits) {
        return padBits == 0 || (data[payload5 - 1] & ((1 << padBits) - 1)) == 0;
    }

    private static int[] expandHrp(String hrp) {
        int[] r = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            r[i] = hrp.charAt(i) >> 5;
            r[i + hrp.length() + 1] = hrp.charAt(i) & 31;
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
