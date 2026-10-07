package byx.service.tx;

import byx.service.tx.TxValues.EstimatedFee;
import byx.service.tx.TxValues.GasAmount;
import byx.service.tx.TxValues.GasLimit;
import byx.service.tx.TxValues.GasPrice;
import byx.service.tx.TxValues.MaximumFee;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Aritmética exata de gas e taxa. adjustedGas = ceil(simulatedGas * ajuste); fee = ceil(adjustedGas * gasPrice). Os valores ficam SEPARADOS
 * (simulado, ajustado, limite, preço, taxa estimada, orçamento máximo) e NUNCA se reduz o gas silenciosamente para caber na taxa:
 * se não cabe, falha (MAX_GAS_EXCEEDED / MAX_FEE_EXCEEDED) antes de qualquer assinatura.
 */
public final class FeeEngine {
    private FeeEngine() { }

    public record Fee(GasAmount simulatedGas, GasAmount adjustedGas, GasLimit gasLimit, GasPrice gasPrice, EstimatedFee estimatedFee, MaximumFee maximumFee) {
        /** Gas máximo permitido pelo orçamento neste preço (floor(orçamento / preço)); informativo. */
        public BigInteger gasAllowedByBudget() {
            if (gasPrice.value().signum() == 0) {
                return null;
            }
            return new BigDecimal(maximumFee.value()).divide(gasPrice.value(), 0, RoundingMode.FLOOR).toBigIntegerExact();
        }
    }

    public static Fee compute(TxPolicy policy, FeeMode mode, GasAmount simulated) {
        BigDecimal adjustment = policy.gasAdjustment();
        if (adjustment == null || adjustment.compareTo(BigDecimal.ONE) < 0 || adjustment.compareTo(BigDecimal.TEN) > 0) {
            throw new TxException(TxError.BAD_REQUEST);
        }
        BigInteger adjusted = new BigDecimal(simulated.value()).multiply(adjustment).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        long ceiling = policy.globalGasLimit().value();
        if (adjusted.compareTo(BigInteger.valueOf(ceiling)) > 0) {
            throw new TxException(TxError.MAX_GAS_EXCEEDED);
        }
        GasAmount adjustedGas = new GasAmount(adjusted);
        GasLimit limit = new GasLimit(adjusted.longValueExact());
        GasPrice price = policy.price(mode);
        BigInteger fee = new BigDecimal(adjusted).multiply(price.value()).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        MaximumFee budget = policy.maximumFeeBudget();
        if (fee.compareTo(budget.value()) > 0) {
            throw new TxException(TxError.MAX_FEE_EXCEEDED);
        }
        return new Fee(simulated, adjustedGas, limit, price, new EstimatedFee(fee), budget);
    }
}
