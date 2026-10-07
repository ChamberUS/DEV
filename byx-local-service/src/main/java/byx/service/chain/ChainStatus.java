package byx.service.chain;

/**
 * Status mínimo apresentável. Sem JSON cru, sem cabeçalhos, sem detalhes internos do nó. {@code chainId} é o observado (validado por padrão restrito) para mostrar divergência;
 * em divergência {@code networkMatch=false} e a altura NÃO é exposta como saudável.
 */
public record ChainStatus(ChainState state, boolean configured, boolean reachable, String chainId, Long latestHeight, Boolean catchingUp, Long blockTimeMs, boolean networkMatch,
        ChainReason reason, int generation, long updatedAtMs) {
    static ChainStatus notConfigured(long now) {
        return new ChainStatus(ChainState.NOT_CONFIGURED, false, false, null, null, null, null, false, ChainReason.NOT_CONFIGURED, 0, now);
    }
}
