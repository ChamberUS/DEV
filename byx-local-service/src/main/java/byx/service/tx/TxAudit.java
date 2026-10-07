package byx.service.tx;

import byx.service.Log;

/**
 * Auditoria de transação. Política explícita: o log do serviço recebe SÓ o tipo do evento, o código fechado e o prefixo (8 hex) do id da operação e
 * da cotação. NUNCA: quantia, destinatário, remetente, memo, hash da transação, bytes assinados, chave, semente, token. Os valores financeiros ficam
 * apenas no diário da transação (privado, 0600, dono: serviço) quando ele existir.
 */
public interface TxAudit {
    enum Type { TX_PREPARE, TX_QUOTED, TX_CONFIRM, TX_SIGN_REQUEST, TX_BROADCAST_REQUEST, TX_SUBMITTED, TX_FAILED, TX_UNKNOWN_OUTCOME, TX_CANCEL, TX_DENIED }

    void event(Type type, String operationPrefix, String quotePrefix, String code);

    /** Produção: eventos fixos no log do serviço (sem segredo e sem dado financeiro). */
    TxAudit LOG = (type, op, quote, code) -> Log.event(type.name(), "op=" + op + " quote=" + quote + (code == null ? "" : " code=" + code));
}
