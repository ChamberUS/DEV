package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.NavigationGuard;
import panel.shell.ShellContext;
import panel.shell.ShellRouter;
import panel.shell.ShellRoutes;
import panel.shell.ShortcutRegistry;

/** AB01: the logo, the shortcut and the router share one Home action; the unsaved-changes guard defaults to Stay. */
class HomeNavigationTest {
    private static final class Fixture {
        final List<String> requested = new ArrayList<>();
        final List<String> displayed = new ArrayList<>();
        final ShellRouter router;
        final ByxShell shell;
        final Stage stage = new Stage();
        final MotionService motion = new MotionService();

        Fixture() {
            motion.preference.set(MotionPreference.OFF);
            router = new ShellRouter(new Navigator(), (t, k) -> {
                requested.add(t);
                return ShellRouter.Decision.ALLOW;
            }, displayed::add);
            shell = new ByxShell(router, motion, new LegacyHost());
            Scene scene = new Scene(shell, 1440, 900);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            stage.show();
            shell.applyCss();
            shell.layout();
        }

        void close() {
            shell.dispose();
            stage.close();
        }
    }

    @Test
    void homeRouteIsPublicToTheSessionNeverResearchAndHasItsOwnRail() {
        assertFalse(ShellRoutes.isResearch(ShellRoutes.HOME), "Home must not trigger admin verification");
        assertEquals(ShellContext.HOME, ShellRoutes.contextOf(ShellRoutes.HOME));
        assertEquals(ShellRoutes.HOME, ShellRoutes.home(ShellContext.HOME));
        List<String> rail = ShellRoutes.rail(ShellContext.HOME).stream().map(ShellRoutes.Route::id).toList();
        assertEquals(List.of("t-home", "t-desk", "t-markets", "t-byx", "t-benefits", "h-faq"), rail);
        assertTrue(rail.stream().noneMatch(ShellRoutes::isResearch));
    }

    @Test
    void paletteListsGoToHomeFirstAndItsEntryRequestsTheCanonicalRoute() {
        for (boolean admin : new boolean[] {true, false}) {
            var first = panel.ui.CommandPalette.commands(admin, admin, false).get(0);
            assertEquals("Go to Home", first.title());
            assertEquals(ShellRoutes.HOME, first.target());
            assertEquals("", first.state(), "Home needs no permission");
        }
    }

    @Test
    void logoIsAKeyboardAndPointerButtonThatRequestsHome() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.router.request("t-markets");
            var logo = f.shell.rail().logoButton();
            Object[] out = {logo.isFocusTraversable(), logo.getAccessibleText(), null, null, null};
            logo.fire();
            out[2] = f.router.route();
            logo.fire(); // already on Home: nothing is re-displayed
            logo.fire();
            out[3] = List.copyOf(f.displayed);
            out[4] = List.copyOf(f.requested);
            f.close();
            return out;
        });
        assertTrue((boolean) r[0]);
        assertTrue(((String) r[1]).contains("Go to Home"));
        assertEquals("t-home", r[2]);
        assertEquals(List.of("t-markets", "t-home"), r[3], "no re-render when already on Home");
        assertEquals(List.of("t-markets", "t-home"), r[4]);
    }

    @Test
    void shortcutGoesHomeOnlyWithTheShortcutModifierAndShift() throws Exception {
        boolean mac = ByxShell.isMac();
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.router.request("t-markets");
            // Cmd/Ctrl+H alone (macOS "Hide") must not navigate
            Event.fireEvent(f.shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, false, !mac, false, mac));
            String afterPlain = f.router.route();
            Event.fireEvent(f.shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, true, !mac, false, mac));
            String afterShift = f.router.route();
            f.close();
            return new Object[] {afterPlain, afterShift};
        });
        assertEquals("t-markets", r[0]);
        assertEquals("t-home", r[1]);
    }

    @Test
    void shortcutIsRegisteredAndDoesNotCollide() {
        long homeBindings = ShortcutRegistry.GROUPS.stream().flatMap(g -> g.items().stream()).filter(e -> e.keys().contains("H")).count();
        assertEquals(1, homeBindings);
        long sameCombo = ShortcutRegistry.GROUPS.stream().flatMap(g -> g.items().stream())
                .filter(e -> e.keys().equals(List.of("⌘/Ctrl", "Shift", "H"))).count();
        assertEquals(1, sameCombo, "no other action uses Cmd/Ctrl+Shift+H");
    }

    @Test
    void homeRequestReplacesAPendingRequestInsteadOfBeingDropped() {
        List<String> shown = new ArrayList<>();
        var pendingGate = new ShellRouter.Gate() {
            @Override
            public ShellRouter.Decision evaluate(String target, Navigator.Ticket ticket) {
                return target.equals("slow") ? ShellRouter.Decision.PENDING : ShellRouter.Decision.ALLOW;
            }
        };
        ShellRouter router = new ShellRouter(new Navigator(), pendingGate, shown::add);
        router.request("t-home");
        assertFalse(router.requestHome(), "already Home, nothing pending");
        router.request("slow");
        assertNotNull(router.pending());
        assertTrue(router.requestHome(), "a pending request for another page is replaced");
        assertEquals(List.of("t-home", "t-home"), shown);
    }

    // ---------------------------------------------------------------- navigation guard

    private record GuardRun(AtomicInteger stay, AtomicInteger discard, AtomicInteger saved, AtomicBoolean saveOk, Fixture f, NavigationGuard.Handle h) {
    }

    private static GuardRun guard(boolean canSave, boolean saveOk) {
        Fixture f = new Fixture();
        AtomicInteger stay = new AtomicInteger();
        AtomicInteger discard = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        AtomicBoolean ok = new AtomicBoolean(saveOk);
        var h = NavigationGuard.open(f.shell.overlay(), f.motion, 2, canSave, stay::incrementAndGet, discard::incrementAndGet, ok::get, saved::incrementAndGet);
        return new GuardRun(stay, discard, saved, ok, f, h);
    }

    @Test
    void guardDefaultFocusIsStayAndEscapeStays() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            GuardRun g = guard(true, true);
            boolean stayFocused = g.f().stage.getScene().getFocusOwner() == g.h().stay();
            Event.fireEvent(g.h().stay(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            Object[] out = {stayFocused, g.stay().get(), g.discard().get(), g.saved().get(), g.f().shell.overlay().openDialogs()};
            g.f().close();
            return out;
        });
        assertTrue((boolean) r[0], "initial focus is the safe choice");
        assertEquals(1, r[1]);
        assertEquals(0, r[2], "Esc never discards");
        assertEquals(0, r[3]);
        assertEquals(0, r[4], "dialog closed");
    }

    @Test
    void discardAndSaveAreExplicitAndFailedSaveNeverNavigatesOrDiscards() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            GuardRun fail = guard(true, false);
            fail.h().save().fire();
            Object[] a = {fail.saved().get(), fail.discard().get(), fail.stay().get(), fail.h().dialog().isOpen(), fail.h().message().isVisible()};
            fail.f().close();
            GuardRun ok = guard(true, true);
            ok.h().save().fire();
            Object[] b = {ok.saved().get(), ok.discard().get(), ok.h().dialog().isOpen()};
            ok.f().close();
            GuardRun d = guard(false, false);
            Object[] c0 = {d.h().save().isVisible()};
            d.h().discard().fire();
            Object[] c = {d.discard().get(), c0[0]};
            d.f().close();
            return new Object[] {a, b, c};
        });
        Object[] fail = (Object[]) ((Object[]) r)[0];
        assertEquals(0, fail[0]);
        assertEquals(0, fail[1]);
        assertEquals(0, fail[2]);
        assertTrue((boolean) fail[3], "stays on the dialog after a failed save");
        assertTrue((boolean) fail[4], "tells the user");
        Object[] ok = (Object[]) ((Object[]) r)[1];
        assertEquals(1, ok[0]);
        assertEquals(0, ok[1]);
        assertFalse((boolean) ok[2]);
        Object[] d = (Object[]) ((Object[]) r)[2];
        assertEquals(1, d[0]);
        assertFalse((boolean) d[1], "Save and go is hidden when the page cannot save");
    }
}
