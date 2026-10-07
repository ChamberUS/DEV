package byx.service.tx;

import byx.service.tx.TxValues.AccountNumber;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.ChainGeneration;
import byx.service.tx.TxValues.EstimatedFee;
import byx.service.tx.TxValues.GasAmount;
import byx.service.tx.TxValues.GasLimit;
import byx.service.tx.TxValues.GasPrice;
import byx.service.tx.TxValues.MaximumFee;
import byx.service.tx.TxValues.Sequence;
import byx.service.tx.TxValues.TxIntentDigest;
import byx.service.tx.TxValues.TxQuoteId;
import byx.service.tx.TxValues.UbyxAmount;
import java.math.BigInteger;

/**
 * Cotação IMUTÁVEL criada pelo serviço. Liga o resumo da intenção, a chain (id + geração), remetente/destinatário/quantia/resumo do memo, conta e
 * sequence, os cinco números de gas/taxa, a versão da política e o prazo. O painel só recebe uma cópia para exibir; nada do que ele devolve altera
 * campo algum (a confirmação referencia a cotação por id e o serviço usa os valores guardados aqui).
 */
public record TxQuote(TxQuoteId id, TxIntentDigest intentDigest, FeeMode feeMode, String chainId, ChainGeneration chainGeneration, BankAddress sender, BankAddress recipient,
        UbyxAmount amount, String memoDigest, AccountNumber accountNumber, Sequence sequence, GasAmount simulatedGas, GasAmount adjustedGas, GasLimit gasLimit, GasPrice gasPrice,
        EstimatedFee fee, MaximumFee maximumFee, String policyName, String policyVersion, long createdAtMs, long expiresAtMs) {

    /** Total debitado da conta: quantia + taxa. */
    public BigInteger totalDebit() {
        return amount.value().add(fee.value());
    }

    public boolean expiredAt(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
