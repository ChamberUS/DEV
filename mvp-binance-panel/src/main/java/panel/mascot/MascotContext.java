package panel.mascot;

import java.util.Optional;

/**
 * Contextos FECHADOS do guia local. As telas pedem um contexto tipado; nenhum texto fica espalhado pelas telas (ver {@link StaticMascotGuideProvider}). Não existe contexto de autenticação, MFA,
 * segredo, permissão ou segurança: o guia nunca fala nesses casos.
 */
public enum MascotContext {
    CHAIN_NOT_CONFIGURED, CHAIN_CONNECTING, CHAIN_OFFLINE, CHAIN_SYNCING, CHAIN_LIVE, CHAIN_STALE, CHAIN_MISMATCH,
    EMPTY_MERCHANTS, EMPTY_PAYMENTS, EMPTY_CERTIFICATES,
    MERCHANT_NOT_FOUND, PAYMENT_NOT_FOUND, CERTIFICATE_NOT_FOUND,
    FIRST_VISIT_CHAIN_DATA, FIRST_VISIT_RESEARCH, FIRST_VISIT_TRADING,
    RESEARCH_WAITING, GENERIC_IDLE;

    /** Estado do serviço (ChainState) -> contexto. */
    public static Optional<MascotContext> forChain(String chainState) {
        if (chainState == null) {
            return Optional.empty();
        }
        return switch (chainState) {
            case "NOT_CONFIGURED" -> Optional.of(CHAIN_NOT_CONFIGURED);
            case "CONNECTING" -> Optional.of(CHAIN_CONNECTING);
            case "OFFLINE" -> Optional.of(CHAIN_OFFLINE);
            case "SYNCING" -> Optional.of(CHAIN_SYNCING);
            case "LIVE" -> Optional.of(CHAIN_LIVE);
            case "STALE" -> Optional.of(CHAIN_STALE);
            case "NETWORK_MISMATCH" -> Optional.of(CHAIN_MISMATCH);
            default -> Optional.empty();
        };
    }

    /** Contextos de primeira visita (uma vez por sessão). */
    public boolean firstVisit() {
        return name().startsWith("FIRST_VISIT_");
    }

    /** Contextos que sugerem uma reação one-shot ao aparecer por um evento (não por clique). */
    public boolean notFound() {
        return name().endsWith("_NOT_FOUND");
    }
}
