package byx.service.chain;

/**
 * Lista FECHADA das operações PÚBLICAS e SOMENTE LEITURA da chain. Cada uma tem rota conhecida, método GET e DTO conhecido; nenhuma recebe URL, host, caminho ou consulta do painel. Não existe
 * operação genérica (nada de http.request, rpc.call, grpc.call, chain.queryRaw, fetchUrl, proxy, execute) nem de escrita (enviar, transmitir, assinar, carteira, execução de grant de gás,
 * mutação de pagamento/loja/governança). {@code ipc}: a operação existe no protocolo local; as demais são servidas dentro do status.
 */
public enum ChainOperation {
    CHAIN_STATUS("byx.status", ChainRoute.RPC_STATUS, true),
    CHAIN_LATEST_HEIGHT("byx.latestHeight", ChainRoute.RPC_STATUS, false),
    CHAIN_NETWORK_INFO("byx.networkInfo", ChainRoute.RPC_STATUS, false),
    CHAIN_DENOM_METADATA("byx.denomMetadata", ChainRoute.REST_DENOM_METADATA, true),
    CHAIN_SUPPLY("byx.supply", ChainRoute.REST_SUPPLY, true);

    private final String wire;
    private final ChainRoute route;
    private final boolean ipc;

    ChainOperation(String wire, ChainRoute route, boolean ipc) {
        this.wire = wire;
        this.route = route;
        this.ipc = ipc;
    }

    public String wire() { return wire; }

    public ChainRoute route() { return route; }

    public boolean ipc() { return ipc; }

    /** Método HTTP: sempre GET. */
    public String method() { return "GET"; }
}
