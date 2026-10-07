package byx.service.chain;

/** Acesso de teste ao estado de produção do conector (fora do pacote). */
public final class ChainConnectorTestAccess {
    private ChainConnectorTestAccess() { }

    public static boolean productionIsNotConfigured() {
        try (ChainConnector c = ChainConnector.notConfigured()) {
            return !c.configured() && c.status().state() == ChainState.NOT_CONFIGURED && ChainConfig.production().isEmpty();
        }
    }
}
