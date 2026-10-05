package panel.researchview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javafx.scene.control.ButtonBase;
import org.junit.jupiter.api.Test;
import panel.model.Snapshot;
import panel.tradeview.DeskHarness;
import panel.tradeview.DeskNodes;

/**
 * Gates científicos: VALIDATION continua LOCKED, FINAL_HOLDOUT continua SEALED, a UI não altera o research guard,
 * nenhum clique da V2 contorna um gate, nenhuma fixture de QA grava estado científico.
 */
class ResearchGateTest {
    private static final Path SRC = Path.of("src/main/java/panel/researchview");
    private static final Path TESTS = Path.of("src/test/java/panel/researchview");

    @Test
    void theResearchViewsCannotReachJobsProcessesOrFiles() throws IOException {
        Pattern forbidden = Pattern.compile("(JobManager|CommandSpec|CommandAdapter|ProcessBuilder|Runtime\\.getRuntime|\\.submit\\(|AppContext|"
                + "Files\\.(write|delete|move|copy|createFile)|FileWriter|FileOutputStream|\\.validationStatus\\s*=|\\.finalHoldout\\s*=|\\.partition\\s*=)");
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(forbidden.matcher(Files.readString(p)).find(), p + " must not start jobs, processes or write files or gates");
            }
        }
    }

    @Test
    void theCaptureServiceIsUsedReadOnly() throws IOException {
        String screen = Files.readString(SRC.resolve("CaptureScreen.java"));
        Set<String> calls = new java.util.TreeSet<>();
        var m = Pattern.compile("monitor\\.(\\w+)\\(").matcher(screen);
        while (m.find()) {
            calls.add(m.group(1));
        }
        assertEquals(Set.of("start", "stop", "refresh"), calls, "the monitor can only observe");
    }

    @Test
    void fixturesAreInMemoryAndNeverTouchTheFilesystemOrTheHome() throws IOException {
        for (String name : new String[] {"ResearchFixtures.java", "ResearchHarness.java"}) {
            String src = Files.readString(TESTS.resolve(name));
            assertFalse(Pattern.compile("(Files\\.|user\\.home|FileWriter|ProcessBuilder)").matcher(src).find(), name + " is in-memory only");
        }
    }

    @Test
    void noClickOnTheOverviewOpensAGateOrChangesAnything() throws Exception {
        DeskHarness.fx(() -> {
            Snapshot s = ResearchFixtures.trainReady(34, true);
            ResearchHarness h = ResearchHarness.open(1440, 900, s);
            int before = s.fingerprint();
            for (ButtonBase b : DeskNodes.all(h.overview, ButtonBase.class)) {
                b.fire();
            }
            h.overview.tiles().forEach(t -> t.getOnMouseClicked().handle(null));
            h.overview.guardTiles().forEach(t -> {
                assertTrue(t.getOnMouseClicked() == null && t.getOnKeyPressed() == null, "a gate tile has no handler");
            });
            h.overview.lookup("#research-attention").getOnMouseClicked().handle(null);
            // só pedidos de navegação para telas somente leitura; quem decide é o roteador/gate real
            Set<String> allowed = Set.of("capture", "dataset", "features", "labels", "hypotheses", "validation", "execution", "live", "logs");
            assertTrue(allowed.containsAll(h.navigations), h.navigations.toString());
            assertEquals(before, s.fingerprint(), "the snapshot is exactly what it was");
            assertEquals("LOCKED", s.validationStatus);
            assertEquals("SEALED", s.finalHoldout);
            assertEquals(List.of("TRAIN"), List.of(s.partition));
            h.close();
            return null;
        });
    }
}
