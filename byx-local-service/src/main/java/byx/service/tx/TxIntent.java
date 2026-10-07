package byx.service.tx;

import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.KeyRef;
import byx.service.tx.TxValues.Memo;
import byx.service.tx.TxValues.TxIntentDigest;
import byx.service.tx.TxValues.UbyxAmount;

/**
 * Intenção de transação: modelo FECHADO (sealed). Não existe "tipo em texto + payload arbitrário": cada tipo é uma classe com campos tipados e
 * o conjunto só cresce explicitamente aqui. Um tipo desconhecido não tem como ser representado, logo é negado antes de chegar ao serviço.
 */
public sealed interface TxIntent permits TxIntent.BankSendIntent {
    /** Nome estável do tipo (entra no resumo canônico). */
    String type();

    /** Codificação canônica determinística (domínio + tipo + campos com prefixo de tamanho). */
    byte[] canonical();

    default TxIntentDigest digest() {
        return new TxIntentDigest(TxDigest.sha256Hex(canonical()));
    }

    /** Transferência bank/MsgSend: remetente (referência de chave do serviço), destinatário, quantia em ubyx inteiros e memo opcional. */
    record BankSendIntent(KeyRef sender, BankAddress recipient, UbyxAmount amount, Memo memo) implements TxIntent {
        public static final String TYPE = "bank_send";

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public byte[] canonical() {
            return new TxDigest.Canonical().text("BYX-TX-INTENT-V1").text(TYPE).text(sender.label()).text(recipient.value())
                    .text(amount.value().toString()).text(memo.text()).done();
        }
    }
}
