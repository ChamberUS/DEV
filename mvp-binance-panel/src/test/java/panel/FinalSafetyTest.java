package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import panel.model.Snapshot;

/** Passo 14: guardas de fonte para trading, gates científicos e BYX sobre TODO o produto (src/main), não só sobre uma tela. */
class FinalSafetyTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            // QaApp e UserMenuProbe são ferramentas locais ignoradas pelo git e excluídas da compilação (pom): não fazem parte do produto
            return s.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("QaApp.java") && !p.getFileName().toString().contains("Probe.java")
                    || p.getFileName().toString().equals("SystemMotionProbe.java")).toList();
        }
    }

    private static Set<String> filesMatching(String regex) throws IOException {
        Pattern p = Pattern.compile(regex);
        Set<String> out = new TreeSet<>();
        for (Path f : sources()) {
            if (p.matcher(Files.readString(f)).find()) {
                out.add(MAIN.resolve("panel").relativize(f).toString());
            }
        }
        return out;
    }

    /** Nome do método (nível de classe) que contém a primeira ocorrência do trecho no arquivo. */
    private static String enclosingMethod(String file, String needle) throws IOException {
        String src = Files.readString(MAIN.resolve("panel").resolve(file));
        int at = src.indexOf(needle);
        assertTrue(at >= 0, needle + " in " + file);
        String[] lines = src.substring(0, at).split("\n");
        Pattern header = Pattern.compile("^ {4}(?!\\s).*\\b(\\w+)\\s*\\(.*\\)\\s*(throws [\\w., ]+)?\\{\\s*$");
        for (int i = lines.length - 1; i >= 0; i--) {
            var m = header.matcher(lines[i]);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new AssertionError("no enclosing method for " + needle);
    }

    /** Argumentos de cada {@code new ProcessBuilder(...)} do arquivo, sem shell: primeiro argumento é o executável literal e os demais são literais ou o pid. */
    private static void assertNoShell(String file, String executable, int expected) throws IOException {
        String src = Files.readString(MAIN.resolve("panel").resolve(file));
        var m = Pattern.compile("new ProcessBuilder\\(([^;]*?)\\)\\s*(?:[.;)]|:)").matcher(src);
        int found = 0;
        while (m.find()) {
            found++;
            String[] args = m.group(1).split(",\\s*(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            assertEquals("\"" + executable + "\"", args[0].trim(), file + ": the executable is a fixed literal path");
            for (int i = 1; i < args.length; i++) {
                String a = args[i].trim();
                assertTrue(a.matches("\"[^\"]*\"") || a.matches("Long\\.toString\\(\\w+\\)"), file + ": argument " + i + " is a literal or the pid, not input: " + a);
            }
        }
        assertEquals(expected, found, file + ": the expected subprocess launch sites");
        // nenhum outro caminho de execução: sem Runtime.exec e sem ProcessBuilder montado de outra forma (os argumentos acima já são literais)
        assertFalse(src.contains("Runtime.getRuntime().exec") || src.contains("ProcessBuilder.command("), file + ": no other way to start a process");
    }

    @Test
    void liveTradingCanNeverBeEnabledByTheProduct() throws IOException {
        assertEquals(Set.of(), filesMatching("\\.trading\\s*=[^=]"), "no code writes the trading mode");
        assertEquals(Set.of(), filesMatching("trading\\s*=\\s*\"ENABLED"), "no code sets ENABLED");
        assertEquals(Set.of(), filesMatching("(?i)(api\\.binance|fapi\\.binance|/fapi/|newOrder|X-MBX-APIKEY|createOrder|placeOrder|submitOrder)"),
                "no exchange or order endpoint exists in the product");
        // HMAC-SHA256 só em três pontos nomeados, cada um num método específico (não por arquivo inteiro): OTP, prova de pareamento do serviço
        // local e impressão do identificador de tentativas sem conta (auditoria). Nenhum assina requisição de exchange.
        assertEquals(Set.of("auth/OtpService.java", "auth/AuthService.java", "localservice/LocalServiceClient.java"), filesMatching("HmacSHA256"));
        assertEquals("mac", enclosingMethod("auth/OtpService.java", "Mac.getInstance(\"HmacSHA256\")"));
        assertEquals("fingerprint", enclosingMethod("auth/AuthService.java", "Mac.getInstance(\"HmacSHA256\")"));
        assertEquals("proof", enclosingMethod("localservice/LocalServiceClient.java", "Mac.getInstance(\"HmacSHA256\")"));
        assertTrue(Files.readString(MAIN.resolve("panel/localservice/LocalServiceClient.java")).contains("\"byx-ipc-v1|\""), "the pairing proof is domain-separated");
        assertEquals(Set.of(), filesMatching("Mac\\.getInstance\\(\"Hmac(SHA1|SHA512|MD5)"), "no other HMAC variant is used anywhere");
        assertTrue(Files.readString(MAIN.resolve("panel/shell/DockModel.java")).contains("liveOff"), "the dock keeps the Live trading OFF text");
    }

    @Test
    void scientificGatesAreConstantsAndNoRouteOpensThem() throws Exception {
        Snapshot s = new Snapshot();
        assertEquals("LOCKED", s.validationStatus);
        assertEquals("SEALED", s.finalHoldout);
        assertEquals(Set.of(), filesMatching("\\.(validationStatus|finalHoldout)\\s*=[^=]"), "no code assigns a gate");
        for (var r : panel.shell.ShellRoutes.all()) {
            assertFalse(r.id().toLowerCase().contains("holdout"), "no route exposes the final holdout: " + r.id());
        }
        String app = Files.readString(MAIN.resolve("panel/app/PanelApp.java"));
        for (String locked : List.of("\"validation\"", "\"paper\"", "\"live\"")) {
            assertTrue(Pattern.compile("views\\.put\\(" + Pattern.quote(locked) + ", new LockedView\\(").matcher(app).find(), locked + " stays a LockedView");
        }
        // o Onboarding, as Views de Help, Account, System e BYX V2 nunca tocam jobs, processos ou gates
        for (String pkg : List.of("systemview", "helpview", "accountview", "byxview")) {
            try (Stream<Path> files = Files.list(MAIN.resolve("panel/" + pkg))) {
                for (Path f : files.toList()) {
                    String src = Files.readString(f);
                    assertFalse(src.contains("ctx.jobs") || src.contains("JobManager") || src.contains("ProcessBuilder") || src.contains("research.start"), f + " touches jobs or processes");
                }
            }
        }
    }

    @Test
    void byxV2IsReadOnlyAndTheLegacyActionsAreNotExposed() throws IOException {
        assertEquals(Set.of(), filesMatching("byxGas\\.(request|revoke|refresh)\\("), "gas request/revoke/refresh is called from no UI");
        assertEquals(Set.of(), filesMatching("byxPayments\\.(create|confirm)\\("), "payment intents are created and confirmed from no UI");
        // O resolver de captura pertence a outro trabalho e pode ou não estar presente no checkout: a exceção só existe SE o arquivo existir
        // (e então é verificada de forma específica abaixo); sem ele, nenhum outro ProcessBuilder é aceito.
        boolean resolverPresent = Files.exists(MAIN.resolve("panel/adapter/CaptureRuntimeResolver.java"));
        Set<String> expectedProcesses = new TreeSet<>(Set.of("adapter/LocalnetGasTestSigner.java", "motion/SystemMotionProbe.java", "process/ProcessRunner.java"));
        if (resolverPresent) {
            expectedProcesses.add("adapter/CaptureRuntimeResolver.java");
        }
        assertEquals(expectedProcesses, filesMatching("ProcessBuilder"),
                "processes: the capture resolver when present (read-only lsof, fixed arguments), the test signer (opt-in by env), the OS probe and the existing ProcessRunner only");
        // as duas exceções novas são específicas: executável literal, argumentos literais (ou o pid), nenhum shell, um único subprocesso
        if (resolverPresent) {
            assertNoShell("adapter/CaptureRuntimeResolver.java", "/usr/sbin/lsof", 1);
        }
        assertNoShell("motion/SystemMotionProbe.java", "/usr/bin/defaults", 2);
        // o signer de teste só existe atrás da variável de ambiente e do modo de desenvolvimento
        String ctx = Files.readString(MAIN.resolve("panel/app/AppContext.java"));
        assertTrue(ctx.contains("I_ACKNOWLEDGE_TEST_ONLY") && ctx.contains("security.devMode()"));
        assertEquals(Set.of("accountview/SettingsScreen.java", "helpview/AboutScreen.java", "systemview/OnboardingDialog.java"), filesMatching("DEVNET"),
                "DEVNET appears only as unavailable text, never as an option");
        assertEquals(Set.of("helpview/DiagnosticsReport.java"), filesMatching("(privateKey|PrivateKey|mnemonic|seedPhrase|KeyPairGenerator)"), "no private key, mnemonic or key generation in code (UI statements about keys are text; the only hit is the diagnostics redaction list)");
    }

    @Test
    void productCodeHasNoDebugLeftovers() throws IOException {
        assertEquals(Set.of(), filesMatching("\\b(TODO|FIXME|XXX|HACK)\\b"));
        assertEquals(Set.of(), filesMatching("\"/(tmp|private|Users)/"), "no absolute temp or user paths");
        // System.out só nas ferramentas de desenvolvimento declaradas (galeria e probe); o log de erro inesperado usa System.err
        Set<String> out = filesMatching("System\\.out");
        assertTrue(Set.of("app/GalleryApp.java", "design/ControlGallery.java", "app/PanelApp.java").containsAll(out), "debug System.out in product code: " + out);
        assertTrue(Files.readString(MAIN.resolve("panel/app/PanelApp.java")).contains("Boolean.getBoolean(\"byx.runtime.diagnostics\")"), "the runtime dump is opt-in");
        assertEquals(Set.of(), filesMatching("(?i)(demo data|DEMO_DATA)\\W.*\\bnew\\b").stream().filter(f -> !f.startsWith("design/")).collect(Collectors.toSet()));
    }
}
