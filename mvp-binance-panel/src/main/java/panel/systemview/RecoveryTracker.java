package panel.systemview;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import panel.design.StatusState;

/**
 * Recuperação de conexão (P3.8) como representação do estado REAL: CONNECTED, DISCONNECTED, RECONNECTING (só quando o próprio
 * serviço informa), RESTORED (retido 3 s, um aviso) e RETRY FAILED (depois de um retry real que não restaurou). Não existe
 * contagem de tentativas inventada ("n de 3"): nenhum serviço reporta tentativas. Um serviço que nunca esteve de pé
 * nunca "caiu": fica fora da recuperação (esperado/indisponível).
 */
public final class RecoveryTracker {
    public enum State { CONNECTED, DISCONNECTED, RECONNECTING, RESTORED, RETRY_FAILED }

    public static final Duration RESTORED_HOLD = Duration.ofSeconds(3);

    public enum Event { NONE, LOST, RESTORED, RETRY_FAILED }

    private static final class Entry {
        boolean everUp;
        State state = State.CONNECTED;
        Instant restoredAt;
        boolean retrying;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** Atualiza com a leitura atual; devolve o evento (para o único toast de restauração e a barra global). */
    public Event update(String id, StatusState now, Instant at) {
        Entry e = entries.computeIfAbsent(id, k -> new Entry());
        boolean up = now == StatusState.OPERATIONAL || now == StatusState.DEGRADED;
        boolean down = now == StatusState.UNAVAILABLE;
        boolean reconnecting = now == StatusState.CONNECTING || now == StatusState.RECONNECTING;
        Event event = Event.NONE;
        if (up) {
            if (e.state == State.DISCONNECTED || e.state == State.RECONNECTING || e.state == State.RETRY_FAILED) {
                e.state = State.RESTORED;
                e.restoredAt = at;
                e.retrying = false;
                event = Event.RESTORED;
            } else if (e.state == State.RESTORED && !at.isBefore(e.restoredAt.plus(RESTORED_HOLD))) {
                e.state = State.CONNECTED;
            }
            e.everUp = true;
        } else if (e.everUp && (down || reconnecting)) {
            if (reconnecting) {
                e.state = State.RECONNECTING;
            } else if (e.state == State.CONNECTED || e.state == State.RESTORED) {
                e.state = State.DISCONNECTED;
                event = Event.LOST;
            } else if (e.state == State.RECONNECTING) {
                e.state = State.DISCONNECTED;
            }
        }
        return event;
    }

    /** O usuário pediu uma nova tentativa real (refresh). */
    public void retryStarted(String id) {
        Entry e = entries.get(id);
        if (e != null && e.state != State.CONNECTED) {
            e.retrying = true;
        }
    }

    /** O retry terminou e o serviço continua fora: RETRY FAILED (Retry continua disponível). */
    public Event retryFinished(String id, StatusState now) {
        Entry e = entries.get(id);
        if (e == null || !e.retrying) {
            return Event.NONE;
        }
        e.retrying = false;
        if (now == StatusState.UNAVAILABLE && (e.state == State.DISCONNECTED || e.state == State.RECONNECTING)) {
            e.state = State.RETRY_FAILED;
            return Event.RETRY_FAILED;
        }
        return Event.NONE;
    }

    public State state(String id) {
        Entry e = entries.get(id);
        return e == null ? State.CONNECTED : e.state;
    }

    /** O serviço já esteve de pé nesta sessão e agora não está: a barra global e o chip de recuperação só existem para isso. */
    public boolean lost(String id) {
        State s = state(id);
        return s == State.DISCONNECTED || s == State.RECONNECTING || s == State.RETRY_FAILED;
    }

    public List<String> lostServices() {
        List<String> out = new ArrayList<>();
        entries.forEach((k, v) -> {
            if (lost(k)) {
                out.add(k);
            }
        });
        return out;
    }

    public void reset() {
        entries.clear();
    }
}
