package panel.app;

/**
 * Pré-aquecimento paralelo à subida do JavaFX. O toolkit leva 1-3 s para ficar de pé e a nossa thread ficaria parada; neste tempo uma
 * thread de baixa prioridade só CARREGA classes/bibliotecas nativas que a thread FX vai precisar logo depois, para que a construção do
 * contexto e da tela de entrada não pague esse custo (SQLite nativo, detecção da identidade de assinatura do próprio processo).
 * <p>
 * Não abre rede, não lê nem escreve estado do usuário, não toca no serviço local, na autoridade nem em credenciais: cada passo é uma
 * inicialização de classe idempotente e thread-safe (a FX apenas espera se chegar antes). Falha em qualquer passo é ignorada: o uso
 * real, mais tarde, falha ou funciona exatamente como antes.
 */
public final class StartupWarmup {
    private StartupWarmup() {
    }

    public static void start() {
        Thread t = new Thread(StartupWarmup::run, "startup-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    static void run() {
        step("sqlite-native", () -> {
            Class.forName("org.sqlite.JDBC");
            org.sqlite.SQLiteJDBCLoader.initialize();
        });
        step("signing-identity", () -> new panel.localservice.LocalServiceClient(panel.localservice.LocalServiceClient.defaultHome()).identityMode());
    }

    private interface Step {
        void run() throws Exception;
    }

    private static void step(String label, Step step) {
        long t0 = System.nanoTime();
        try {
            step.run();
        } catch (Exception | LinkageError ignored) {
            // o uso real decide
        }
        StartupTrace.mark("warmup " + label + " " + (System.nanoTime() - t0) / 1_000_000 + " ms [" + Thread.currentThread().getName() + "]");
    }
}
