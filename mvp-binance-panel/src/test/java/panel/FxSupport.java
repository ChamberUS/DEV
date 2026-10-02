package panel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;

/** Inicia o toolkit JavaFX uma vez e roda blocos na thread FX. Aborta o teste se não houver display. */
final class FxSupport {
    private static boolean started;

    private FxSupport() {
    }

    static synchronized void start() {
        if (started) {
            return;
        }
        try {
            CountDownLatch l = new CountDownLatch(1);
            Platform.startup(l::countDown);
            Assumptions.assumeTrue(l.await(10, TimeUnit.SECONDS), "JavaFX toolkit unavailable");
        } catch (IllegalStateException e) {
            // já iniciado
        } catch (Throwable e) {
            Assumptions.abort("JavaFX toolkit unavailable: " + e);
        }
        Platform.setImplicitExit(false);
        started = true;
    }

    static <T> T fx(java.util.function.Supplier<T> s) throws Exception {
        start();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(s.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                l.countDown();
            }
        });
        if (!l.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread timeout");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return out.get();
    }

    static void fx(Runnable r) throws Exception {
        fx(() -> {
            r.run();
            return null;
        });
    }
}
