package panel.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Monta comandos do adaptive-trader existente; só TRAIN, só whitelist. */
public class AdaptiveTraderCli implements CommandAdapter {
    private static final Pattern SESSION_ID = Pattern.compile("microstructure-\\d{8}T\\d{6}Z-[a-z_]+");

    private final Supplier<String> cliPath;

    public AdaptiveTraderCli(Supplier<String> cliPath) {
        this.cliPath = cliPath;
    }

    @Override
    public boolean available() {
        return Files.isExecutable(Path.of(cliPath.get()));
    }

    @Override
    public List<String> build(CommandSpec spec, String sessionId) {
        List<String> cmd = new ArrayList<>(List.of(cliPath.get(), "research", "microstructure", spec.subcommand));
        if (spec.needsSession) {
            if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
                throw new IllegalArgumentException("Sessão inválida: " + sessionId);
            }
            cmd.add("--session");
            cmd.add(sessionId);
        }
        for (String arg : cmd) {
            String u = arg.toUpperCase(Locale.ROOT);
            if (u.contains("VALIDATION") || u.contains("FINAL_HOLDOUT")) {
                throw new IllegalArgumentException("Somente TRAIN é permitido nesta fase.");
            }
        }
        return cmd;
    }
}
