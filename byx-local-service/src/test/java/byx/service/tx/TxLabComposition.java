package byx.service.tx;

import byx.service.tx.TxPorts.TxSessions;
import java.time.Clock;

/**
 * Composição SINTÉTICA para QA manual do Transaction Lab (só src/test): assinante efêmero, transporte e chain falsos, política de rascunho de testnet, contas
 * fictícias. Usada por {@code byx.service.auth.TxLabHost}; nunca faz parte do artefato.
 */
public final class TxLabComposition {
    private TxLabComposition() { }

    public static TxIpc ipc(TxSessions sessions) {
        TxFx fx = new TxFx();
        fx.transport.spendable = "5000000000";
        // todas as contas do host sintético usam a chave fictícia do remetente
        TxPorts.TxKeys keys = (account, ref) -> java.util.Optional.of(new TxPorts.TxKey("lab-key", new TxValues.BankAddress(TxFx.SENDER)));
        TxService svc = new TxService(() -> true, fx.policy, sessions, fx.chain, keys, fx.transport, fx.signer, (s, i, st) -> TxPorts.Decision.ALLOW, TxAudit.LOG, TxJournal.memory(),
                Clock.systemUTC());
        return new TxIpc(svc);
    }
}
