package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.design.StatusState;
import panel.model.ByxSnapshot;
import panel.model.CaptureInfo;
import panel.model.ScientificCapture;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.shell.DockModel;
import panel.shell.StatusDock;

/** Dock só com estado real: ilegível = UNKNOWN, nada configurado = neutro, queda = vermelho, nada inventado. */
class DockModelTest {
    private static StatusDock.Item item(List<StatusDock.Group> m, String key) {
        return m.stream().flatMap(g -> g.items().stream()).filter(i -> i.key().equals(key)).findFirst().orElse(null);
    }

    private static List<StatusDock.Group> build(Snapshot s, TraderSnapshot t, String connection, boolean admin, boolean capture) {
        return build(s, t, connection, admin, capture, ScientificCapture.unknown("test"));
    }

    private static List<StatusDock.Group> build(Snapshot s, TraderSnapshot t, String connection, boolean admin, boolean capture, ScientificCapture sci) {
        return DockModel.build(s, t, ByxSnapshot.unknown("cosmos", "LOCALNET", connection, "x"), "Wallet not linked", admin, capture, sci);
    }

    private static ScientificCapture sci(ScientificCapture.Status status, String reason) {
        return new ScientificCapture(status, reason, "campaign-x", "hash", "session-x", java.time.Duration.ofSeconds(3), 1L, 2L,
                ScientificCapture.Admission.UNKNOWN, null, java.time.Instant.now());
    }

    private static CaptureInfo capture(String recorder) {
        return new CaptureInfo(recorder, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void groupsMatchTheHandoff() {
        var m = build(new Snapshot(), new TraderSnapshot(), "UNKNOWN", false, false);
        assertEquals(List.of("SYSTEM HEALTH", "MODE", "ENVIRONMENT"), m.stream().map(StatusDock.Group::label).toList());
    }

    @Test
    void unreadableIsUnknownNeverOperational() {
        TraderSnapshot t = new TraderSnapshot();
        t.feed = null;
        Snapshot s = new Snapshot(); // capture.recorder() == null
        var m = build(s, t, "UNKNOWN", false, false);
        assertEquals(StatusState.UNKNOWN, item(m, "feed").dot());
        assertEquals(StatusState.UNKNOWN, item(m, "capture").dot());
        assertEquals(StatusState.UNKNOWN, item(m, "network").dot());
    }

    @Test
    void nothingConfiguredIsNeutralNotADrop() {
        TraderSnapshot t = new TraderSnapshot();
        t.feed = "NOT_CONFIGURED";
        Snapshot s = new Snapshot();
        var m = build(s, t, "UNKNOWN", false, false);
        assertEquals(StatusState.UNAVAILABLE, item(m, "feed").dot());
        assertTrue(item(m, "feed").expected(), "no feed in this build: neutral chip and grey dot");
        assertEquals(StatusState.UNKNOWN, item(m, "capture").dot(), "not observed is UNKNOWN, never a drop");
    }

    @Test
    void realDropIsRed() {
        Snapshot s = new Snapshot();
        s.backendOnline = false;
        var m = build(s, new TraderSnapshot(), "OFFLINE", false, false);
        assertEquals(StatusState.UNAVAILABLE, item(m, "backend").dot());
        assertFalse(item(m, "backend").expected());
        assertEquals(StatusState.UNAVAILABLE, item(m, "network").dot());
        assertFalse(item(m, "network").expected());
    }

    @Test
    void onlineStatesNeedEvidence() {
        Snapshot s = new Snapshot();
        s.backendOnline = true;
        TraderSnapshot t = new TraderSnapshot();
        t.feed = "LIVE";
        var m = build(s, t, "ONLINE", false, false, sci(ScientificCapture.Status.RUNNING, null));
        assertEquals(StatusState.OPERATIONAL, item(m, "backend").dot());
        assertEquals(StatusState.OPERATIONAL, item(m, "feed").dot());
        assertEquals(StatusState.OPERATIONAL, item(m, "capture").dot());
        assertEquals(StatusState.OPERATIONAL, item(m, "network").dot());
    }

    @Test
    void liveTradingOffIsPrimaryTextNeverRed() {
        var live = item(build(new Snapshot(), new TraderSnapshot(), "UNKNOWN", false, false), "live");
        assertEquals("Live trading OFF", live.text());
        assertEquals("primary", live.tone());
        assertNull(live.dot(), "no status dot (never red)");
    }

    @Test
    void linksOnlyToRealScreensTheSessionCanOpen() {
        var trader = build(new Snapshot(), new TraderSnapshot(), "UNKNOWN", false, false);
        assertEquals("sys-status", item(trader, "capture").target(), "Capture needs admin: a trader gets the System Status detail");
        assertEquals("sys-status", item(trader, "backend").target(), "Backend links to System Status");
        assertNull(item(trader, "auth"), "no Admin session item without an admin session");
        var admin = build(new Snapshot(), new TraderSnapshot(), "UNKNOWN", true, true);
        assertEquals("capture", item(admin, "capture").target());
        assertEquals("t-byx", item(admin, "network").target());
        assertEquals("t-wallet", item(admin, "wallet").target());
        assertEquals("Admin session", item(admin, "auth").text());
    }

    @Test
    void scientificCaptureMapsToTheDocumentedColours() {
        var s = new Snapshot();
        var t = new TraderSnapshot();
        assertEquals(StatusState.OPERATIONAL, item(build(s, t, "UNKNOWN", false, false, sci(ScientificCapture.Status.RUNNING, null)), "capture").dot());
        var degraded = item(build(s, t, "UNKNOWN", false, false, sci(ScientificCapture.Status.DEGRADED, "writer silent 45 s")), "capture");
        assertEquals(StatusState.DEGRADED, degraded.dot());
        assertTrue(degraded.hint().contains("writer silent 45 s") && degraded.hint().contains("campaign-x"), "the hint carries campaign and reason");
        assertEquals(StatusState.UNKNOWN, item(build(s, t, "UNKNOWN", false, false, sci(ScientificCapture.Status.UNKNOWN, "x")), "capture").dot());
        var stopped = item(build(s, t, "UNKNOWN", false, false, sci(ScientificCapture.Status.STOPPED, "gone")), "capture");
        assertEquals(StatusState.UNAVAILABLE, stopped.dot());
        assertFalse(stopped.expected(), "a stopped scientific recorder is a real drop (red), not a neutral chip");
        assertEquals("Sci. capture", stopped.text(), "short label that fits the 1440 dock; the hint spells out Scientific capture");
        assertTrue(stopped.hint().startsWith("Scientific capture:"));
    }
}
