package panel.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import panel.auth.UserSession;
import panel.notifications.NotificationEvent.Type;

/** FX-owned, bounded current-session history. Scope invalidation is synchronous even off the FX thread. */
public final class NotificationCenter {
    public static final int CAPACITY = 100, ID_CAPACITY = 200;
    public static final Duration AGGREGATION = Duration.ofSeconds(30);
    public enum SourceState { UNKNOWN, CONNECTED, OFFLINE, UNCERTAIN, ERROR }
    public record Scope(long generation, UUID session, long account, boolean admin) { }
    private final AtomicLong generation = new AtomicLong();
    private volatile Scope owner;
    private final ObservableList<NotificationEvent> rows = FXCollections.observableArrayList();
    private final ObservableList<NotificationEvent> visible = FXCollections.unmodifiableObservableList(rows);
    private final LinkedHashMap<UUID, Boolean> seen = new LinkedHashMap<>();
    private final ReadOnlyIntegerWrapper unread = new ReadOnlyIntegerWrapper();
    private final ReadOnlyObjectWrapper<SourceState> source = new ReadOnlyObjectWrapper<>(SourceState.UNKNOWN);
    private long order;

    private static void fx() { if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Notification updates require FX thread"); }
    public ObservableList<NotificationEvent> events() { return visible; }
    public ReadOnlyIntegerProperty unreadProperty() { return unread.getReadOnlyProperty(); }
    public ReadOnlyObjectProperty<SourceState> sourceProperty() { return source.getReadOnlyProperty(); }
    public Scope scope() { return owner; }
    public boolean accepts(Scope scope) { return scope != null && scope.equals(owner) && scope.generation == generation.get(); }
    public boolean active() { return accepts(owner); }
    public void start(UserSession session) {
        fx(); invalidate(); owner = new Scope(generation.get(), session.id(), session.user().id(), session.user().admin());
        clear();
    }
    public void invalidate() {
        long g = generation.incrementAndGet(); owner = null;
        if (Platform.isFxApplicationThread()) clear();
        else Platform.runLater(() -> { if (generation.get() == g) clear(); });
    }
    private void clear() { fx(); rows.clear(); seen.clear(); unread.set(0); source.set(SourceState.UNKNOWN); order = 0; }
    public void source(Scope scope, SourceState state) { fx(); if (accepts(scope)) source.set(java.util.Objects.requireNonNull(state)); }
    public boolean publish(Scope scope, Type type, UUID identity, Instant at) {
        fx(); java.util.Objects.requireNonNull(type); java.util.Objects.requireNonNull(identity); java.util.Objects.requireNonNull(at);
        if (!accepts(scope) || (type.adminOnly && !scope.admin) || seen.containsKey(identity)) return false;
        seen.put(identity, true); while (seen.size() > ID_CAPACITY) seen.remove(seen.keySet().iterator().next());
        NotificationEvent prior = rows.stream().filter(e -> e.type() == type && !at.isBefore(e.at())
                && Duration.between(e.at(), at).compareTo(AGGREGATION) < 0).findFirst().orElse(null);
        if (prior != null) rows.remove(prior);
        NotificationEvent next = new NotificationEvent(prior == null ? identity : prior.id(), type, at, ++order, false,
                prior == null ? 1 : Math.min(Integer.MAX_VALUE - 1, prior.occurrences()) + 1);
        rows.add(next); rows.sort(java.util.Comparator.comparing(NotificationEvent::at).thenComparingLong(NotificationEvent::order).reversed());
        while (rows.size() > CAPACITY) rows.removeLast(); count(); return true;
    }
    /** Repeated rejected security observations are counted without new rows, alerts or read-state changes. */
    public void repeatSecurity(Scope scope, Type type) {
        fx(); if (!accepts(scope) || type != Type.SERVICE_SECURITY) return;
        for (int i = 0; i < rows.size(); i++) { var e = rows.get(i); if (e.type() == type) {
            rows.set(i, new NotificationEvent(e.id(), e.type(), e.at(), e.order(), e.read(), Math.min(Integer.MAX_VALUE - 1, e.occurrences()) + 1)); return;
        } }
    }
    public void read(UUID id, boolean read) {
        fx(); if (!active()) return;
        for (int i = 0; i < rows.size(); i++) { var e = rows.get(i); if (e.id().equals(id)) {
            rows.set(i, new NotificationEvent(e.id(), e.type(), e.at(), e.order(), read, e.occurrences())); break;
        } } count();
    }
    public void readAll() { fx(); if (active()) { rows.replaceAll(e -> new NotificationEvent(e.id(), e.type(), e.at(), e.order(), true, e.occurrences())); count(); } }
    private void count() { unread.set((int) rows.stream().filter(e -> !e.read()).count()); }
}
