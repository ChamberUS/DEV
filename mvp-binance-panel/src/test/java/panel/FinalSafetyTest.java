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

    @Test
    void liveTradingCanNeverBeEnabledByTheProduct() throws IOException {
        assertEquals(Set.of(), filesMatching("\\.trading\\s*=[^=]"), "no code writes the trading mode");
        assertEquals(Set.of(), filesMatching("trading\\s*=\\s*\"ENABLED"), "no code sets ENABLED");
        assertEquals(Set.of(), filesMatching("(?i)(api\\.binance|fapi\\.binance|/fapi/|newOrder|X-MBX-APIKEY|createOrder|placeOrder|submitOrder)"),
                "no exchange or order endpoint exists in the product");
        assertEquals(Set.of("auth/OtpService.java"), filesMatching("HmacSHA256"), "the only HMAC is the OTP one: no exchange request signing");
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
        assertEquals(Set.of("adapter/LocalnetGasTestSigner.java", "motion/SystemMotionProbe.java", "process/ProcessRunner.java"), filesMatching("ProcessBuilder"),
                "processes: the test signer (opt-in by env), the OS probe and the existing ProcessRunner only");
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
