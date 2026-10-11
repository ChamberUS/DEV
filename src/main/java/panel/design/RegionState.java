package panel.design;

/** Contrato padrão de estados de região (P3.10, BYX_DESIGN_TOKENS.json#regionStates). */
public enum RegionState {
    READY, LOADING, EMPTY, PARTIAL, STALE, DEGRADED, ERROR, UNAVAILABLE, LOCKED, NOT_CONFIGURED;

    /** Estados que mantêm o conteúdo visível (com banner ou esmaecido). */
    public boolean showsContent() {
        return this == READY || this == PARTIAL || this == STALE || this == DEGRADED;
    }
}
