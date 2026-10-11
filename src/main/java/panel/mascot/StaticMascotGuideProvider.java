package panel.mascot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Textos do guia: profissionais, curtos e amigáveis, sem linguagem infantil nem antropomorfismo excessivo. LOCAIS: nada depende de internet, LLM, servidor ou analytics. O guia COMPLEMENTA a UI: nunca
 * substitui erro de segurança/validação/rede, negação de permissão nem blocker científico (por isso CHAIN_MISMATCH não tem dica e não há contexto de segurança).
 */
public final class StaticMascotGuideProvider implements MascotGuideProvider {
    private static final Map<MascotContext, MascotHint> HINTS = new EnumMap<>(MascotContext.class);

    private static void put(MascotContext c, String text, MascotState reaction) {
        HINTS.put(c, new MascotHint(c, text, reaction));
    }

    static {
        put(MascotContext.CHAIN_NOT_CONFIGURED, "This build has no BYX node configured, so there is no chain data to read.", null);
        put(MascotContext.CHAIN_CONNECTING, "Connecting to the local BYX node.", null);
        put(MascotContext.CHAIN_OFFLINE, "The local BYX node is offline.", null);
        put(MascotContext.CHAIN_SYNCING, "The node is catching up. I'll keep an eye on it.", null);
        put(MascotContext.CHAIN_LIVE, "BYX is live and synchronized.", null);
        put(MascotContext.CHAIN_STALE, "The node's latest block is not recent. Data may be behind.", null);
        put(MascotContext.EMPTY_MERCHANTS, "No merchants exist on this chain yet.", null);
        put(MascotContext.EMPTY_PAYMENTS, "No payments were found for that store.", null);
        put(MascotContext.EMPTY_CERTIFICATES, "No certificates were found for that merchant.", null);
        put(MascotContext.MERCHANT_NOT_FOUND, "No merchant was found with that ID.", MascotState.ATTENTION);
        put(MascotContext.PAYMENT_NOT_FOUND, "No payment was found with that ID.", MascotState.ATTENTION);
        put(MascotContext.CERTIFICATE_NOT_FOUND, "No certificate was found with that ID.", MascotState.ATTENTION);
        put(MascotContext.FIRST_VISIT_CHAIN_DATA, "This page reads public data from the BYX node. Nothing here can change the chain.", null);
        put(MascotContext.FIRST_VISIT_RESEARCH, "Research is read-only here: reports and validation status, never trading.", null);
        put(MascotContext.RESEARCH_WAITING, "Waiting for research data to become available.", null);
        put(MascotContext.GENERIC_IDLE, "Nothing needs your attention right now.", null);
        // sem dica: CHAIN_MISMATCH (UI de erro normal manda) e FIRST_VISIT_TRADING (só se houver algo realmente útil)
    }

    @Override
    public Optional<MascotHint> hint(MascotContext context) {
        return Optional.ofNullable(HINTS.get(context));
    }
}
