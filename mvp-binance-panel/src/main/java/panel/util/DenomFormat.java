package panel.util;

import java.math.BigInteger;

/** Formatação monetária EXATA: unidades-base inteiras → texto decimal com o expoente (sem double/float). */
public final class DenomFormat {
    private DenomFormat() { }

    public static String format(BigInteger baseUnits, int exponent) {
        if (baseUnits == null || baseUnits.signum() < 0 || exponent < 0 || exponent > 18) {
            throw new IllegalArgumentException("invalid amount");
        }
        String digits = baseUnits.toString();
        if (exponent == 0) {
            return digits;
        }
        if (digits.length() <= exponent) {
            digits = "0".repeat(exponent - digits.length() + 1) + digits;
        }
        return digits.substring(0, digits.length() - exponent) + "." + digits.substring(digits.length() - exponent);
    }
}
