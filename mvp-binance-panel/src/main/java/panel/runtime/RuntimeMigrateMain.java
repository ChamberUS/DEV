package panel.runtime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * CLI do migrador de runtime (independente do byx-migrate). {@code plan --source F} e {@code verify --source F --target G} só leem; {@code prepare --source F --target G}
 * exige digitar a frase e recusa alvo existente. Nenhum padrão de caminho: os dois caminhos são SEMPRE explícitos. Nada é impresso além de hashes, contagens e nomes.
 */
public final class RuntimeMigrateMain {
    private RuntimeMigrateMain() { }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, new BufferedReader(new InputStreamReader(System.in))));
    }

    static int run(String[] args, BufferedReader in) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: plan --source F | prepare --source F --target G | verify --source F --target G");
            return 2;
        }
        Path source = option(args, "--source");
        Path target = option(args, "--target");
        try {
            switch (args[0]) {
                case "plan" -> System.out.println(RuntimeMigrator.toJson(RuntimeMigrator.plan(need(source))));
                case "prepare" -> {
                    System.out.println("type the confirmation phrase to PREPARE the runtime database (copies only approved non-auth tables; the source is read-only):");
                    System.out.println(RuntimeMigrator.toJson(RuntimeMigrator.prepare(need(source), need(target), in.readLine())));
                }
                case "verify" -> System.out.println(RuntimeMigrator.toJson(RuntimeMigrator.verify(need(source), need(target))));
                default -> {
                    System.out.println("RESULT STOP unknown_command");
                    return 2;
                }
            }
            System.out.println("RESULT OK " + args[0]);
            return 0;
        } catch (RuntimeMigrator.MigrationException e) {
            System.out.println("RESULT STOP " + e.code);
            return 1;
        }
    }

    private static Path need(Path p) throws RuntimeMigrator.MigrationException {
        if (p == null) throw new RuntimeMigrator.MigrationException("path_required");
        return p;
    }

    private static Path option(String[] args, String name) {
        int i = Arrays.asList(args).indexOf(name);
        return i >= 0 && i + 1 < args.length ? Path.of(args[i + 1]) : null;
    }
}
