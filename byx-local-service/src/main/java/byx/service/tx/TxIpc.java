package byx.service.tx;

import byx.service.tx.TxIntent.BankSendIntent;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.ClientOperationId;
import byx.service.tx.TxValues.KeyRef;
import byx.service.tx.TxValues.Memo;
import byx.service.tx.TxValues.TxQuoteId;
import byx.service.tx.TxValues.UbyxAmount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Superfície IPC FECHADA e tipada de transação: tx.prepareBankSend, tx.getQuote, tx.confirm, tx.getStatus, tx.cancel. NÃO existe (e a lista é testada)
 * sign, broadcast, signBytes, sendAnyMessage, protobufAny, rawTx, rawHttp, rawGrpc. Cada operação aceita um conjunto fechado de campos de TEXTO; qualquer
 * campo a mais, tipo errado ou aninhamento é recusado. Quantia chega como inteiro decimal em ubyx (nunca BYX decimal). Gate mestre/política desligados
 * respondem TX_DISABLED ANTES de qualquer validação: chamar o IPC sem UI não executa nada.
 */
public final class TxIpc {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "tx.prepareBankSend", Set.of("session", "operation", "sender", "recipient", "amountUbyx", "memo", "feeMode"),
            "tx.getQuote", Set.of("session", "operation", "quote"),
            "tx.confirm", Set.of("session", "operation", "quote"),
            "tx.getStatus", Set.of("session", "operation"),
            "tx.cancel", Set.of("session", "operation"));
    public static final Set<String> OPERATIONS = FIELDS.keySet();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final TxService service;

    public TxIpc(TxService service) {
        this.service = service;
    }

    /** Composição de produção desta fase: serviço desligado (tudo responde TX_DISABLED). */
    public static TxIpc disabled(TxService.Disabled none) {
        return new TxIpc(none.service());
    }

    public static boolean handles(String op) {
        return op != null && op.startsWith("tx.");
    }

    public TxService service() {
        return service;
    }

    /** Escreve ok/result ou ok=false/error{code} no objeto de resposta. */
    public void handle(long peerKey, String op, JsonNode req, ObjectNode resp) {
        try {
            if (!service.enabled()) {
                deny(resp, TxError.TX_DISABLED); // antes de qualquer validação: nenhuma informação sobre campos, sessão ou política
                return;
            }
            if (peerKey == 0L) {
                deny(resp, TxError.UNAUTHORIZED); // sem peer verificado pelo kernel nenhuma operação de transação roda (mesma regra do auth.*)
                return;
            }
            Set<String> allowed = FIELDS.get(op);
            if (allowed == null) {
                deny(resp, TxError.BAD_REQUEST); // inclui tx.sign, tx.broadcast etc.: não existem
                return;
            }
            Iterator<String> names = req.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                if (!n.equals("v") && !n.equals("id") && !n.equals("op") && !allowed.contains(n)) {
                    deny(resp, TxError.BAD_REQUEST);
                    return;
                }
            }
            for (String f : allowed) {
                JsonNode v = req.get(f);
                if (v == null || !v.isTextual()) {
                    deny(resp, TxError.BAD_REQUEST);
                    return;
                }
            }
            String token = req.get("session").asText();
            if (!TOKEN.matcher(token).matches()) {
                deny(resp, TxError.BAD_REQUEST);
                return;
            }
            ClientOperationId opId = new ClientOperationId(req.get("operation").asText());
            switch (op) {
                case "tx.prepareBankSend" -> {
                    BankSendIntent intent = new BankSendIntent(new KeyRef(req.get("sender").asText()), new BankAddress(req.get("recipient").asText()),
                            UbyxAmount.parse(req.get("amountUbyx").asText()), new Memo(req.get("memo").asText()));
                    FeeMode mode = FeeMode.parse(req.get("feeMode").asText());
                    TxQuote q = service.prepareBankSend(peerKey, token, opId, intent, mode);
                    ok(resp, new TxService.Status(TxState.AWAITING_CONFIRMATION, q, null, null));
                }
                case "tx.getQuote" -> ok(resp, service.quote(peerKey, token, opId, new TxQuoteId(req.get("quote").asText())));
                case "tx.confirm" -> ok(resp, service.confirm(peerKey, token, opId, new TxQuoteId(req.get("quote").asText())));
                case "tx.getStatus" -> ok(resp, service.status(peerKey, token, opId));
                case "tx.cancel" -> ok(resp, service.cancel(peerKey, token, opId));
                default -> deny(resp, TxError.BAD_REQUEST);
            }
        } catch (TxException e) {
            resp.removeAll();
            deny(resp, e.error());
        } catch (RuntimeException e) {
            resp.removeAll();
            deny(resp, TxError.INTERNAL); // nunca a mensagem da exceção
        }
    }

    private static void deny(ObjectNode resp, TxError error) {
        resp.put("ok", false);
        resp.putObject("error").put("code", error.name());
    }

    private static void ok(ObjectNode resp, TxService.Status st) {
        resp.put("ok", true);
        ObjectNode r = resp.putObject("result");
        r.put("state", st.state().name());
        if (st.error() != null) {
            r.put("error", st.error().name());
        }
        if (st.txHash() != null) {
            r.put("txHash", st.txHash());
        }
        TxQuote q = st.quote();
        if (q != null) {
            ObjectNode o = r.putObject("quote");
            o.put("quoteId", q.id().value());
            o.put("intentDigest", q.intentDigest().value());
            o.put("feeMode", q.feeMode().name());
            o.put("chainId", q.chainId());
            o.put("chainGeneration", q.chainGeneration().value());
            o.put("sender", q.sender().value());
            o.put("recipient", q.recipient().value());
            o.put("amountUbyx", q.amount().value().toString());
            o.put("memoDigest", q.memoDigest());
            o.put("accountNumber", q.accountNumber().value());
            o.put("sequence", q.sequence().value());
            o.put("simulatedGas", q.simulatedGas().value().toString());
            o.put("adjustedGas", q.adjustedGas().value().toString());
            o.put("gasLimit", q.gasLimit().value());
            o.put("gasPrice", q.gasPrice().canonical());
            o.put("feeUbyx", q.fee().value().toString());
            o.put("maximumFeeUbyx", q.maximumFee().value().toString());
            o.put("totalDebitUbyx", q.totalDebit().toString());
            o.put("policy", q.policyName());
            o.put("policyVersion", q.policyVersion());
            o.put("createdAtMs", q.createdAtMs());
            o.put("expiresAtMs", q.expiresAtMs());
        }
    }
}
