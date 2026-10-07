package byx.service.tx;

import byx.service.tx.TxValues.GasLimit;
import byx.service.tx.TxValues.GasPrice;
import byx.service.tx.TxValues.MaximumFee;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Política de transação, DONA do serviço. A implementação de produção é {@link #DISABLED} ("TX_DISABLED"): nenhuma variável de ambiente, propriedade,
 * arquivo ou comando a muda. Políticas habilitadas existem só no código de teste (rascunho de rede de teste) até uma revisão explícita.
 */
public interface TxPolicy {
    /** Nome e versão ficam na cotação: política diferente na confirmação invalida a cotação. */
    String name();

    String version();

    boolean enabled();

    /** Preço do gas por modo (ubyx/gas). */
    GasPrice price(FeeMode mode);

    /** Fator aplicado ao gas simulado (ex.: 1.10). */
    BigDecimal gasAdjustment();

    /** Teto global do gas limit. */
    GasLimit globalGasLimit();

    /** Orçamento máximo de taxa (ubyx). Domina o teto de gas em modos caros. */
    MaximumFee maximumFeeBudget();

    /** Validade de uma cotação. */
    Duration quoteTtl();

    TxPolicy DISABLED = new TxPolicy() {
        @Override public String name() { return "TX_DISABLED"; }
        @Override public String version() { return "disabled"; }
        @Override public boolean enabled() { return false; }
        @Override public GasPrice price(FeeMode mode) { throw new TxException(TxError.TX_DISABLED); }
        @Override public BigDecimal gasAdjustment() { throw new TxException(TxError.TX_DISABLED); }
        @Override public GasLimit globalGasLimit() { throw new TxException(TxError.TX_DISABLED); }
        @Override public MaximumFee maximumFeeBudget() { throw new TxException(TxError.TX_DISABLED); }
        @Override public Duration quoteTtl() { throw new TxException(TxError.TX_DISABLED); }
    };
}
