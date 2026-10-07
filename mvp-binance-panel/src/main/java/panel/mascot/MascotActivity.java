package panel.mascot;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Política de QUANDO mostrar o mascote para trabalho em andamento (sem flicker, sem bloquear resultado). Operações abaixo de {@link #SHOW_AFTER_MS} não mostram nada; depois disso, QUERY = THINKING
 * (escala para PROCESSING após {@link #ESCALATE_AFTER_MS}), TASK = PROCESSING, SYNC = SYNCING. Com várias operações ao mesmo tempo vale a mais "forte" (SYNC > TASK > QUERY). Ao terminar a ÚLTIMA, o
 * sink recebe vazio IMEDIATAMENTE (o resultado nunca espera a animação). Lógica pura: o relógio é injetado (testes); a thread é a do chamador (FX).
 */
public final class MascotActivity {
    public enum Kind { QUERY, TASK, SYNC }

    public static final long SHOW_AFTER_MS = 250;
    public static final long ESCALATE_AFTER_MS = 3_000;

    public interface Scheduler {
        Handle schedule(long delayMs, Runnable r);

        interface Handle {
            void cancel();
        }
    }

    public static final class Token {
        private final Kind kind;
        private boolean open = true;

        private Token(Kind kind) {
            this.kind = kind;
        }
    }

    private final Scheduler scheduler;
    private final Consumer<Optional<MascotState>> sink;
    private final java.util.List<Token> active = new java.util.ArrayList<>();
    private Scheduler.Handle showTimer;
    private Scheduler.Handle escalateTimer;
    private MascotState shown;
    private boolean escalated;

    public MascotActivity(Scheduler scheduler, Consumer<Optional<MascotState>> sink) {
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public Token begin(Kind kind) {
        Token t = new Token(kind);
        active.add(t);
        if (shown != null) {
            publish();
        } else if (showTimer == null) {
            showTimer = scheduler.schedule(SHOW_AFTER_MS, () -> {
                showTimer = null;
                if (!active.isEmpty()) {
                    publish();
                }
            });
        }
        if (escalateTimer == null) {
            escalateTimer = scheduler.schedule(ESCALATE_AFTER_MS, () -> {
                escalateTimer = null;
                escalated = true;
                if (shown != null) {
                    publish();
                }
            });
        }
        return t;
    }

    /** Idempotente: terminar duas vezes ou com token desconhecido não faz nada. */
    public void end(Token t) {
        if (t == null || !t.open) {
            return;
        }
        t.open = false;
        active.remove(t);
        if (!active.isEmpty()) {
            if (shown != null) {
                publish();
            }
            return;
        }
        cancel(showTimer);
        cancel(escalateTimer);
        showTimer = null;
        escalateTimer = null;
        escalated = false;
        if (shown != null) {
            shown = null;
            sink.accept(Optional.empty());
        }
    }

    /** Estado hoje exibido pela atividade (vazio = nada). */
    public Optional<MascotState> shown() {
        return Optional.ofNullable(shown);
    }

    private void publish() {
        MascotState s = strongest();
        if (s != shown) {
            shown = s;
            sink.accept(Optional.of(s));
        }
    }

    private MascotState strongest() {
        boolean sync = false;
        boolean task = false;
        for (Token t : active) {
            sync |= t.kind == Kind.SYNC;
            task |= t.kind == Kind.TASK;
        }
        if (sync) {
            return MascotState.SYNCING;
        }
        return task || escalated ? MascotState.PROCESSING : MascotState.THINKING;
    }

    private static void cancel(Scheduler.Handle h) {
        if (h != null) {
            h.cancel();
        }
    }

    /** Scheduler JavaFX (PauseTransition): só na thread FX. */
    public static Scheduler fx() {
        return (ms, r) -> {
            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(ms));
            p.setOnFinished(e -> r.run());
            p.play();
            return p::stop;
        };
    }
}
