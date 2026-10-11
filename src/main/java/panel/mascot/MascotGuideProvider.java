package panel.mascot;

import java.util.Optional;

/** Fonte de dicas. Hoje só {@link StaticMascotGuideProvider} (textos locais, determinísticos). Costura para um assistente futuro; nada de rede/LLM/analytics nesta fase. */
public interface MascotGuideProvider {
    Optional<MascotHint> hint(MascotContext context);
}
