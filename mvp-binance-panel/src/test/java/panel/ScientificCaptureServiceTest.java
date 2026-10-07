package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import panel.model.ScientificCapture;
import panel.model.ScientificCapture.Status;
import panel.service.ScientificCaptureService;

/** A leitura é periódica, fora da FX, com limite de tempo; travar, falhar ou ser lenta vira UNKNOWN e nunca bloqueia ninguém. */
class ScientificCaptureServiceTest {
    private static ScientificCapture running() {
        return new ScientificCapture(Status.RUNNING, null, "c", "h", "s", Duration.ofSeconds(1), 1L, 2L, ScientificCapture.Admission.UNKNOWN, null, java.time.Instant.now());
    }

    private static boolean await(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        return c.getAsBoolean();
    }

    @Test
    void startsUnknownReadsOffTheFxThreadAndPublishesTheResult() throws Exception {
        AtomicBoolean onFx = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        ScientificCaptureService s = new ScientificCaptureService(now -> {
            reads.incrementAndGet();
            try {
                if (Platform.isFxApplicationThread()) {
                    onFx.set(true);
                }
            } catch (IllegalStateException noToolkit) {
                // sem toolkit: com certeza não é a FX
            }
            return running();
        }, Clock.systemUTC());
        assertEquals(Status.UNKNOWN, s.current().status(), "before the first read: UNKNOWN, never STOPPED");
        s.start();
        assertTrue(await(() -> s.current().status() == Status.RUNNING));
        assertFalse(onFx.get(), "the status read never runs on the FX thread");
        s.close();
    }

    @Test
    void slowReaderTimesOutToUnknownAndNeverStacksReads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        ScientificCaptureService s = new ScientificCaptureService(now -> {
            started.incrementAndGet();
            while (release.getCount() > 0) { // travado de verdade: ignora interrupção (como um subprocesso preso)
                try {
                    release.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // continua preso
                }
            }
            return running();
        }, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofMillis(200));
        s.start();
        assertTrue(await(() -> s.current().reason() != null && s.current().reason().contains("timed out")), "slow read -> UNKNOWN (timed out)");
        assertEquals(Status.UNKNOWN, s.current().status());
        Thread.sleep(600);
        assertEquals(1, started.get(), "no new read is stacked on top of a stuck one");
        release.countDown();
        s.close();
    }

    @Test
    void failingReaderIsUnknownAndRecovers() throws Exception {
        AtomicInteger n = new AtomicInteger();
        ScientificCaptureService s = new ScientificCaptureService(now -> {
            if (n.incrementAndGet() == 1) {
                throw new IllegalStateException("resolver unavailable");
            }
            return running();
        }, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofSeconds(2));
        s.start();
        assertTrue(await(() -> n.get() >= 1));
        assertTrue(await(() -> s.current().status() == Status.RUNNING), "next poll recovers after a failure");
        s.close();
    }

    @Test
    void stopDiscardsTheLastValueAndStopsReading() throws Exception {
        AtomicInteger n = new AtomicInteger();
        ScientificCaptureService s = new ScientificCaptureService(now -> { n.incrementAndGet(); return running(); }, Clock.systemUTC(),
                Duration.ofMillis(100), Duration.ofSeconds(2));
        s.start();
        assertTrue(await(() -> s.current().status() == Status.RUNNING));
        s.stop();
        assertEquals(Status.UNKNOWN, s.current().status(), "after logout nothing stale is shown");
        int at = n.get();
        Thread.sleep(400);
        assertEquals(at, n.get(), "stopped: no reads");
        s.close();
    }

    @Test
    void intervalIsTensOfSecondsNotPerFrame() {
        assertTrue(ScientificCaptureService.INTERVAL.compareTo(Duration.ofSeconds(5)) >= 0);
        assertTrue(ScientificCaptureService.TIMEOUT.compareTo(ScientificCaptureService.INTERVAL) < 0);
    }
}
