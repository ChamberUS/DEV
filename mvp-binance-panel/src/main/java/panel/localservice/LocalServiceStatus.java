package panel.localservice;

import java.time.Instant;
import java.util.Map;

/**
 * Estado do serviço local visto pelo painel. Só carrega o que o serviço devolve de não sensível; nenhum caminho, segredo, prova ou
 * texto livre do serviço entra aqui (a razão é um código fixo do próprio painel).
 */
public record LocalServiceStatus(State state, String code, boolean everConnected, String version, int protocol, long uptimeSeconds, String instance,
        Map<String, Boolean> features, Instant checkedAt) {
    public enum State {
        /** Ainda sem leitura. */
        UNKNOWN,
        CONNECTED,
        /** Nada escutando (serviço não iniciado, reiniciando ou socket ausente) ou sem resposta no prazo. */
        UNAVAILABLE,
        /** O serviço não provou conhecer o segredo, ou recusou a nossa prova. */
        AUTH_FAILED,
        /** O arquivo/diretório de pareamento não é privado (dono, permissão, symlink): nem se tenta conectar. */
        INSECURE_PAIRING,
        /** Protocolo fora da faixa suportada ou resposta fora do contrato. */
        INCOMPATIBLE
    }

    public static LocalServiceStatus unknown() {
        return new LocalServiceStatus(State.UNKNOWN, "not_checked", false, null, 0, 0, null, Map.of(), null);
    }

    public static LocalServiceStatus failed(State state, String code, boolean everConnected, Instant at) {
        return new LocalServiceStatus(state, code, everConnected, null, 0, 0, null, Map.of(), at);
    }

    public boolean connected() {
        return state == State.CONNECTED;
    }

    /** Capacidade privada: sempre falso nesta fundação (o serviço declara bloqueio e o painel não o contradiz). */
    public boolean feature(String name) {
        return state == State.CONNECTED && Boolean.TRUE.equals(features.get(name));
    }

    /** Texto curto para diagnóstico: só estado, protocolo e versão (nada de caminhos). */
    public String summary() {
        return switch (state) {
            case CONNECTED -> "CONNECTED · protocol " + protocol + " · v" + version;
            case UNKNOWN -> "UNKNOWN";
            default -> state.name() + " · " + code;
        };
    }
}
