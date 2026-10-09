package panel.accountview;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javafx.application.Platform;

/** Cancellable presentation reads. Hidden/disposed views never consume an old result. */
final class AccountRead implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "account-read");
        t.setDaemon(true);
        return t;
    });
    private Future<?> pending;
    private long generation;
    private boolean closed;

    <T> void load(Supplier<T> read, BiConsumer<T, RuntimeException> display) {
        cancel();
        if (closed) return;
        long ticket = generation;
        pending = worker.submit(() -> {
            T value = null;
            RuntimeException error = null;
            try { value = read.get(); } catch (RuntimeException e) { error = e; }
            T result = value;
            RuntimeException failure = error;
            Platform.runLater(() -> {
                if (!closed && ticket == generation) display.accept(result, failure);
            });
        });
    }

    void cancel() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    @Override public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
