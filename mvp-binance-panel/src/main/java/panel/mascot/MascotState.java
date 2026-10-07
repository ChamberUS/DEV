package panel.mascot;

/**
 * Estados FECHADOS do mascote. A UI pede um estado tipado; nenhum código da aplicação escolhe arquivo, caminho ou URL (os assets vêm do manifesto empacotado).
 * Loops: IDLE, THINKING, PROCESSING, SYNCING. One-shots (terminam numa pose estável e nunca repetem): ATTENTION, NOTIFICATION, TRANSITION.
 */
public enum MascotState {
    IDLE(true), THINKING(true), PROCESSING(true), SYNCING(true), ATTENTION(false), NOTIFICATION(false), TRANSITION(false);

    private final boolean loop;

    MascotState(boolean loop) {
        this.loop = loop;
    }

    public boolean loops() {
        return loop;
    }

    public boolean oneShot() {
        return !loop;
    }
}
