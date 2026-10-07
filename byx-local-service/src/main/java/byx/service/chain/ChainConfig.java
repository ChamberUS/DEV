package byx.service.chain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Configuração TIPADA do nó local público: RPC e REST (gRPC e WebSocket ficam para quando o contrato da chain fechar), chain id esperado e modelo de denom. Produção: NÃO CONFIGURADO
 * ({@link #production()} vazio) até o contrato da chain estar pronto e uma configuração empacotada ser revisada; nenhum host vem do painel, de ambiente, de propriedade nem de arquivo do usuário.
 */
public record ChainConfig(ChainEndpoint rpc, ChainEndpoint rest, String expectedChainId, DenomModel denom) {
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public ChainConfig {
        if (rpc == null || rest == null || denom == null || expectedChainId == null || !CHAIN_ID.matcher(expectedChainId).matches()) {
            throw new IllegalArgumentException("invalid chain configuration");
        }
    }

    /** Produção: sem configuração (NOT_CONFIGURED/DISABLED). */
    public static Optional<ChainConfig> production() {
        return Optional.empty();
    }

    /** Validação completa de origens e chain id (usada por testes com servidor falso em 127.0.0.1; produção não a chama). */
    public static ChainConfig of(String rpcOrigin, String restOrigin, String expectedChainId, DenomModel denom) {
        return new ChainConfig(ChainEndpoint.parse(rpcOrigin), ChainEndpoint.parse(restOrigin), expectedChainId, denom);
    }
}
