package panel.notifications;

import java.time.Instant;
import java.util.UUID;

/** Closed, sanitized vocabulary: no raw payload, contact, key material or executable navigation. */
public record NotificationEvent(UUID id, Type type, Instant at, long order, boolean read, int occurrences) {
    public enum Severity { INFO, WARNING, ERROR }
    public enum Destination {
        STATUS("sys-status"), SECURITY("t-security"), RESEARCH("overview");
        public final String route;
        Destination(String route) { this.route = route; }
    }
    public enum Type {
        SERVICE_LOST("connectionLost", Severity.WARNING, Destination.STATUS, false),
        SERVICE_RESTORED("connectionRestored", Severity.INFO, Destination.STATUS, false),
        SERVICE_UNAVAILABLE("serviceOffline", Severity.WARNING, Destination.STATUS, false),
        SERVICE_UNCERTAIN("serviceUncertain", Severity.WARNING, Destination.STATUS, false),
        SERVICE_SECURITY("serviceSecurity", Severity.ERROR, Destination.STATUS, false),
        SERVICE_CONTRACT("serviceContract", Severity.ERROR, Destination.STATUS, false),
        RESEARCH_ACCESS_FAILED("researchFailed", Severity.ERROR, Destination.RESEARCH, true),
        ADMIN_EXPIRED("adminExpired", Severity.WARNING, Destination.SECURITY, true);
        public final String key;
        public final Severity severity;
        public final Destination destination;
        public final boolean adminOnly;
        Type(String key, Severity severity, Destination destination, boolean adminOnly) {
            this.key = "notification." + key; this.severity = severity; this.destination = destination; this.adminOnly = adminOnly;
        }
    }
}
