package panel.byxview;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import panel.design.ByxBadge;
import panel.model.ByxSnapshot;
import panel.util.Fmt;

/** Estado de rede derivado só do {@link ByxSnapshot}; campo ausente é "—", nunca um valor inventado. */
public final class NetworkModel {
    public static final String NONE = "—";

    private NetworkModel() {
    }

    public enum State {
        AWAITING_NODE("AWAITING NODE", ByxBadge.Tone.NEUTRAL), SYNCING("SYNCING", ByxBadge.Tone.INFO),
        HEALTHY("HEALTHY", ByxBadge.Tone.POSITIVE), STALE("STALE", ByxBadge.Tone.WARNING),
        DEGRADED("DEGRADED", ByxBadge.Tone.WARNING), OFFLINE("OFFLINE", ByxBadge.Tone.NEGATIVE),
        IDENTITY_MISMATCH("IDENTITY MISMATCH", ByxBadge.Tone.NEGATIVE);

        public final String text;
        public final ByxBadge.Tone tone;

        State(String text, ByxBadge.Tone tone) {
            this.text = text;
            this.tone = tone;
        }

        /** Só estes dois representam atividade real; os outros não mostram movimento. */
        public boolean active() {
            return this == AWAITING_NODE || this == SYNCING;
        }
    }

    /** Os seis estados da legenda da referência (AWAITING NODE é o estado de espera, não uma legenda). */
    public static final List<State> LEGEND = List.of(State.HEALTHY, State.SYNCING, State.STALE, State.DEGRADED, State.OFFLINE,
            State.IDENTITY_MISMATCH);

    public static State state(ByxSnapshot s) {
        String connection = s.connection();
        if ("OFFLINE".equals(connection)) {
            return State.OFFLINE;
        }
        if ("UNKNOWN".equals(connection) && s.height() == null) {
            return State.AWAITING_NODE;
        }
        if ("ONLINE".equals(connection) && !"VERIFIED".equals(s.identity())) {
            return State.IDENTITY_MISMATCH;
        }
        if ("STALE".equals(s.freshness())) {
            return State.STALE;
        }
        if (Boolean.TRUE.equals(s.syncing())) {
            return State.SYNCING;
        }
        if ("ONLINE".equals(connection) && "FRESH".equals(s.freshness()) && Boolean.FALSE.equals(s.syncing())) {
            return State.HEALTHY;
        }
        return State.DEGRADED;
    }

    public static String value(String v) {
        return v == null || v.isBlank() ? NONE : v;
    }

    public static String block(ByxSnapshot s) {
        return s.blockTime() == null ? NONE : Fmt.dateTime(s.blockTime());
    }

    public static String sync(ByxSnapshot s) {
        return s.syncing() == null ? NONE : s.syncing() ? "Syncing" : "Caught up";
    }

    public static String freshness(ByxSnapshot s) {
        return "UNKNOWN".equals(s.freshness()) ? NONE : value(s.freshness());
    }

    public static String rpc(ByxSnapshot s) {
        return "UNKNOWN".equals(s.connection()) ? NONE : value(s.connection());
    }

    public static String identity(ByxSnapshot s) {
        return "UNVERIFIED".equals(s.identity()) && "UNKNOWN".equals(s.connection()) ? NONE : value(s.identity());
    }

    public static String environment(ByxSnapshot s) {
        return "UNKNOWN".equals(s.environment()) ? NONE : value(s.environment());
    }

    public static String age(ByxSnapshot s, Clock clock) {
        if (s.blockTime() == null) {
            return NONE;
        }
        Duration d = Duration.between(s.blockTime(), clock.instant());
        return d.isNegative() ? "0s" : Fmt.duration(d);
    }

    public static String balance(ByxSnapshot s) {
        return s.balance() == null ? NONE : s.formattedBalance();
    }
}
