package byx.service.auth;

import byx.service.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Auditoria de segurança do serviço (estado SEPARADO de credenciais, autorização, sessão e limitador): só códigos fixos e o id opaco da conta. Nunca nome digitado, senha, OTP ou token. */
public final class AuthAudit {
    public record Entry(long atMs, String event, String actor) {
    }

    private static final int MAX = 1_000;
    private final Deque<Entry> ring = new ArrayDeque<>();
    private final java.time.Clock clock;

    public AuthAudit(java.time.Clock clock) {
        this.clock = clock;
    }

    synchronized void record(String event, String actor) {
        if (ring.size() >= MAX) {
            ring.pollFirst();
        }
        ring.addLast(new Entry(clock.millis(), event, actor == null ? "-" : actor));
        Log.event("auth_audit", event);
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(ring);
    }
}
