package byx.service.chain;

import java.util.Optional;

/**
 * Perfil TIPADO do conector da chain pública. A escolha é feita no BUILD ({@link #ACTIVE}, recurso dentro do JAR assinado); nenhuma variável de ambiente, propriedade, arquivo de usuário ou UI a lê ou a muda em runtime. {@code PRODUCTION_DISABLED}: sem nó (NOT_CONFIGURED). {@code LOCAL_QA}: o nó descartável de QA em loopback, com endpoints FIXOS (RPC 127.0.0.1:28657,
 * REST 127.0.0.1:28317), chain id {@code byx} e denom {@code ubyx}/{@code BYX}/6 (contrato da chain: READY_FOR_APP_INTEGRATION, somente leitura). O nó só é aceito se o chain id e a metadata de
 * denom batem; qualquer divergência é NETWORK_MISMATCH. Sem nó rodando, o estado é OFFLINE (fechado), nunca saudável.
 */
public enum ChainProfile {
    PRODUCTION_DISABLED(null),
    LOCAL_QA(new ChainConfig(new ChainEndpoint("127.0.0.1", 28657), new ChainEndpoint("127.0.0.1", 28317), "byx", new DenomModel("ubyx", "BYX", 6)));

    /**
     * Perfil do artefato, escolhido no BUILD/PACKAGE (build-app.sh --chain-profile, que passa -Dbyx.chain.profile ao Maven) e gravado dentro do JAR assinado como recurso filtrado. Padrão:
     * PRODUCTION_DISABLED. Vazio, ausente ou desconhecido (inclusive o placeholder não filtrado) resolve para PRODUCTION_DISABLED (fechado); nunca para um host arbitrário.
     */
    public static final ChainProfile ACTIVE = resolve(embedded());

    private final ChainConfig config;

    ChainProfile(ChainConfig config) {
        this.config = config;
    }

    /** Resolve o nome do perfil de build; só os nomes exatos dos perfis tipados valem, o resto cai no padrão seguro. */
    static ChainProfile resolve(String name) {
        if (name != null) {
            for (ChainProfile p : values()) {
                if (p.name().equals(name.strip())) {
                    return p;
                }
            }
        }
        return PRODUCTION_DISABLED;
    }

    private static String embedded() {
        try (java.io.InputStream in = ChainProfile.class.getResourceAsStream("/byx/chain-profile.txt")) {
            return in == null ? null : new String(in.readNBytes(64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    public Optional<ChainConfig> config() {
        return Optional.ofNullable(config);
    }
}
