package panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guardas de fonte: nada de inspeção de processo/subprocesso/varredura na UI, e o fundo do login sem laço de 60 Hz. */
class StartupGuardTest {
    private static String src(String rel) throws IOException {
        return Files.readString(Path.of("src/main/java/panel", rel));
    }

    @Test
    void uiLayersNeverInspectProcessesOrSpawnSubprocesses() throws IOException {
        for (String file : new String[] {"app/PanelApp.java", "shell/DockModel.java", "shell/StatusDock.java", "systemview/SystemStatusModel.java",
                "authview/AuthScreens.java", "authview/BrandPanel.java"}) {
            String s = src(file);
            for (String forbidden : new String[] {"ProcessBuilder", "ProcessHandle", "lsof", "Runtime.getRuntime().exec", "ScientificCaptureResolver", "CaptureRuntimeResolver"}) {
                assertFalse(s.contains(forbidden), file + " must not touch " + forbidden + " (it belongs to the background capture service)");
            }
        }
    }

    @Test
    void theDockReadsTheCaptureStatusOnlyThroughTheNonBlockingAccessor() throws IOException {
        String app = src("app/PanelApp.java");
        assertTrue(app.contains("ctx.scientificCapture.current()"));
        assertFalse(app.contains("scientificCapture.observe"), "the FX thread never triggers a status read");
    }

    @Test
    void theLoginBackdropHasNoAnimationTimerOrTimelineLoop() throws IOException {
        String s = src("authview/BrandPanel.java");
        assertFalse(s.contains("AnimationTimer"), "the 60 Hz pulse loop is gone");
        assertFalse(s.contains("new Timeline"), "no JavaFX animation clock keeps the pulse loop alive");
        assertTrue(s.contains("ScheduledExecutorService"), "a low-frequency clock drives the decoration");
    }

    @Test
    void startupWarmupOnlyLoadsClassesAndTouchesNoNetworkOrUserState() throws IOException {
        String s = src("app/StartupWarmup.java");
        for (String forbidden : new String[] {"HttpClient", "Socket", "URL(", "ensureService", "login(", "Files.", "ProcessBuilder"}) {
            assertFalse(s.contains(forbidden), "warm-up must not use " + forbidden);
        }
    }

    @Test
    void theLoginSurfaceNeverReferencesTheMascotAndBootstrapOnlyRegistersTheEmptyGallery() throws IOException {
        for (String file : new String[] {"authview/AuthScreens.java", "authview/AuthLayout.java", "authview/BrandPanel.java", "authview/LoginController.java",
                "app/StartupWarmup.java", "app/Main.java"}) {
            assertFalse(src(file).contains("mascot"), file + " must not load the mascot (it only lives in post-login screens)");
        }
        String app = src("app/PanelApp.java");
        int start = app.indexOf("public void start(Stage stage)");
        int showEntry = app.indexOf("private void showEntry(");
        String bootstrap = app.substring(start, showEntry);
        assertFalse(bootstrap.contains("Mascot"), "PanelApp.start must not touch the mascot");
        // a galeria é registrada vazia, dentro do fluxo pós-login (afterLogin), e só abre sob demanda
        int afterLogin = app.indexOf("private void afterLogin(");
        assertTrue(app.indexOf("t-mascot-gallery\", new panel.mascot.MascotGallery") > afterLogin, "the gallery is created only after login");
    }

    @Test
    void productionPanelHasNoMarketBypassAndOnlyTwoKnownEnvironmentReads() throws IOException {
        java.util.List<String> env = new java.util.ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                for (String forbidden : new String[] {"disableMarketForTest", "skipMarket", "offlineTestMode", "offlineMode", "noMarket", "BYX_NO_MARKET", "FakeMarket"}) {
                    assertFalse(src.contains(forbidden), p + " must not contain " + forbidden);
                }
                int i = 0;
                while ((i = src.indexOf("System.getenv(", i)) >= 0) {
                    int end = src.indexOf(')', i);
                    env.add(src.substring(i + 14, end));
                    i = end;
                }
            }
        }
        for (String e : env) {
            assertTrue(e.equals("\"BYX_LOCAL_SERVICE_HOME\"") || e.equals("\"BYX_STARTUP_TRACE\""), "unexpected environment switch " + e);
        }
    }
}
