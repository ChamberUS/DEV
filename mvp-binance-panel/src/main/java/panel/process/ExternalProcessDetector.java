package panel.process;

import java.util.List;

/** Detecta execuções do adaptive-trader iniciadas fora do painel (somente leitura). */
public final class ExternalProcessDetector {
    public record External(long pid, String command) {
    }

    private ExternalProcessDetector() {
    }

    public static List<External> find() {
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != ProcessHandle.current().pid())
                .map(p -> new External(p.pid(), p.info().commandLine().orElse("")))
                .filter(e -> e.command().contains("adaptive-trader") && e.command().contains("research microstructure"))
                .toList();
    }
}
