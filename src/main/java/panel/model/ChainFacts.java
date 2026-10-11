package panel.model;

import java.math.BigInteger;

/** Fatos PÚBLICOS da chain vindos do serviço (modelo de denom e suprimento em unidades-base). Sem saldo de conta, sem carteira. */
public record ChainFacts(String baseDenom, String displayDenom, int exponent, BigInteger supplyBaseUnits) {
    /** Suprimento em texto decimal EXATO (inteiros; nunca ponto flutuante), ex.: 1500000 com expoente 6 → "1.500000". */
    public String supplyDisplay() {
        return panel.util.DenomFormat.format(supplyBaseUnits, exponent);
    }
}
