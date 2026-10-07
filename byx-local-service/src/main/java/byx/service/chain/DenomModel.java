package byx.service.chain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Modelo de denom tipado: denom base, denom de exibição e expoente. Conversão monetária SOMENTE com inteiros/BigDecimal (nunca double/float): unidades-base ↔ texto decimal exato.
 */
public record DenomModel(String base, String display, int exponent) {
    private static final Pattern BASE = Pattern.compile("[a-z][a-z0-9]{2,31}");
    private static final Pattern DISPLAY = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,31}");

    public DenomModel {
        if (base == null || !BASE.matcher(base).matches() || display == null || !DISPLAY.matcher(display).matches() || exponent < 0 || exponent > 18) {
            throw new IllegalArgumentException("invalid denom model");
        }
    }

    /** Unidades-base (inteiro não negativo) → texto decimal exato com o expoente (ex.: 1500000 → "1.500000"). */
    public String format(BigInteger baseUnits) {
        if (baseUnits == null || baseUnits.signum() < 0) {
            throw new IllegalArgumentException("non-negative base units required");
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

    /** Texto decimal → unidades-base; recusa casas além do expoente, sinal, notação científica e lixo (nada é arredondado). */
    public BigInteger parse(String decimal) {
        if (decimal == null || !decimal.matches("[0-9]{1,40}(\\.[0-9]{1,18})?")) {
            throw new IllegalArgumentException("invalid decimal amount");
        }
        BigDecimal v = new BigDecimal(decimal);
        if (v.scale() > exponent) {
            throw new IllegalArgumentException("too many decimal places");
        }
        return v.movePointRight(exponent).toBigIntegerExact();
    }
}
