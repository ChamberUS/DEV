package byx.service.chain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Configuração TIPADA do nó local público: RPC e REST (gRPC e WebSocket ficam para quando for necessário), chain id esperado e modelo de denom. O artefato usa só as configurações de
 * {@link ChainProfile} (constantes de compilação); nenhum host vem do painel, de ambiente, de propriedade nem de arquivo do usuário.
 */
public record ChainConfig(ChainEndpoint rpc, ChainEndpoint rest, String expectedChainId, DenomModel denom) {
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public ChainConfig {
        if (rpc == null || rest == null || denom == null || expectedChainId == null || !CHAIN_ID.matcher(expectedChainId).matches()) {
            throw new IllegalArgumentException("invalid chain configuration");
        }
    }

    /** Configuração do perfil ativo do artefato ({@link ChainProfile#ACTIVE}); vazia se o perfil for PRODUCTION_DISABLED. */
    public static Optional<ChainConfig> production() {
        return ChainProfile.ACTIVE.config();
    }

    /** Validação completa de origens e chain id (usada por testes com servidor falso em 127.0.0.1; produção não a chama). */
    public static ChainConfig of(String rpcOrigin, String restOrigin, String expectedChainId, DenomModel denom) {
        return new ChainConfig(ChainEndpoint.parse(rpcOrigin), ChainEndpoint.parse(restOrigin), expectedChainId, denom);
    }
}
