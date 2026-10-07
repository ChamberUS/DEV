package byx.service.tx;

import byx.service.auth.AuthService;
import byx.service.tx.TxPorts.TxChain;
import byx.service.tx.TxPorts.TxSession;
import byx.service.tx.TxPorts.TxSessions;
import java.time.Clock;
import java.util.Optional;

/** Adaptadores REAIS e somente-leitura usados pela composição de produção (sessão = autoridade de autenticação; chain = conector público). */
public final class TxProduction {
    private TxProduction() { }

    public static TxSessions sessions(AuthService auth) {
        return (peerKey, token) -> auth.resolveSession(peerKey, token)
                .map(r -> new TxSession(r.sessionId(), r.peerKey(), r.accountId(), r.admin(), r.recentMfa(), r.elevated()));
    }

    /** Produção nesta fase: serviço de transação DESLIGADO (gate falso, política TX_DISABLED, sem assinante, sem transporte). */
    public static TxIpc disabled(AuthService authOrNull, TxChain chain) {
        TxSessions sessions = authOrNull == null ? (peer, token) -> Optional.empty() : sessions(authOrNull);
        return TxIpc.disabled(TxService.Disabled.of(sessions, chain, Clock.systemUTC()));
    }
}
