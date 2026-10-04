package panel;

import org.junit.jupiter.api.Test;
import panel.ui.CommandPalette;
import static org.junit.jupiter.api.Assertions.*;

class CommandPaletteTest {
    @Test void userHasOnlyPublicNavigationEvenWithInconsistentVerificationFlag() {
        for (boolean verified : new boolean[] {false, true}) {
            var commands = CommandPalette.commands(false, verified);
            assertTrue(commands.stream().filter(c -> c.target() != null).allMatch(c -> c.target().startsWith("t-")));
            assertFalse(commands.stream().anyMatch(c -> c.title().contains("Final holdout")));
        }
    }
    @Test void unverifiedAdminGetsExplicitVerificationState() {
        var capture = CommandPalette.commands(true, false).stream().filter(c -> "capture".equals(c.target())).findFirst().orElseThrow();
        assertEquals("Admin verification required", capture.state());
    }
    @Test void authorizedAdminStillCannotOpenSealedOrUnavailableTargets() {
        var commands = CommandPalette.commands(true, true);
        assertNull(commands.stream().filter(c -> c.state().equals("Sealed")).findFirst().orElseThrow().target());
        assertNull(commands.stream().filter(c -> c.state().equals("Environment unavailable")).findFirst().orElseThrow().target());
        assertEquals("Locked · ResearchGuard", commands.stream().filter(c -> "validation".equals(c.target())).findFirst().orElseThrow().state());
    }
    @Test void selectionRoutesThroughProvidedGateAndEscapeClosesOverlay() throws Exception {
        FxSupport.fx(() -> {
            var target = new java.util.concurrent.atomic.AtomicReference<String>();
            var palette = new CommandPalette(target::set);
            var root = new javafx.scene.layout.StackPane();
            new javafx.scene.Scene(root, 800, 700);
            palette.open(root, true, false);
            root.applyCss(); root.layout();
            var row = root.lookupAll(".command-row").stream().map(n -> (javafx.scene.control.Button) n)
                    .filter(b -> b.getText().startsWith("Open Capture")).findFirst().orElseThrow();
            row.fire();
            assertEquals("capture", target.get());
            assertTrue(root.getChildren().isEmpty());
            palette.open(root, true, true);
            var overlay = root.getChildren().getFirst();
            overlay.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            assertTrue(root.getChildren().isEmpty());
        });
    }
    @Test void expiredAdminSessionReturnsPaletteToVerificationRequired() {
        var auth = AuthFixture.ready(); auth.seedAdmin(); auth.auth.login("boss", "correct-horse-1".toCharArray());
        auth.authorize();
        assertTrue(auth.access.hasValidAdminSession());
        auth.clock.advance(java.time.Duration.ofMinutes(31));
        assertFalse(auth.access.hasValidAdminSession());
        assertEquals("Admin verification required", CommandPalette.commands(true, auth.access.hasValidAdminSession()).stream()
                .filter(c -> "overview".equals(c.target())).findFirst().orElseThrow().state());
    }
}
