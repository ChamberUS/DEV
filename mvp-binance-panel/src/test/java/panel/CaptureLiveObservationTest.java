package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import panel.adapter.LocalCaptureProcessProbe;
import panel.model.CaptureSnapshot.State;
import panel.motion.MotionService;
import panel.researchview.CapturePanel;

/** Opt-in, metadata-only observation; never starts or stops capture or the running app. */
@EnabledIfSystemProperty(named = "capture.live", matches = "true")
class CaptureLiveObservationTest {
    @Test void actualRuntimePublishesToJavafxPanel() throws Exception {
        Path project = Path.of("/Users/buynnex-corp/dev/mvp-binance");
        var probe = new LocalCaptureProcessProbe(Path.of(System.getProperty("user.home"), ".mvp-binance-capture"),
                project.resolve("data/microstructure"), project.resolve(".venv/bin/adaptive-trader"));
        var snapshot = probe.read();
        assertEquals(State.RUNNING, snapshot.state()); assertTrue(snapshot.processAlive());
        assertNotNull(snapshot.collectorPid()); assertNotNull(snapshot.sessionId()); assertNotNull(snapshot.lastUpdate());
        assertNull(snapshot.lastEvent());
        FxSupport.fx(() -> {
            var card = new CapturePanel(new MotionService(), Clock.systemUTC());
            new Scene(card);
            card.show(snapshot);
            assertSame(snapshot, card.snapshot());
            assertTrue(CaptureRuntimeResolverTest.children(card).stream()
                    .filter(javafx.scene.control.Label.class::isInstance).map(javafx.scene.control.Label.class::cast)
                    .anyMatch(l -> l.getText().equals(snapshot.sessionId())));
        });
        System.out.println("LIVE CAPTURE: state=" + snapshot.state() + " supervisor=" + snapshot.pid()
                + " collector=" + snapshot.collectorPid() + " session=" + snapshot.sessionId()
                + " lastWrite=" + snapshot.lastUpdate() + " capturedBytes=" + snapshot.capturedBytes()
                + " warnings=" + snapshot.warnings());
    }
}
