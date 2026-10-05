package panel.systemview;

import java.time.Duration;

/**
 * Contrato de startup (P3.5): sem splash se a inicialização termina em 800 ms; passado o limiar, o estado mínimo aparece e fica
 * até a rota estar pronta. Um pedido de start mais novo vence o antigo. Os tempos lógicos valem igualmente em FULL, REDUCED e OFF.
 */
public final class StartupModel {
    public static final Duration THRESHOLD = Duration.ofMillis(800);
    public static final Duration FADE_OUT = Duration.ofMillis(160);

    private long generation;
    private boolean ready;

    /** Novo pedido de start: invalida o anterior e devolve o token do pedido. */
    public synchronized long begin() {
        ready = false;
        return ++generation;
    }

    public synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    /** Marca a rota pronta; só vale para o pedido corrente. */
    public synchronized boolean ready(long token) {
        if (token != generation) {
            return false;
        }
        ready = true;
        return true;
    }

    /** A tela mínima aparece se, passado o limiar, o pedido corrente ainda não está pronto. */
    public synchronized boolean showsStartup(long token, Duration elapsed) {
        return token == generation && !ready && elapsed.compareTo(THRESHOLD) >= 0;
    }

    public static final java.util.List<String> STEPS = java.util.List.of("Interface", "Account", "Services", "Workspace");
}
