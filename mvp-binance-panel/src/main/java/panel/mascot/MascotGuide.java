package panel.mascot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Política do guia: o mascote NÃO fala toda hora. Dicas AUTOMÁTICAS ({@link #offer}) respeitam cooldown por contexto ({@link #COOLDOWN_MS}) e as de primeira visita saem UMA vez por sessão; quando o
 * usuário PEDE (clique, {@link #ask}) a resposta é sempre dada. Só em memória (sessão atual): nada é persistido nem enviado. Nunca insiste, bloqueia, culpa, pressiona nem pede para habilitar nada.
 */
public final class MascotGuide {
    public static final long COOLDOWN_MS = 120_000;
    private static final MascotGuide SESSION = new MascotGuide(new StaticMascotGuideProvider(), System::currentTimeMillis);

    private final MascotGuideProvider provider;
    private final LongSupplier clock;
    private final Map<MascotContext, Long> lastShown = new EnumMap<>(MascotContext.class);

    public MascotGuide(MascotGuideProvider provider, LongSupplier clock) {
        this.provider = provider;
        this.clock = clock;
    }

    /** Guia da sessão atual do app. */
    public static MascotGuide session() {
        return SESSION;
    }

    /** Dica automática: vazia se não houver texto, se o cooldown não passou ou se uma primeira visita já foi dada nesta sessão. */
    public synchronized Optional<MascotHint> offer(MascotContext c) {
        Optional<MascotHint> h = provider.hint(c);
        if (h.isEmpty()) {
            return h;
        }
        long now = clock.getAsLong();
        Long prev = lastShown.get(c);
        if (prev != null && (c.firstVisit() || now - prev < COOLDOWN_MS)) {
            return Optional.empty();
        }
        lastShown.put(c, now);
        return h;
    }

    /** Pedido explícito do usuário: sempre responde (se houver texto) e registra o horário. */
    public synchronized Optional<MascotHint> ask(MascotContext c) {
        Optional<MascotHint> h = provider.hint(c);
        h.ifPresent(x -> lastShown.put(c, clock.getAsLong()));
        return h;
    }

    public synchronized void resetSession() {
        lastShown.clear();
    }
}
