package panel.design;

/** statusStates.states. Um componente que não pode ser lido é UNKNOWN, nunca OPERATIONAL. */
public enum StatusState {
    OPERATIONAL, CONNECTING, RECONNECTING, DEGRADED, UNAVAILABLE, UNKNOWN;

    /** Só estes estados pulsam (statePulse). */
    public boolean pulses() {
        return this == CONNECTING || this == RECONNECTING;
    }

    /** Classe CSS; UNAVAILABLE esperado (nada configurado) usa o chip neutro e o ponto cinza. */
    public String styleClass(boolean expected) {
        if (this == UNAVAILABLE && expected) {
            return "state-unavailable-expected";
        }
        return "state-" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
