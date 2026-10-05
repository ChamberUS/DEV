package panel.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import panel.design.StatusState;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;

/**
 * Converte o estado real dos serviços nos grupos do dock (SYSTEM HEALTH, MODE, ENVIRONMENT). Nada é inventado:
 * o que não pode ser lido é UNKNOWN; "nada configurado" (feed NOT_CONFIGURED, captura parada) usa o ponto
 * neutro; queda é vermelha. Live trading OFF é texto primário, nunca vermelho. Itens com tela real apontam
 * para ela; os demais esperam a System Status (passo 12) e não navegam.
 */
public final class DockModel {
    /** Rota do detalhe: o dock resume, System Status explica. */
    public static final String STATUS_ROUTE = "sys-status";

    private DockModel() {
    }

    public static StatusState backend(Snapshot s) {
        return s.backendOnline ? StatusState.OPERATIONAL : StatusState.UNAVAILABLE;
    }

    /** Estado do feed; o booleano do par indica "nada configurado" (neutro, não queda). */
    public static Object[] feed(String feed) {
        if (feed == null) {
            return new Object[] {StatusState.UNKNOWN, false};
        }
        return switch (feed.toUpperCase(Locale.ROOT)) {
            case "NOT_CONFIGURED" -> new Object[] {StatusState.UNAVAILABLE, true};
            case "WAITING", "CONNECTING", "AWAITING" -> new Object[] {StatusState.CONNECTING, false};
            case "RECONNECTING" -> new Object[] {StatusState.RECONNECTING, false};
            case "LIVE", "CONNECTED", "ONLINE" -> new Object[] {StatusState.OPERATIONAL, false};
            case "STALE", "DEGRADED", "MOCK" -> new Object[] {StatusState.DEGRADED, false};
            case "OFFLINE", "DISCONNECTED" -> new Object[] {StatusState.UNAVAILABLE, false};
            default -> new Object[] {StatusState.UNKNOWN, false};
        };
    }

    public static Object[] capture(String recorder) {
        if (recorder == null) {
            return new Object[] {StatusState.UNKNOWN, false};
        }
        return switch (recorder.toUpperCase(Locale.ROOT)) {
            case "RUNNING" -> new Object[] {StatusState.OPERATIONAL, false};
            case "STOPPED" -> new Object[] {StatusState.UNAVAILABLE, true};
            default -> new Object[] {StatusState.UNKNOWN, false};
        };
    }

    public static StatusState network(String connection) {
        if (connection == null) {
            return StatusState.UNKNOWN;
        }
        return switch (connection.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> StatusState.OPERATIONAL;
            case "SYNCING" -> StatusState.CONNECTING;
            case "DEGRADED", "STALE" -> StatusState.DEGRADED;
            case "OFFLINE" -> StatusState.UNAVAILABLE;
            default -> StatusState.UNKNOWN;
        };
    }

    private static String title(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String r = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }

    /**
     * walletText vem da identidade BYX real; adminSession da autorização real; captureRoute só existe para
     * quem pode abrir Capture (admin).
     */
    public static List<StatusDock.Group> build(Snapshot s, TraderSnapshot t, ByxSnapshot network, String walletText,
            boolean adminSession, boolean captureRoute) {
        Object[] feed = feed(t.feed);
        Object[] cap = capture(s.capture.recorder());
        List<StatusDock.Item> health = List.of(
                StatusDock.Item.status("backend", "Backend", backend(s), false, STATUS_ROUTE, null),
                StatusDock.Item.status("feed", "Market feed", (StatusState) feed[0], (boolean) feed[1], "t-markets", null),
                StatusDock.Item.status("capture", "Capture", (StatusState) cap[0], (boolean) cap[1],
                        captureRoute ? "capture" : STATUS_ROUTE, null),
                StatusDock.Item.status("network", "Network", network(network.connection()), false, "t-byx", null));
        boolean liveOff = !"ENABLED".equalsIgnoreCase(t.trading);
        List<StatusDock.Item> mode = List.of(
                StatusDock.Item.text("mode", title(t.mode), null, STATUS_ROUTE, null),
                StatusDock.Item.text("paper", "Paper/Shadow locked", "tertiary", STATUS_ROUTE, null),
                StatusDock.Item.text("live", liveOff ? "Live trading OFF" : "Live trading " + t.trading, "primary", STATUS_ROUTE, null));
        List<StatusDock.Item> env = new ArrayList<>();
        env.add(StatusDock.Item.text("environment", network.environment() == null ? "Unknown" : network.environment(), null, "t-byx", null));
        env.add(StatusDock.Item.text("wallet", walletText, null, "t-wallet", null));
        if (adminSession) {
            env.add(StatusDock.Item.text("auth", "Admin session", null, "t-profile", null));
        }
        return List.of(new StatusDock.Group("SYSTEM HEALTH", health), new StatusDock.Group("MODE", mode),
                new StatusDock.Group("ENVIRONMENT", env));
    }
}
