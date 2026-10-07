package panel.txview;

import com.fasterxml.jackson.databind.JsonNode;
import panel.localservice.AuthorityGateway;

/** Porta do Transaction Lab para o serviço. Produção do Lab: {@link #over(AuthorityGateway)}; testes injetam um dublê. Nunca é chamada na thread FX. */
public interface TxLabService {
    record Reply(boolean ok, String code, JsonNode result) { }

    Reply prepare(String operation, String sender, String recipient, String amountUbyx, String memo, String feeMode);

    Reply confirm(String operation, String quote);

    Reply status(String operation);

    Reply cancel(String operation);

    static TxLabService over(AuthorityGateway g) {
        return new TxLabService() {
            private Reply of(AuthorityGateway.Reply r) {
                return new Reply(r.ok(), r.code(), r.result());
            }

            @Override public Reply prepare(String op, String sender, String recipient, String amount, String memo, String mode) { return of(g.txPrepareBankSend(op, sender, recipient, amount, memo, mode)); }
            @Override public Reply confirm(String op, String quote) { return of(g.txConfirm(op, quote)); }
            @Override public Reply status(String op) { return of(g.txGetStatus(op)); }
            @Override public Reply cancel(String op) { return of(g.txCancel(op)); }
        };
    }
}
