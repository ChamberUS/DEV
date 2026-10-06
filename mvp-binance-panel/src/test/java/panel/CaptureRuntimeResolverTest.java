package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import panel.adapter.*;
import panel.adapter.CaptureRuntimeResolver.Activity;
import panel.adapter.CaptureRuntimeResolver.Process;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;
import panel.motion.MotionService;
import panel.researchview.CapturePanel;
import panel.service.CaptureMonitorService;

class CaptureRuntimeResolverTest {
    @TempDir Path temp;
    static final Instant NOW = Instant.parse("2026-10-06T03:30:00Z");
    static final String CAMPAIGN = "ethusdt-futures-continuous-20261002T204051Z";
    Path state, root, runtime, checkout;
    final CaptureMonitorTest.FakeClock clock = new CaptureMonitorTest.FakeClock();
    final class Resolver extends CaptureRuntimeResolver {
        volatile List<Process> observations = List.of();
        volatile Activity activity = new Activity("microstructure-20261006T030000Z-usd_m_futures", NOW.minusSeconds(1));
        Resolver() { super(temp.resolve("project/.venv/bin/adaptive-trader")); }
        @Override protected List<Process> processes() { return observations; }
        @Override protected Activity activity(long pid, Path root) { return activity; }
    }
    Resolver resolver;
    LocalCaptureProcessProbe probe;
    @BeforeEach void setup() throws Exception {
        state = Files.createDirectories(temp.resolve("state"));
        root = Files.createDirectories(temp.resolve("project/data/microstructure"));
        runtime = Files.createDirectories(state.resolve("runtime/current"));
        checkout = temp.resolve("project-capture-current");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts/continuous_capture.sh"), "synthetic launcher");
        Path activation = Files.createDirectories(state.resolve("recovery/current")).resolve("activation.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(activation.toFile(), Map.of(
                "checkout", checkout.toString(), "runtime", runtime.toString(), "campaign_id", CAMPAIGN));
        Files.writeString(state.resolve("current_campaign"), CAMPAIGN);
        Files.writeString(state.resolve("capture.pid"), "5179");
        clock.now = NOW; resolver = new Resolver();
        var source = new LocalCaptureProcessProbe.Source() {
            public String text(Path p) { throw new AssertionError("No legacy resolver"); }
            public LocalCaptureProcessProbe.ProcessInfo process(long p, Path s, String c) { throw new AssertionError("No legacy resolver"); }
            public LocalCaptureProcessProbe.Storage storage(Path p) {
                return new LocalCaptureProcessProbe.Storage(1024L, 4096L, 8192L, NOW.minusSeconds(3600), null);
            }
            public CaptureRuntimeResolver.Runtime runtime(Path s, Path r, Instant n) throws java.io.IOException {
                return resolver.read(s, r, n);
            }
        };
        probe = new LocalCaptureProcessProbe(state, root, clock, source);
        running(5179, 5186, checkout, runtime);
    }
    void running(long supervisor, long collector, Path checkout, Path runtime) {
        resolver.observations = List.of(
            new Process(supervisor, 1L, NOW.minusSeconds(600), "/bin/bash",
                    List.of(checkout.resolve("scripts/continuous_capture.sh").toString())),
            new Process(collector, supervisor, NOW.minusSeconds(599), "/usr/bin/python3", List.of(
                    runtime.resolve("bin/adaptive-trader").toString(), "market", "microstructure", "campaign-record",
                    "--campaign-id", CAMPAIGN, "--market", "futures", "--symbol", "ETHUSDT", "--output-dir", root.toString())));
    }
    @Test void staleLegacyPidDoesNotHideRegisteredRuntime() throws Exception {
        Files.writeString(state.resolve("capture.pid"), "7");
        var observations = new ArrayList<>(resolver.observations);
        observations.add(new Process(7, 1L, NOW, "/usr/bin/sleep", List.of("600")));
        resolver.observations = observations;
        var snapshot = probe.read();
        assertEquals(State.RUNNING, snapshot.state()); assertEquals(5179L, snapshot.pid());
        assertEquals(5186L, snapshot.collectorPid()); assertNotNull(snapshot.sessionId());
        assertTrue(snapshot.warnings().stream().anyMatch(w -> w.contains("Obsolete registered PID")));
    }
    @Test void activeWriterOverridesCachedStorageTimestamp() {
        var snapshot = probe.read();
        assertEquals(State.RUNNING, snapshot.state());
        assertEquals(NOW.minusSeconds(1), snapshot.lastUpdate()); assertNull(snapshot.lastEvent());
        assertEquals(Duration.ofSeconds(600), snapshot.continuousElapsed(NOW));
    }
    @Test void actuallyStaleWriterAndBoundary() {
        resolver.activity = new Activity("session", NOW.minus(CaptureRuntimeResolver.STALE_AFTER));
        assertEquals(State.RUNNING, probe.read().state());
        resolver.activity = new Activity("session", NOW.minus(CaptureRuntimeResolver.STALE_AFTER).minusSeconds(1));
        assertEquals(State.STALE, probe.read().state());
    }
    @Test void stoppedRuntimeEvenWithDeadRegisteredPid() {
        resolver.observations = List.of();
        var snapshot = probe.read();
        assertEquals(State.STOPPED, snapshot.state()); assertFalse(snapshot.processAlive()); assertNull(snapshot.sessionId());
    }
    @Test void pidReuseByDifferentProcessIsNotRunning() {
        resolver.observations = List.of(new Process(5179, 1L, NOW, "/usr/bin/sleep", List.of("600")));
        var snapshot = probe.read();
        assertEquals(State.STOPPED, snapshot.state());
        assertTrue(snapshot.warnings().stream().anyMatch(w -> w.contains("different process")));
    }
    @Test void missingWriterAndAmbiguousCollectorsAreUnknown() {
        resolver.activity = new Activity(null, null);
        assertEquals(State.UNKNOWN, probe.read().state());
        var observations = new ArrayList<>(resolver.observations);
        Process original = observations.getLast();
        observations.add(new Process(6000, original.parent(), original.start(), original.command(), original.args()));
        resolver.observations = observations;
        assertEquals(State.UNKNOWN, probe.read().state());
    }
    @Test void campaignOutputAndParentIdentityMustMatch() {
        Process collector = resolver.observations.getLast();
        var wrong = new ArrayList<>(collector.args());
        wrong.set(wrong.indexOf("--campaign-id") + 1, "other-campaign");
        resolver.observations = List.of(new Process(5186, 5179L, NOW, collector.command(), wrong));
        assertNotEquals(State.RUNNING, probe.read().state());
        running(5179, 5186, checkout, runtime);
        resolver.observations = List.of(new Process(5179, 1L, NOW, "/usr/bin/sleep", List.of("600")), collector);
        assertNotEquals(State.RUNNING, probe.read().state());
    }
    @Test void handoffPublishesIntoSameJavafxPanelWithoutRestart() throws Exception {
        FxSupport.start();
        var card = FxSupport.fx(() -> new CapturePanel(new MotionService(), clock));
        FxSupport.fx(() -> new Scene(card));
        var first = new CountDownLatch(1); var second = new CountDownLatch(1);
        var firstPublished = new AtomicBoolean(); var last = new AtomicReference<CaptureSnapshot>();
        var service = new CaptureMonitorService(probe, () -> {});
        try {
            FxSupport.fx(() -> service.start(s -> {
                card.show(s); last.set(s);
                if (firstPublished.compareAndSet(false, true)) first.countDown();
                if (Objects.equals(s.pid(), 10657L)) second.countDown();
            }));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            List<Node> nodes = FxSupport.fx(() -> children(card));
            Path nextCheckout = temp.resolve("project-capture-next");
            Files.createDirectories(nextCheckout.resolve("scripts"));
            Files.writeString(nextCheckout.resolve("scripts/continuous_capture.sh"), "synthetic handoff");
            Path nextRuntime = Files.createDirectories(state.resolve("runtime/next"));
            Files.writeString(state.resolve("capture.pid"), "10657");
            resolver.activity = new Activity("microstructure-20261006T033000Z-usd_m_futures", NOW);
            running(10657, 10658, nextCheckout, nextRuntime);
            FxSupport.fx(service::refresh);
            assertTrue(second.await(5, TimeUnit.SECONDS));
            FxSupport.fx(() -> {
                assertEquals(nodes, children(card)); assertEquals(State.RUNNING, card.snapshot().state());
                assertEquals(10657L, card.snapshot().pid()); assertEquals(NOW, card.snapshot().lastUpdate());
                assertTrue(children(card).stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(l -> l.getText().contains("microstructure-20261006T033000Z")));
            });
        } finally { FxSupport.fx(service::close); }
    }
    static List<Node> children(Parent parent) {
        var result = new ArrayList<Node>();
        for (Node n : parent.getChildrenUnmodifiable()) {
            result.add(n); if (n instanceof Parent p) result.addAll(children(p));
        }
        return result;
    }
}
