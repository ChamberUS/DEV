package panel.identity;

import java.nio.channels.SocketChannel;

/** Verifica QUEM está do outro lado de uma conexão Unix, pelo kernel e pela assinatura de código — nunca por dado enviado pelo cliente. */
public interface PeerVerifier {
    /** reason = código fixo ("ok" quando verified). Nunca carrega caminho, nome, PID, ambiente ou texto vindo do peer. */
    record Verdict(boolean verified, String reason) {
        public static Verdict ok() {
            return new Verdict(true, "ok");
        }

        public static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    Verdict verify(SocketChannel connection);
}
