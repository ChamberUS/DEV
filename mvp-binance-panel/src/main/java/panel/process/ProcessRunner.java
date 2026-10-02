package panel.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Executa um processo capturando stdout/stderr linha a linha. Sempre chamado fora da thread da UI. */
public class ProcessRunner {
    private volatile Process process;

    public int run(List<String> cmd, Path workdir, Consumer<String> stdout, Consumer<String> stderr) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(workdir.toFile()).start();
        process = p;
        Thread err = new Thread(() -> pump(p.getErrorStream(), stderr), "proc-stderr");
        err.setDaemon(true);
        err.start();
        pump(p.getInputStream(), stdout);
        int code = p.waitFor();
        err.join(2000);
        return code;
    }

    public String capture(List<String> cmd, Path workdir, int timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(workdir.toFile()).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (var in = p.getInputStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    if (output.size() + count > 2_000_000) { overflow.set(true); p.destroyForcibly(); break; }
                    output.write(bytes, 0, count);
                }
            } catch (IOException ignored) { }
        }, "status-stdout");
        Thread errors = new Thread(() -> pump(p.getErrorStream(), line -> { }), "status-stderr");
        reader.setDaemon(true);
        errors.setDaemon(true);
        reader.start(); errors.start();
        try {
            if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("Status request timed out");
            }
            reader.join(1000);
            if (p.exitValue() != 0 || reader.isAlive() || overflow.get()) throw new IOException("Status request failed");
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            p.destroyForcibly();
            p.getInputStream().close();
            p.getErrorStream().close();
        }
    }

    private static void pump(java.io.InputStream in, Consumer<String> sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.accept(line);
            }
        } catch (IOException ignored) {
            // stream fechado ao cancelar
        }
    }

    public void cancel() {
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }
}
