package panel.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import panel.model.ScientificCapture;

/**
 * Lê o estado da captura científica FORA da thread FX, a cada {@link #INTERVAL}, com limite de tempo estrito, e guarda o último
 * resultado para a UI ler sem bloquear ({@link #current()}). Falha, travamento ou leitura lenta viram UNKNOWN; nada aqui toca na
 * thread FX, no login nem na captura (somente leitura). Não é admin-gated: expõe só o resumo que o dock mostra.
 */
public final class ScientificCaptureService implements AutoCloseable {
    public static final Duration INTERVAL = Duration.ofSeconds(10);
    public static final Duration TIMEOUT = Duration.ofSeconds(6);

    private final Function<java.time.Instant, ScientificCapture> source;
    private final Clock clock;
    private final Duration interval;
    private final Duration timeout;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "scientific-capture"));
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> daemon(r, "scientific-capture-read"));
    private volatile ScientificCapture current;
    private ScheduledFuture<?> task;
    private Future<ScientificCapture> inFlight;
    private int reads;

    public ScientificCaptureService(Function<java.time.Instant, ScientificCapture> source, Clock clock) {
        this(source, clock, INTERVAL, TIMEOUT);
    }

    public ScientificCaptureService(Function<java.time.Instant, ScientificCapture> source, Clock clock, Duration interval, Duration timeout) {
        this.source = source;
        this.clock = clock;
        this.interval = interval;
        this.timeout = timeout;
        this.current = ScientificCapture.unknown("not observed yet", clock.instant());
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    }

    /** Último resultado conhecido; nunca bloqueia. */
    public ScientificCapture current() {
        return current;
    }

    public synchronized void start() {
        if (task != null) {
            return;
        }
        task = scheduler.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        current = ScientificCapture.unknown("not observed yet", clock.instant());
    }

    /** Número de leituras iniciadas (teste/diagnóstico de custo). */
    public synchronized int reads() {
        return reads;
    }

    void poll() {
        Future<ScientificCapture> pending;
        synchronized (this) {
            if (task == null) {
                return;
            }
            if (inFlight != null && !inFlight.isDone()) {
                current = ScientificCapture.unknown("previous status read still running", clock.instant());
                return; // não empilha leituras sobre uma leitura travada
            }
            reads++;
            pending = reader.submit(() -> source.apply(clock.instant()));
            inFlight = pending;
        }
        ScientificCapture result;
        try {
            result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            result = ScientificCapture.unknown("status read timed out", clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            result = ScientificCapture.unknown("status read failed (" + e.getClass().getSimpleName() + ")", clock.instant());
        }
        synchronized (this) {
            if (task != null) { // stop() durante a leitura descarta o resultado
                current = result == null ? ScientificCapture.unknown("no result", clock.instant()) : result;
            }
        }
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
        reader.shutdownNow();
    }
}
