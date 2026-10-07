package panel.app;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

/**
 * Medição de inicialização, desligada por padrão: só escreve se a variável de ambiente {@code BYX_STARTUP_TRACE} apontar para um arquivo.
 * Registra apenas rótulos e milissegundos desde o início da JVM (nenhum caminho, dado de usuário ou segredo) e, opcionalmente, o maior atraso
 * observado na thread FX. Não altera comportamento; sem a variável cada chamada é uma comparação.
 */
public final class StartupTrace {
    private static final Path FILE = target();
    private static final long JVM_START_MS = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static volatile long maxStallMs;

    private StartupTrace() {
    }

    private static Path target() {
        String v = System.getenv("BYX_STARTUP_TRACE");
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    public static boolean enabled() {
        return FILE != null;
    }

    public static void mark(String label) {
        if (FILE == null) {
            return;
        }
        write(String.format(java.util.Locale.ROOT, "%6d ms  %s%n", System.currentTimeMillis() - JVM_START_MS, label));
    }

    /** Mede uma etapa de construção; sem trace, apenas executa. */
    public static <T> T time(String label, Supplier<T> step) {
        if (FILE == null) {
            return step.get();
        }
        long t0 = System.nanoTime();
        try {
            return step.get();
        } finally {
            write(String.format(java.util.Locale.ROOT, "%6d ms  %-34s took %5d ms  [%s]%n", System.currentTimeMillis() - JVM_START_MS, label,
                    (System.nanoTime() - t0) / 1_000_000, Thread.currentThread().getName()));
        }
    }

    /** Pulso de 50 ms na thread FX: guarda o maior atraso entre o agendado e o executado. */
    public static void heartbeat() {
        if (FILE == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    long due = System.nanoTime();
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    javafx.application.Platform.runLater(done::countDown);
                    done.await();
                    long stall = (System.nanoTime() - due) / 1_000_000;
                    if (stall > maxStallMs) {
                        maxStallMs = stall;
                    }
                    if (stall > 250) {
                        write(String.format(java.util.Locale.ROOT, "%6d ms  FX_STALL %d ms%n", System.currentTimeMillis() - JVM_START_MS, stall));
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException | IllegalStateException e) {
                Thread.currentThread().interrupt();
            }
        }, "startup-trace-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void write(String line) {
        try {
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // medição nunca interfere no app
        }
    }
}
