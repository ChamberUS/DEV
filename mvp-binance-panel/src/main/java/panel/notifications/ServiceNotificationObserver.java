package panel.notifications;

import java.util.UUID;
import panel.localservice.LocalServiceStatus;
import panel.notifications.NotificationEvent.Type;
import panel.notifications.NotificationCenter.SourceState;

/** Consumes existing monitor results; never probes or starts work. Timeout is explicitly uncertain. */
public final class ServiceNotificationObserver {
    private final NotificationCenter center;
    private LocalServiceStatus.State last;
    private boolean connected, confirmedLoss;
    private SourceState lastSource;
    public ServiceNotificationObserver(NotificationCenter center) { this.center = center; }
    public void reset() { last = null; connected = false; confirmedLoss = false; lastSource = null; }
    public void observe(NotificationCenter.Scope scope, LocalServiceStatus status) {
        if (!center.accepts(scope)) return;
        boolean definite = status.state() == LocalServiceStatus.State.UNAVAILABLE
                && ("not_started".equals(status.code()) || "refused".equals(status.code()));
        SourceState state = switch (status.state()) {
            case UNKNOWN -> SourceState.UNKNOWN;
            case CONNECTED -> SourceState.CONNECTED;
            case UNAVAILABLE -> definite ? SourceState.OFFLINE : SourceState.UNCERTAIN;
            default -> SourceState.ERROR;
        };
        center.source(scope, state);
        if (status.state() == last && state == lastSource) {
            if (last == LocalServiceStatus.State.AUTH_FAILED || last == LocalServiceStatus.State.INSECURE_PAIRING)
                center.repeatSecurity(scope, Type.SERVICE_SECURITY);
            return;
        }
        Type type = null;
        if (status.connected()) {
            if (confirmedLoss) type = Type.SERVICE_RESTORED;
            connected = true; confirmedLoss = false;
        } else if (definite) {
            type = connected ? Type.SERVICE_LOST : Type.SERVICE_UNAVAILABLE;
            if (connected) confirmedLoss = true;
        } else if (state == SourceState.UNCERTAIN) type = Type.SERVICE_UNCERTAIN;
        else if (status.state() == LocalServiceStatus.State.AUTH_FAILED || status.state() == LocalServiceStatus.State.INSECURE_PAIRING) type = Type.SERVICE_SECURITY;
        else if (status.state() == LocalServiceStatus.State.INCOMPATIBLE) type = Type.SERVICE_CONTRACT;
        last = status.state(); lastSource = state;
        if (type != null && status.checkedAt() != null) center.publish(scope, type, UUID.randomUUID(), status.checkedAt());
    }
}
