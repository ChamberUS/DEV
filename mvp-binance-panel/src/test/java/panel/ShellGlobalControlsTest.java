package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.design.OverlayLayer;
import panel.design.RegionState;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.NotificationPanel;
import panel.shell.ShellPalette;
import panel.shell.ShellPalette.Entry;
import panel.shell.ShellRouter;
import panel.shell.UserMenu;

/** Busca/paleta, menu do usuário e painel de notificações: só pedem rota, gates reais, camadas e foco. */
class ShellGlobalControlsTest {
    private static final class Fixture {
        final List<String> requested = new ArrayList<>();
        final ShellRouter router;
        final ByxShell shell;
        final Stage stage = new Stage();
        final ShellPalette palette;
        final UserMenu menu;
        final NotificationPanel notifications;
        final AtomicInteger refreshes = new AtomicInteger();

        Fixture(MotionPreference p) {
            MotionService motion = new MotionService();
            motion.preference.set(p);
            router = new ShellRouter(new Navigator(), (t, k) -> {
                requested.add(t);
                return ShellRouter.Decision.ALLOW;
            }, id -> { });
            shell = new ByxShell(router, motion, new LegacyHost());
            Scene scene = new Scene(shell, 1440, 900);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            stage.show();
            router.request("t-desk");
            palette = new ShellPalette(shell.overlay(), router::request, () -> List.of(
                    Entry.nav("Go to Trading Desk", "t-desk", null),
                    Entry.nav("Open Research", "overview", "Admin verification required"),
                    Entry.gated(ShellPalette.Group.NAVIGATION, "Final holdout", "Sealed"),
                    Entry.command("Refresh data", refreshes::incrementAndGet),
                    Entry.gated(ShellPalette.Group.HELP, "Keyboard shortcuts", "Arrives in step 11")));
            shell.setOnOpenSearch(palette::open);
            menu = new UserMenu(shell.overlay(), shell.topBar().avatar(), router);
            menu.setIdentity(new UserMenu.Identity("qa-admin", null, "Administrator"));
            menu.setItems(List.of(UserMenu.Item.route("Profile", "profile", null, "t-profile"),
                    UserMenu.Item.pending("Security", "security", null, "Arrives in step 10"),
                    UserMenu.Item.route("Settings", "settings", "⌘,", "t-settings"),
                    UserMenu.Item.action("Sign out", "logout", null, () -> requested.add("SIGN-OUT")).asDanger()));
            notifications = new NotificationPanel(shell.overlay(), shell.topBar().notifications(), motion);
            shell.applyCss();
            shell.layout();
        }

        void close() {
            shell.dispose();
            stage.close();
        }

        Node focus() {
            return stage.getScene().getFocusOwner();
        }
    }

    private static void esc(Node n) {
        Event.fireEvent(n, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    }

    private static void press(Node n) {
        Event.fireEvent(n, new MouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false, false,
                false, true, false, false, true, false, false, null));
    }

    @Test
    void paletteNavigatesOnlyThroughTheRouter() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            Object[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p);
                f.shell.topBar().search().fire();
                boolean open = f.palette.isOpen();
                boolean focused = f.focus() != null && f.focus().getStyleClass().contains("byx-palette-input");
                f.palette.setQuery("research");
                f.palette.key(KeyCode.ENTER);
                Object[] out = {open, focused, List.copyOf(f.requested), f.router.route(), f.palette.isOpen()};
                f.close();
                return out;
            });
            assertTrue((boolean) r[0], p + " opens");
            assertTrue((boolean) r[1], p + " input focused");
            assertEquals(List.of("t-desk", "overview"), r[2], p + " palette only requests a route");
            assertEquals("overview", r[3], p.name());
            assertFalse((boolean) r[4], p + " closes after activation");
        }
    }

    @Test
    void gatedRowsNeverNavigateAndShowTheReason() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.palette.open();
            f.palette.setQuery("holdout");
            String footer = f.palette.footerText();
            f.palette.key(KeyCode.ENTER);
            Object[] out = {footer, List.copyOf(f.requested), f.palette.isOpen()};
            f.close();
            return out;
        });
        assertEquals("Final holdout: Sealed", r[0]);
        assertEquals(List.of("t-desk"), r[1], "gated row must not navigate");
        assertTrue((boolean) r[2], "palette stays open on a gated row");
    }

    @Test
    void arrowsMoveSelectionAndCommandsRun() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.palette.open();
            String first = f.palette.selection().title();
            f.palette.key(KeyCode.DOWN);
            f.palette.key(KeyCode.DOWN);
            f.palette.key(KeyCode.DOWN);
            String fourth = f.palette.selection().title();
            f.palette.key(KeyCode.ENTER);
            Object[] out = {first, fourth, f.refreshes.get(), f.router.route()};
            f.close();
            return out;
        });
        assertEquals("Go to Trading Desk", r[0]);
        assertEquals("Refresh data", r[1]);
        assertEquals(1, r[2]);
        assertEquals("t-desk", r[3], "a command does not navigate");
    }

    @Test
    void openCloseOpenLeavesPaletteOpenWithInputFocused() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            f.palette.open();
            esc(f.focus()); // teclas vão para o dono do foco (dentro do host de camadas)
            boolean closed = !f.palette.isOpen();
            f.palette.open();
            f.palette.open(); // Cmd+K de novo com a paleta aberta
            boolean[] out = {closed, f.palette.isOpen(), f.focus() != null && f.focus().getStyleClass().contains("byx-palette-input"),
                    f.shell.overlay().topLayer() == OverlayLayer.PALETTE};
            f.close();
            return out;
        });
        assertTrue(r[0], "Esc closes the palette");
        assertTrue(r[1]);
        assertTrue(r[2]);
        assertTrue(r[3]);
    }

    @Test
    void userMenuKeyboardAndFocusReturn() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.shell.topBar().avatar().requestFocus();
            Event.fireEvent(f.shell.topBar().avatar(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
            boolean open = f.menu.isOpen();
            int enabled = f.menu.itemButtons().size();
            String first = f.focus().getAccessibleText();
            f.menu.key(KeyCode.UP); // volta para o último
            String wrapped = f.focus().getAccessibleText();
            esc(f.focus()); // teclas vão para o dono do foco (dentro do host de camadas)
            Object[] out = {open, enabled, first, wrapped, f.menu.isOpen(), f.focus() == f.shell.topBar().avatar(),
                    f.shell.topBar().avatar().getStyleClass().contains("expanded")};
            f.close();
            return out;
        });
        assertTrue((boolean) r[0]);
        assertEquals(3, r[1], "pending item is not focusable");
        assertEquals("Profile", r[2]);
        assertEquals("Sign out", r[3], "arrow up wraps");
        assertFalse((boolean) r[4], "Esc closes");
        assertTrue((boolean) r[5], "focus returns to the avatar");
        assertFalse((boolean) r[6]);
    }

    @Test
    void userMenuItemsRequestRoutesAndPendingNeverOpens() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.menu.open(true);
            f.menu.itemButtons().get(1).fire(); // Settings
            String afterSettings = f.router.route();
            f.menu.open(true);
            boolean securityDisabled = f.shell.overlay().layer(OverlayLayer.POPOVER).lookupAll(".byx-menu-item").stream()
                    .anyMatch(n -> n.isDisabled() && n.getAccessibleText().startsWith("Security"));
            f.close();
            return new Object[] {afterSettings, securityDisabled, List.copyOf(f.requested)};
        });
        assertEquals("t-settings", r[0]);
        assertTrue((boolean) r[1], "Security is disabled with its reason, not a fake screen");
        assertEquals(List.of("t-desk", "t-settings"), r[2]);
    }

    @Test
    void outsideClickClosesAndOwnerToggles() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.shell.topBar().avatar().fire();
            boolean opened = f.menu.isOpen();
            press(f.shell.topBar().avatar()); // dono: não fecha no press
            f.shell.topBar().avatar().fire(); // alterna: fecha
            boolean toggledClosed = !f.menu.isOpen();
            f.shell.topBar().avatar().fire();
            press(f.shell.dock()); // fora
            boolean outsideClosed = !f.menu.isOpen();
            f.close();
            return new boolean[] {opened, toggledClosed, outsideClosed};
        });
        assertTrue(r[0]);
        assertTrue(r[1], "avatar click toggles the menu closed");
        assertTrue(r[2], "outside click closes");
    }

    @Test
    void notificationPanelIsHonestAndNeverCoexistsWithMenu() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            f.menu.open(true);
            f.shell.topBar().notifications().fire();
            Object[] out = {f.notifications.isOpen(), f.menu.isOpen(), f.notifications.state(),
                    f.shell.overlay().openPopovers(), f.shell.topBar().unreadVisible(), f.router.route()};
            f.close();
            return out;
        });
        assertTrue((boolean) r[0]);
        assertFalse((boolean) r[1], "panel and user menu never coexist");
        assertEquals(RegionState.UNAVAILABLE, r[2], "no service: UNAVAILABLE, never fake items");
        assertEquals(1, r[3]);
        assertFalse((boolean) r[4], "no count without a service");
        assertEquals("t-desk", r[5], "opening overlays never navigates");
    }

    @Test
    void overlaysEndInTheSameLogicalStateInEveryMode() throws Exception {
        List<String> states = new ArrayList<>();
        for (MotionPreference p : MotionPreference.values()) {
            states.add(FxSupport.fx(() -> {
                Fixture f = new Fixture(p);
                f.palette.open();
                f.palette.close();
                f.menu.open(true);
                f.shell.topBar().notifications().fire();
                f.shell.topBar().notifications().fire();
                f.menu.open(true);
                f.palette.open();
                String s = f.shell.overlay().topLayer() + " popovers=" + f.shell.overlay().openPopovers()
                        + " palette=" + f.palette.isOpen() + " menu=" + f.menu.isOpen() + " route=" + f.router.route();
                f.close();
                return s;
            }));
        }
        assertEquals(states.get(0), states.get(1), "FULL vs REDUCED");
        assertEquals(states.get(0), states.get(2), "FULL vs OFF");
        assertEquals("PALETTE popovers=0 palette=true menu=false route=t-desk", states.get(0));
    }
}
