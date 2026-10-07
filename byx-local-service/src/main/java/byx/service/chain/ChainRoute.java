package byx.service.chain;

/**
 * Rotas FIXAS do nó local (RPC e REST). O caminho é um modelo literal; o único parâmetro é o denom base, validado por padrão restrito e vindo da configuração tipada (nunca do painel).
 * Limite de resposta por rota. Sem prefixo, sem curinga, sem redirecionamento.
 */
public enum ChainRoute {
    RPC_STATUS(Kind.RPC, "/status", 16 * 1024),
    REST_DENOM_METADATA(Kind.REST, "/cosmos/bank/v1beta1/denoms_metadata/%s", 16 * 1024),
    REST_SUPPLY(Kind.REST, "/cosmos/bank/v1beta1/supply/by_denom?denom=%s", 8 * 1024);

    public enum Kind { RPC, REST }

    private final Kind kind;
    private final String template;
    private final int maxBytes;

    ChainRoute(Kind kind, String template, int maxBytes) {
        this.kind = kind;
        this.template = template;
        this.maxBytes = maxBytes;
    }

    public Kind kind() { return kind; }

    public int maxBytes() { return maxBytes; }

    /** Caminho (e consulta) final; o denom já foi validado pela configuração. */
    String path(String denom) {
        return template.contains("%s") ? String.format(template, denom) : template;
    }
}
