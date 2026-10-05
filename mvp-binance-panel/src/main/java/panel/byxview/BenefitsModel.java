package panel.byxview;

import java.util.List;
import panel.design.ByxBadge;
import panel.model.Entitlement;

/** Classificação honesta dos benefícios: o que tem lastro real de política e o que é só referência de interface. */
public final class BenefitsModel {
    private BenefitsModel() {
    }

    public static final List<String> TIERS = List.of("FREE", "HOLDER", "PLUS", "PRO");

    /** Classe de cada benefício na referência; nenhum tem valor financeiro real. */
    public enum Backing {
        TEST_POLICY("HOLD_TO_UNLOCK"), REFERENCE_ONLY("REFERENCE ONLY");

        public final String text;

        Backing(String text) {
            this.text = text;
        }
    }

    /** Extended History e Advanced Analytics têm política TEST real; Bot Controls e Premium Research são pré-visualizações. */
    public static Backing backing(Entitlement e) {
        return backing(e.id());
    }

    public static String status(Entitlement e) {
        return switch (e.status()) {
            case UNLOCKED -> "UNLOCKED";
            case LOCKED -> "LOCKED · " + e.requiredTier();
            case UNAVAILABLE -> "UNAVAILABLE";
        };
    }

    public static ByxBadge.Tone tone(Entitlement e) {
        return switch (e.status()) {
            case UNLOCKED -> ByxBadge.Tone.POSITIVE;
            case LOCKED -> ByxBadge.Tone.NEUTRAL;
            case UNAVAILABLE -> ByxBadge.Tone.WARNING;
        };
    }

    public static String name(Entitlement e) {
        int dash = e.displayName().indexOf(" — ");
        return dash < 0 ? e.displayName() : e.displayName().substring(0, dash);
    }

    /** Recurso de referência: id, nome, tier exigido (usado quando nenhuma carteira permite ler os reais). */
    public record Feature(String id, String name, String tier) {
    }

    public static final List<Feature> FEATURES = List.of(new Feature("extended_history", "Extended History", "HOLDER"),
            new Feature("advanced_analytics", "Advanced Analytics", "PLUS"),
            new Feature("advanced_bot_controls", "Advanced Bot Controls", "PLUS"),
            new Feature("premium_research_tools", "Premium Research Tools", "PRO"));

    public static Backing backing(String id) {
        return switch (id) {
            case "extended_history", "advanced_analytics" -> Backing.TEST_POLICY;
            default -> Backing.REFERENCE_ONLY;
        };
    }

    /** O que o BYX nunca concede (referência); constantes de política, não dados. */
    public static final List<String> NEVER = List.of("Admin access", "Validation", "Final holdout", "Unapproved live trading");
}
