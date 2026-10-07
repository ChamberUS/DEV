package panel.systemview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import panel.byxview.NetworkModel;
import panel.design.StatusState;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.shell.DockModel;

/**
 * Estado detalhado dos sete componentes (Backend, Market feed, Capture, Research, BYX node, Wallet, Authentication) a partir
 * de leituras reais. O que não pode ser lido é UNKNOWN, nunca OPERATIONAL; "nada configurado" é UNAVAILABLE esperado (neutro).
 * O dock é o resumo; esta é a mesma fonte com a razão e a idade do dado.
 */
public final class SystemStatusModel {
    private SystemStatusModel() {
    }

    /** retry: existe uma ação real de nova tentativa para o componente (nunca inventada). */
    public record Component(String id, String name, StatusState state, boolean expected, String reason, Instant lastUpdate, boolean retry) {
    }

    public record Inputs(Snapshot research, TraderSnapshot trading, ByxSnapshot network, boolean sessionActive, boolean adminSession,
            boolean walletLinked, boolean researchAvailable, panel.localservice.LocalServiceStatus service, panel.model.ScientificCapture capture) {
        /** Sem captura científica observada ainda. */
        public Inputs(Snapshot research, TraderSnapshot trading, ByxSnapshot network, boolean sessionActive, boolean adminSession, boolean walletLinked,
                boolean researchAvailable, panel.localservice.LocalServiceStatus service) {
            this(research, trading, network, sessionActive, adminSession, walletLinked, researchAvailable, service, panel.model.ScientificCapture.unknown("not observed yet"));
        }

        /** Sem serviço local conhecido (ainda não sondado). */
        public Inputs(Snapshot research, TraderSnapshot trading, ByxSnapshot network, boolean sessionActive, boolean adminSession, boolean walletLinked,
                boolean researchAvailable) {
            this(research, trading, network, sessionActive, adminSession, walletLinked, researchAvailable, panel.localservice.LocalServiceStatus.unknown(),
                    panel.model.ScientificCapture.unknown("not observed yet"));
        }
    }

    public static List<Component> components(Inputs in) {
        List<Component> out = new ArrayList<>();
        Snapshot s = in.research();
        StatusState backend = DockModel.backend(s);
        out.add(new Component("backend", "Backend", backend, false, backend == StatusState.OPERATIONAL ? "Local backend answers."
                : "The local backend is not reachable" + (s.backendNote == null || s.backendNote.isBlank() ? "." : " (" + s.backendNote + ")."),
                s.loadedAt, true));
        Object[] feed = DockModel.feed(in.trading().feed);
        StatusState feedState = (StatusState) feed[0];
        boolean feedExpected = (boolean) feed[1];
        out.add(new Component("feed", "Market feed", feedState, feedExpected, feedReason(feedState, feedExpected, in.trading().feed),
                in.trading().feedUpdatedAt, false));
        Object[] cap = DockModel.capture(in.capture());
        StatusState capState = (StatusState) cap[0];
        boolean capExpected = (boolean) cap[1];
        out.add(new Component("capture", "Scientific capture", capState, capExpected, switch (capState) {
            case OPERATIONAL -> "The scientific recorder is running.";
            case DEGRADED -> "The recorder is running with a current risk: " + (in.capture().reason() == null ? "see Capture." : in.capture().reason() + ".");
            case UNAVAILABLE -> "The scientific recorder is stopped.";
            default -> "The recorder state can not be confirmed" + (in.capture() == null || in.capture().reason() == null ? "." : " (" + in.capture().reason() + ").");
        }, in.capture() == null ? null : in.capture().checkedAt(), false));
        StatusState research = !in.researchAvailable() ? StatusState.UNAVAILABLE : backend == StatusState.OPERATIONAL ? StatusState.OPERATIONAL : StatusState.UNKNOWN;
        out.add(new Component("research", "Research", research, !in.researchAvailable(), !in.researchAvailable()
                ? "Research is available to administrators only." : research == StatusState.OPERATIONAL
                ? "Pipeline readable. VALIDATION locked, FINAL_HOLDOUT sealed." : "Research depends on the backend, which can not be read.", s.loadedAt, false));
        ByxSnapshot n = in.network();
        StatusState node = DockModel.network(n.connection());
        out.add(new Component("node", "BYX node", node, "UNKNOWN".equals(n.connection()) && n.height() == null, "BYX network: " + NetworkModel.state(n).text.toLowerCase(java.util.Locale.ROOT) + ".",
                n.updatedAt(), true));
        out.add(serviceComponent(in.service()));
        boolean verified = "VERIFIED".equals(n.identity());
        out.add(new Component("wallet", "Wallet", !verified ? StatusState.UNAVAILABLE : in.walletLinked() ? StatusState.OPERATIONAL : StatusState.UNAVAILABLE,
                !verified || !in.walletLinked(), !verified ? "The network identity is not verified, so no wallet can be read."
                        : in.walletLinked() ? "A verified wallet is linked." : "No wallet is linked.", null, false));
        out.add(new Component("auth", "Authentication", in.sessionActive() ? StatusState.OPERATIONAL : StatusState.UNAVAILABLE, false,
                !in.sessionActive() ? "No active session." : in.adminSession() ? "Signed in with an active admin session." : "Signed in. Admin verification not active.", null, false));
        return out;
    }

    public static StatusState serviceState(panel.localservice.LocalServiceStatus s) {
        return switch (s.state()) {
            case CONNECTED -> StatusState.OPERATIONAL;
            case UNKNOWN -> StatusState.UNKNOWN;
            case INCOMPATIBLE -> StatusState.DEGRADED;
            default -> StatusState.UNAVAILABLE;
        };
    }

    /** Serviço local: opcional nesta build. Nunca "operacional" sem uma resposta autenticada; as razões são textos fixos, nunca do serviço. */
    static Component serviceComponent(panel.localservice.LocalServiceStatus s) {
        StatusState state = serviceState(s);
        boolean expected = s.state() == panel.localservice.LocalServiceStatus.State.UNAVAILABLE && !s.everConnected();
        String reason = switch (s.state()) {
            case UNKNOWN -> "Not checked yet.";
            case CONNECTED -> "Local service answers (protocol " + s.protocol() + "). Account data and admin operations stay blocked: user identity and authorization "
                    + "are not implemented in the service yet.";
            case UNAVAILABLE -> s.everConnected() ? "The local service stopped answering." : "The local service is not running. It is optional in this build.";
            case AUTH_FAILED -> "The service did not prove the pairing secret, or rejected ours. Nothing was sent to it.";
            case INSECURE_PAIRING -> "The pairing files are not private to this user, so the panel refused to connect.";
            case INCOMPATIBLE -> "The service answered outside the supported contract.";
        };
        return new Component("service", "Local service", state, expected, reason, s.checkedAt(), true);
    }

    private static String feedReason(StatusState state, boolean expected, String feed) {
        return switch (state) {
            case OPERATIONAL -> "Receiving market data.";
            case CONNECTING -> "Waiting for the market feed.";
            case RECONNECTING -> "Reconnecting to the market feed.";
            case DEGRADED -> feed == null ? "Degraded." : "Feed is " + feed.toLowerCase(java.util.Locale.ROOT) + ".";
            case UNAVAILABLE -> expected ? "No market feed is configured in this build." : "The market feed is disconnected.";
            case UNKNOWN -> "The feed state can not be read.";
        };
    }

    /** Idade do dado em texto curto ("—" quando não há horário; nunca inventa). */
    public static String age(Instant at, Instant now) {
        if (at == null) {
            return "—";
        }
        long s = Math.max(0, java.time.Duration.between(at, now).getSeconds());
        return s < 60 ? s + "s ago" : s < 3600 ? s / 60 + " min ago" : s / 3600 + " h ago";
    }
}
