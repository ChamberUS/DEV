package panel.mascot;

/**
 * Prioridade de PRESENÇA (maior vence): erro/segurança &gt; PROCESSING/SYNCING/THINKING (funcional) &gt; ATTENTION/NOTIFICATION &gt; guia contextual &gt; interação do ponteiro &gt; IDLE. O ponteiro nunca
 * sobrescreve um estado funcional; um hint automático nunca interrompe um one-shot nem uma tarefa. ERROR_SECURITY existe para fechar a ordem: o mascote nunca é usado nesses contextos, a UI normal manda.
 */
public enum MascotPriority {
    IDLE, POINTER, CONTEXT_GUIDE, ATTENTION_NOTIFICATION, FUNCTIONAL, ERROR_SECURITY;

    /** Um pedido de {@code requested} pode tomar a presença quando algo de {@code current} está ativo? (empate cede ao que já está: sem briga) */
    public static boolean mayShow(MascotPriority requested, MascotPriority current) {
        return requested.ordinal() > current.ordinal();
    }

    public static MascotPriority of(MascotState s) {
        return switch (s) {
            case PROCESSING, SYNCING, THINKING -> FUNCTIONAL;
            case ATTENTION, NOTIFICATION -> ATTENTION_NOTIFICATION;
            case TRANSITION -> ATTENTION_NOTIFICATION;
            case IDLE -> IDLE;
        };
    }
}
