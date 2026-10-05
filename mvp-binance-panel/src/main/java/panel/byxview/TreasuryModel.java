package panel.byxview;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import panel.design.ByxBadge;
import panel.model.TreasuryAsset;
import panel.model.TreasurySnapshot;

/**
 * Tesouraria por faixa. As quatro classes nunca se misturam e nenhum total é calculado: cada saldo aparece na sua
 * própria unidade/categoria. BYX nunca é convertido para USD.
 */
public final class TreasuryModel {
    private TreasuryModel() {
    }

    public enum Lane {
        REAL("Real verified", "ON_CHAIN and EXTERNAL_CUSTODY only"), TEST("Test", "LOCALNET · no financial value"),
        PAPER("Paper", "Simulated capital for Bot"), MANUAL("Manual, unverified", "Excluded from every verified total");

        public final String title;
        public final String description;

        Lane(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    public enum Availability { NOT_CONFIGURED, VERIFIED, UNVERIFIED, UNAVAILABLE }

    public record LaneState(Lane lane, String badge, ByxBadge.Tone tone, List<String> lines) {
    }

    public static Lane laneOf(TreasuryAsset a) {
        return switch (a.source()) {
            case MANUAL_UNVERIFIED -> Lane.MANUAL;
            case PAPER -> Lane.PAPER;
            default -> a.testOnly() ? Lane.TEST : Lane.REAL;
        };
    }

    /** null = tesouraria ilegível (nenhuma leitura verificada): REAL fica NOT CONFIGURED, os outros UNAVAILABLE. */
    public static List<LaneState> lanes(TreasurySnapshot s, boolean readFailed) {
        List<LaneState> out = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            List<TreasuryAsset> assets = s == null ? List.of() : s.assets().stream().filter(a -> laneOf(a) == lane).toList();
            List<String> lines = assets.stream().map(a -> amount(a) + " · " + label(a.category())).toList();
            String badge;
            ByxBadge.Tone tone;
            if (!assets.isEmpty()) {
                boolean verified = assets.stream().allMatch(a -> a.verification() == TreasuryAsset.Verification.VERIFIED);
                badge = switch (lane) {
                    case REAL -> verified ? "VERIFIED" : "UNVERIFIED";
                    case TEST -> verified ? "TEST ASSET" : "UNVERIFIED";
                    case PAPER -> "PAPER";
                    case MANUAL -> "MANUAL_UNVERIFIED";
                };
                tone = switch (lane) {
                    case REAL -> verified ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.WARNING;
                    case TEST -> ByxBadge.Tone.WARNING;
                    case PAPER -> ByxBadge.Tone.INFO;
                    case MANUAL -> ByxBadge.Tone.NEGATIVE;
                };
            } else if (lane == Lane.REAL) {
                badge = "NOT CONFIGURED";
                tone = ByxBadge.Tone.NEUTRAL;
            } else if (readFailed && lane == Lane.TEST) {
                badge = "UNAVAILABLE";
                tone = ByxBadge.Tone.WARNING;
            } else {
                badge = lane == Lane.TEST ? "TEST ASSET" : lane == Lane.PAPER ? "PAPER" : "MANUAL_UNVERIFIED";
                tone = lane == Lane.TEST ? ByxBadge.Tone.WARNING : lane == Lane.PAPER ? ByxBadge.Tone.INFO : ByxBadge.Tone.NEGATIVE;
                lines = List.of(); // sem entradas: "—", não zero
            }
            out.add(new LaneState(lane, badge, tone, lines));
        }
        return out;
    }

    public static String amount(TreasuryAsset a) {
        return new BigDecimal(a.balance(), a.decimals()).toPlainString() + " " + a.asset();
    }

    public static String label(TreasuryAsset.Category c) {
        String t = c.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT).replace("byx", "BYX");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Rótulo de fonte para a tabela (a referência mostra a fonte, não o selo da faixa). */
    public static String sourceBadge(TreasuryAsset a) {
        return a.source().name();
    }

    public static ByxBadge.Tone sourceTone(TreasuryAsset.Source s) {
        return switch (s) {
            case PAPER -> ByxBadge.Tone.INFO;
            case MANUAL_UNVERIFIED -> ByxBadge.Tone.NEGATIVE;
            default -> ByxBadge.Tone.NEUTRAL;
        };
    }

    public static Availability availability(TreasurySnapshot s, boolean readFailed) {
        if (s == null) {
            return readFailed ? Availability.UNAVAILABLE : Availability.NOT_CONFIGURED;
        }
        return s.assets().stream().anyMatch(a -> a.verification() == TreasuryAsset.Verification.VERIFIED) ? Availability.VERIFIED
                : Availability.UNVERIFIED;
    }
}
