package byx.service.chain;

import java.util.Optional;

/**
 * Perfil TIPADO do conector da chain pública. A escolha é uma CONSTANTE de compilação ({@link #ACTIVE}); nenhuma variável de ambiente, propriedade, arquivo ou UI a lê ou a muda: trocar de perfil
 * é uma mudança de código revisada. {@code PRODUCTION_DISABLED}: sem nó (NOT_CONFIGURED). {@code LOCAL_QA}: o nó descartável de QA em loopback, com endpoints FIXOS (RPC 127.0.0.1:28657,
 * REST 127.0.0.1:28317), chain id {@code byx} e denom {@code ubyx}/{@code BYX}/6 (contrato da chain: READY_FOR_APP_INTEGRATION, somente leitura). O nó só é aceito se o chain id e a metadata de
 * denom batem; qualquer divergência é NETWORK_MISMATCH. Sem nó rodando, o estado é OFFLINE (fechado), nunca saudável.
 */
public enum ChainProfile {
    PRODUCTION_DISABLED(null),
    LOCAL_QA(new ChainConfig(new ChainEndpoint("127.0.0.1", 28657), new ChainEndpoint("127.0.0.1", 28317), "byx", new DenomModel("ubyx", "BYX", 6)));

    /** Perfil do artefato. Esta fase (V2.1M, integração local somente leitura): LOCAL_QA. Reverter = trocar esta constante. */
    public static final ChainProfile ACTIVE = LOCAL_QA;

    private final ChainConfig config;

    ChainProfile(ChainConfig config) {
        this.config = config;
    }

    public Optional<ChainConfig> config() {
        return Optional.ofNullable(config);
    }
}
