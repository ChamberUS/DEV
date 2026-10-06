package panel.localservice;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inicia o serviço local EMPACOTADO quando ele não está rodando (a autenticação depende dele; não há alternativa local). SÓ o helper do PRÓPRIO bundle:
 * o caminho é derivado do executável deste processo ({@code <bundle>/Contents/MacOS/<app>} → {@code <bundle>/Contents/Helpers/byx-local-service.app/…}) e conferido
 * (arquivo regular, executável, dentro do mesmo bundle, sem symlink); nada vem de configuração, ambiente, argumento ou IPC. Fora do bundle (IDE/mvn) não inicia nada.
 * Uma trava de arquivo evita dois lançadores simultâneos; o filho é encerrado com o painel só se FOI este processo que o iniciou (um serviço já existente é reaproveitado).
 * stdout é descartado e stderr (eventos fixos, sem segredo) vai para {@code service.log} 0600 no home do serviço.
 */
public final class ServiceLauncher {
    private static final AtomicReference<Process> STARTED = new AtomicReference<>();
    private final Path home;
    private final Path executable;

    public ServiceLauncher(Path home, Path ownExecutable) {
        this.home = home;
        this.executable = ownExecutable;
    }

    /** Helper do próprio bundle ou null (não empacotado ou fora do formato esperado). */
    static Path helperOf(Path ownExecutable) {
        if (ownExecutable == null) {
            return null;
        }
        try {
            Path real = ownExecutable.toRealPath();
            Path macos = real.getParent();
            if (macos == null || !"MacOS".equals(macos.getFileName().toString()) || macos.getParent() == null || !"Contents".equals(macos.getParent().getFileName().toString())) {
                return null;
            }
            Path contents = macos.getParent();
            Path helper = contents.resolve("Helpers").resolve("byx-local-service.app").resolve("Contents").resolve("MacOS").resolve("byx-local-service");
            if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(helper) || !helper.toRealPath().startsWith(contents)) {
                return null;
            }
            return helper;
        } catch (IOException e) {
            return null;
        }
    }

    public static Path currentExecutable() {
        return ProcessHandle.current().info().command().map(Path::of).orElse(null);
    }

    public boolean available() {
        return helperOf(executable) != null;
    }

    /** Garante que o serviço atende (inicia se preciso) em até {@code wait}. Devolve se ele respondeu ao handshake de pareamento. */
    public boolean ensureRunning(Duration wait) {
        LocalServiceClient probe = new LocalServiceClient(home);
        if (reachable(probe)) {
            return true;
        }
        Path helper = helperOf(executable);
        if (helper == null) {
            return false;
        }
        long end = System.nanoTime() + wait.toNanos();
        try {
            Files.createDirectories(home, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (FileChannel ch = FileChannel.open(home.resolve("launch.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = ch.tryLock();
                if (lock != null) { // quem tem a trava decide; outro já está iniciando se não a obtivermos
                    try {
                        Process alive = STARTED.get();
                        if (!reachable(probe) && (alive == null || !alive.isAlive())) {
                            spawn(helper);
                        }
                        waitReachable(probe, end);
                    } finally {
                        lock.release();
                    }
                    return reachable(probe);
                }
            }
            waitReachable(probe, end);
            return reachable(probe);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void spawn(Path helper) throws IOException {
        Path log = home.resolve("service.log");
        if (Files.exists(log) && Files.size(log) > 1_048_576) {
            Files.delete(log); // só eventos fixos; o arquivo não cresce sem limite
        }
        if (!Files.exists(log)) {
            Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        ProcessBuilder pb = new ProcessBuilder(helper.toString());
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process p = pb.start();
        STARTED.set(p);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process s = STARTED.get();
            if (s != null && s.isAlive()) {
                s.destroy(); // só o que ESTE processo iniciou
            }
        }, "service-launcher-shutdown"));
    }

    private static boolean reachable(LocalServiceClient c) {
        LocalServiceStatus s = c.once(false);
        return s.state() == LocalServiceStatus.State.CONNECTED || s.state() == LocalServiceStatus.State.AUTH_FAILED || s.state() == LocalServiceStatus.State.INCOMPATIBLE;
    }

    private static void waitReachable(LocalServiceClient c, long endNanos) {
        while (System.nanoTime() < endNanos && !reachable(c)) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
