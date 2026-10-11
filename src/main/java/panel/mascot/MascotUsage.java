package panel.mascot;

import java.util.Optional;

/**
 * Matriz de USO do mascote (identidade do produto, NÃO um spinner universal). Uma presença dominante por contexto. NUNCA para erro/segurança: senha, MFA, elevação de admin, aviso de segurança, falha
 * de configuração de segredo, NETWORK_MISMATCH e blocker científico usam os componentes normais (estas funções devolvem vazio para eles). {@code animate=false} = só o poster estático.
 */
public final class MascotUsage {
    private MascotUsage() { }

    /** steady: estado estável; oneShot: um-tiro ao ENTRAR neste estado (depois fica o estável); animate: se o estável anima (senão, poster). */
    public record Plan(MascotState steady, MascotState oneShot, boolean animate) { }

    public enum Research { LOCAL_PROCESSING, VALIDATION_CHECK, WAITING_FOR_DATA, SCIENTIFIC_ERROR }

    /** Estado da chain pública (valores de ChainState do serviço). */
    public static Optional<Plan> forChain(String chainState) {
        if (chainState == null) {
            return Optional.empty();
        }
        return switch (chainState) {
            case "NOT_CONFIGURED", "LIVE" -> Optional.of(new Plan(MascotState.IDLE, null, true)); // IDLE vivo (procedural, barato): sem animação "de loading" constante
            case "CONNECTING" -> Optional.of(new Plan(MascotState.THINKING, null, true));
            case "SYNCING" -> Optional.of(new Plan(MascotState.SYNCING, null, true));
            case "OFFLINE", "STALE" -> Optional.of(new Plan(MascotState.IDLE, MascotState.ATTENTION, true)); // ATTENTION uma vez, depois volta ao IDLE vivo
            default -> Optional.empty(); // NETWORK_MISMATCH, ERROR, desconhecido: UI de erro normal, nada de mascote
        };
    }

    public static Optional<Plan> forResearch(Research r) {
        return switch (r) {
            case LOCAL_PROCESSING -> Optional.of(new Plan(MascotState.PROCESSING, null, true));
            case VALIDATION_CHECK -> Optional.of(new Plan(MascotState.THINKING, null, true));
            case WAITING_FOR_DATA -> Optional.of(new Plan(MascotState.IDLE, null, true));
            case SCIENTIFIC_ERROR -> Optional.empty();
        };
    }

    /** Autenticação e segurança: o mascote animado NUNCA é usado (no máximo um poster neutro decorativo, que não comunica estado). */
    public static boolean allowedForSecurity() {
        return false;
    }

    /** Aplica o plano numa view (só na thread FX). */
    public static void apply(MascotView view, Plan plan, boolean entering) {
        if (plan.animate()) {
            view.setState(plan.steady());
        } else {
            view.setStaticState(plan.steady());
        }
        if (plan.oneShot() != null && entering) {
            view.play(plan.oneShot());
        }
    }
}
