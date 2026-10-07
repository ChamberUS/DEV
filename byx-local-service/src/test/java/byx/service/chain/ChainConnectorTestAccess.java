package byx.service.chain;

/** Acesso de teste ao estado de produção do conector (fora do pacote). */
public final class ChainConnectorTestAccess {
    private ChainConnectorTestAccess() { }

    /** O artefato usa o perfil tipado LOCAL_QA (endpoints fixos em loopback, chain id byx, ubyx/BYX/6); não contata a rede até alguém pedir o status. */
    public static boolean productionProfileIsTheTypedLocalQa() {
        try (ChainConnector c = ChainConnector.production()) {
            ChainConfig cfg = ChainConfig.production().orElseThrow();
            return c.configured() && ChainProfile.ACTIVE == ChainProfile.LOCAL_QA && cfg.rpc().equals(new ChainEndpoint("127.0.0.1", 28657)) && cfg.rest().equals(new ChainEndpoint("127.0.0.1", 28317))
                    && cfg.expectedChainId().equals("byx") && cfg.denom().equals(new DenomModel("ubyx", "BYX", 6));
        }
    }
}
