package panel.adapter;

import java.time.Clock;
import java.time.Instant;
import panel.localservice.ChainStatusClient;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;

/**
 * Leitura PÚBLICA e somente leitura da chain PELO SERVIÇO local (IPC verificado → byx-local-service → nó). O painel não conhece nem envia endpoint, host, porta ou denom: a configuração é do serviço
 * (hoje NOT_CONFIGURED). Só mapeia o status mínimo para o {@link ByxSnapshot} da tela; nada de saldo, carteira ou transação.
 */
public final class ServiceChainGateway implements ByxChainGateway {
    private final ChainStatusClient client;
    private final Clock clock;

    public ServiceChainGateway(ChainStatusClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String source() {
        return "SERVICE_NODE";
    }

    @Override
    public boolean ownsEndpoint() {
        return true;
    }

    /** O argumento é ignorado (o serviço é dono do endpoint); null é o caso normal. */
    @Override
    public ByxSnapshot read(ByxConfig ignored) {
        ChainStatusClient.View view = client.read();
        boolean carriesData = view.state().equals("LIVE") || view.state().equals("SYNCING") || view.state().equals("STALE");
        return toSnapshot(view, carriesData ? client.facts().orElse(null) : null, clock.instant());
    }

    public static ByxSnapshot toSnapshot(ChainStatusClient.View v, Instant now) {
        return toSnapshot(v, null, now);
    }

    public static ByxSnapshot toSnapshot(ChainStatusClient.View v, panel.model.ChainFacts facts, Instant now) {
        String state = v.state();
        Instant blockTime = v.blockTimeMs() == null ? null : Instant.ofEpochMilli(v.blockTimeMs());
        String height = v.latestHeight() == null ? null : Long.toString(v.latestHeight());
        return switch (state) {
            case "LIVE" -> snap(state, "ONLINE", "VERIFIED", "FRESH", false, v, height, blockTime, now, "Public read-only node", facts);
            case "SYNCING" -> snap(state, "ONLINE", "VERIFIED", "FRESH", true, v, height, blockTime, now, "Node is catching up", facts);
            case "STALE" -> snap(state, "ONLINE", "VERIFIED", "STALE", v.catchingUp(), v, height, blockTime, now, "Latest block is not recent", facts);
            case "NETWORK_MISMATCH" -> snap(state, "ONLINE", "UNVERIFIED", "UNKNOWN", null, v, null, null, null, "Node reports a different network", null);
            case "OFFLINE" -> snap(state, "OFFLINE", "UNVERIFIED", "UNKNOWN", null, v, null, null, null, "Node unavailable", null);
            case "CONNECTING" -> snap(state, "UNKNOWN", "UNVERIFIED", "UNKNOWN", null, v, null, null, null, "Connecting to the node", null);
            case "NOT_CONFIGURED" -> snap(state, "UNKNOWN", "UNVERIFIED", "UNKNOWN", null, v, null, null, null, "No chain node is configured in the service package", null);
            default -> snap("ERROR", "UNKNOWN", "UNVERIFIED", "UNKNOWN", null, v, null, null, null, "Unexpected node or service response", null);
        };
    }

    private static ByxSnapshot snap(String state, String connection, String identity, String freshness, Boolean syncing, ChainStatusClient.View v, String height, Instant blockTime,
            Instant now, String message, panel.model.ChainFacts facts) {
        // updatedAt só existe quando há bloco (o serviço de rede do painel compara os dois): sem bloco não há "atualizado em"
        return new ByxSnapshot("SERVICE_NODE", "LOCALNET", connection, identity, freshness, syncing, "NETWORK_MISMATCH".equals(state) || blockTime != null ? v.chainId() : null, height, blockTime,
                null, null, null, 0, blockTime == null ? null : now, message, state, facts);
    }
}
