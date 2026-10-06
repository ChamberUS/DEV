package panel.localservice;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sondagem periódica do serviço local, fora da thread FX. Só escreve um instantâneo imutável: não navega, não concede permissão
 * e não toca a captura. Cada start/stop abre uma nova GERAÇÃO e um resultado de uma geração antiga é descartado (logout, troca de
 * usuário e reinício não recebem resposta atrasada). Parar = zero tarefas agendadas.
 */
public final class LocalServiceMonitor implements AutoCloseable {
    public static final long PERIOD_SECONDS = 10;

    /** Fonte da leitura (o cliente real; testes usam uma controlável). */
    @FunctionalInterface
    public interface Probe {
        LocalServiceStatus probe(boolean everConnected);
    }

    private final Probe client;
    private final Consumer<LocalServiceStatus> onResult;
    private volatile LocalServiceStatus snapshot = LocalServiceStatus.unknown();
    private ScheduledExecutorService scheduler;
    private long generation;
    private boolean everConnected;
    private boolean polling;

    public LocalServiceMonitor(LocalServiceClient client, Consumer<LocalServiceStatus> onResult) {
        this(client::probe, onResult);
    }

    public LocalServiceMonitor(Probe client, Consumer<LocalServiceStatus> onResult) {
        this.client = client;
        this.onResult = onResult == null ? s -> { } : onResult;
    }

    public LocalServiceStatus snapshot() {
        return snapshot;
    }

    public synchronized boolean running() {
        return scheduler != null;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        generation++;
        snapshot = LocalServiceStatus.unknown();
        everConnected = false;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-service-monitor");
            t.setDaemon(true);
            return t;
        });
        long g = generation;
        scheduler.scheduleWithFixedDelay(() -> poll(g), 0, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Sondagem imediata (Retry real). Ignorada se já há uma em curso. */
    public synchronized void refreshNow() {
        if (scheduler == null || polling) {
            return;
        }
        long g = generation;
        scheduler.execute(() -> poll(g));
    }

    private void poll(long g) {
        boolean ever;
        synchronized (this) {
            if (g != generation || scheduler == null) {
                return;
            }
            polling = true;
            ever = everConnected;
        }
        LocalServiceStatus result;
        try {
            result = client.probe(ever);
        } finally {
            synchronized (this) {
                polling = false;
            }
        }
        synchronized (this) {
            if (g != generation || scheduler == null) {
                return; // parou ou reiniciou enquanto a leitura estava em voo: resultado velho não vira estado
            }
            everConnected |= result.connected();
            snapshot = result;
        }
        onResult.accept(result);
    }

    public synchronized void stop() {
        generation++;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        snapshot = LocalServiceStatus.unknown();
        everConnected = false;
    }

    @Override
    public void close() {
        stop();
    }
}
