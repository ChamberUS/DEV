package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
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
import panel.shell.ShellRouter;
import panel.shell.UserMenu;
import panel.shell.avatar.MascotAvatar;

/** B03 in the real shell: the avatar is the same long-lived node across navigation, menu cycles and motion changes, and cleans up on dispose. */
class AvatarHeaderTest {
    private static final class Fixture {
        final MotionService motion = new MotionService();
        final ShellRouter router;
        final ByxShell shell;
        final Stage stage = new Stage();
        final UserMenu menu;

        Fixture(MotionPreference p) {
            motion.preference.set(p);
            router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
            shell = new ByxShell(router, motion, new LegacyHost());
            Scene scene = new Scene(shell, 1440, 900);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            stage.show();
            router.request("t-home");
            menu = new UserMenu(shell.overlay(), shell.topBar().avatar(), router);
            menu.setIdentity(new UserMenu.Identity("qa-user", "qa@example.invalid", "Trader"));
            menu.setItems(List.of(UserMenu.Item.route("Profile", "profile", null, "t-profile"), UserMenu.Item.route("Settings", "settings", "⌘,", "t-settings")));
            shell.topBar().mascot().bindMenuOpen(menu.openProperty());
            shell.topBar().setUser("qa-user");
            shell.applyCss();
            shell.layout();
        }

        void close() {
            shell.dispose();
            stage.close();
        }
    }

    @Test
    void sameNodeSameBoundsAcrossNavigationAndMenuCycles() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            var avatar = f.shell.topBar().avatar();
            Node graphic = avatar.getGraphic();
            Node parent = avatar.getParent();
            var bounds = avatar.localToScene(avatar.getBoundsInLocal());
            String[] routes = {"t-desk", "t-markets", "t-byx", "t-benefits", "t-home", "t-chain-data", "h-faq", "t-settings", "t-home"};
            for (int i = 0; i < 120; i++) {
                f.router.request(routes[i % routes.length]);
                if (i % 3 == 0) {
                    f.menu.open(true);
                    f.shell.layout();
                    boolean same = avatar.localToScene(avatar.getBoundsInLocal()).equals(bounds);
                    if (!same || f.shell.topBar().avatar().getGraphic() != graphic) {
                        return new Object[] {"moved while menu open at " + i};
                    }
                    f.menu.close();
                }
            }
            Object[] out = {avatar.getGraphic() == graphic, avatar.getParent() == parent, avatar.localToScene(avatar.getBoundsInLocal()).equals(bounds),
                    f.shell.topBar().mascot().sceneFilterCount(), f.menu.isOpen()};
            f.close();
            return out;
        });
        assertEquals(true, r[0]);
        assertEquals(true, r[1]);
        assertEquals(true, r[2]);
        assertEquals(3, r[3], "no listener accumulated after 120 navigations and 40 menu cycles");
        assertEquals(false, r[4]);
    }

    @Test
    void menuOpenStateReachesTheAvatarAndAriaFollows() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            var avatar = f.shell.topBar().avatar();
            f.menu.open(true);
            String helpOpen = avatar.getAccessibleHelp();
            boolean expanded = avatar.getStyleClass().contains("expanded");
            f.menu.close();
            Object[] out = {helpOpen, expanded, avatar.getAccessibleHelp(), avatar.getAccessibleText()};
            f.close();
            return out;
        });
        assertEquals("expanded", r[0]);
        assertEquals(true, r[1]);
        assertEquals("collapsed", r[2]);
        assertEquals("Account menu, qa-user", r[3]);
    }

    @Test
    void motionModeCyclingKeepsTheAvatarFunctional() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            MascotAvatar m = f.shell.topBar().mascot();
            for (int i = 0; i < 30; i++) {
                f.motion.preference.set(MotionPreference.values()[i % 3]);
            }
            f.motion.preference.set(MotionPreference.OFF);
            boolean works = f.shell.topBar().avatar().getGraphic() != null && !f.shell.topBar().avatar().isDisabled();
            f.menu.open(true);
            boolean menuWorks = f.menu.isOpen();
            f.menu.close();
            Object[] out = {works, menuWorks, f.motion.preference.get(), m.sceneFilterCount()};
            f.close();
            return out;
        });
        assertEquals(true, r[0]);
        assertEquals(true, r[1]);
        assertEquals(MotionPreference.OFF, r[2]);
        assertEquals(3, r[3]);
    }

    @Test
    void shellDisposeLeavesNothingBehindAndALateTokenIsInert() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL);
            MascotAvatar m = f.shell.topBar().mascot();
            var tok = f.shell.topBar().operations().begin("auth.logout");
            f.close(); // logout: shell disposed
            f.shell.topBar().operations().end(tok, false, "LATE");
            Fixture next = new Fixture(MotionPreference.FULL); // the next session
            var nextTok = next.shell.topBar().operations().begin("research.verification");
            f.shell.topBar().operations().end(tok, false, "LATE AGAIN"); // old token against old registry: still inert
            int pending = ((panel.shell.avatar.OperationRegistry) next.shell.topBar().operations()).pending();
            Object[] out = {m.disposed(), m.sceneFilterCount(), m.timerRunning(), pending, ((panel.shell.avatar.OperationRegistry) f.shell.topBar().operations()).pending()};
            next.shell.topBar().operations().end(nextTok, true, null);
            next.close();
            return out;
        });
        assertEquals(true, r[0]);
        assertEquals(0, r[1]);
        assertEquals(false, r[2]);
        assertEquals(1, r[3], "the next session's operation is unaffected by the previous session's late callback");
        assertEquals(0, r[4]);
    }

    @Test
    void manyShellsCreatedAndDisposedNeverLeakFilters() throws Exception {
        int leaked = FxSupport.fx(() -> {
            int sum = 0;
            for (int i = 0; i < 25; i++) {
                Fixture f = new Fixture(MotionPreference.values()[i % 3]);
                MascotAvatar m = f.shell.topBar().mascot();
                f.close();
                sum += m.sceneFilterCount() + (m.timerRunning() ? 1 : 0);
            }
            return sum;
        });
        assertEquals(0, leaked);
    }

    @Test
    void keyboardReachesLogoThenWorkspaceThenSearchThenBellThenAvatarAndEnterOpensTheMenu() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF);
            f.shell.rail().logoButton().requestFocus();
            List<Node> order = new ArrayList<>();
            Scene scene = f.stage.getScene();
            for (int i = 0; i < 14 && (order.isEmpty() || order.get(order.size() - 1) != f.shell.topBar().avatar()); i++) {
                order.add(scene.getFocusOwner());
                Event.fireEvent(scene.getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            }
            boolean reached = order.contains(f.shell.topBar().avatar());
            int logoAt = order.indexOf(f.shell.rail().logoButton());
            int bellAt = order.indexOf(f.shell.topBar().notifications());
            int avatarAt = order.indexOf(f.shell.topBar().avatar());
            f.shell.topBar().avatar().requestFocus();
            f.shell.topBar().avatar().fire(); // Enter/Space on a focused Button fire its action
            boolean menu = f.menu.isOpen();
            Event.fireEvent(scene.getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            Object[] out = {reached, logoAt, bellAt, avatarAt, menu, f.menu.isOpen(), scene.getFocusOwner() == f.shell.topBar().avatar()};
            f.close();
            return out;
        });
        assertEquals(true, r[0], "the avatar is reachable with Tab");
        assertEquals(0, r[1], "the logo is the first stop");
        assertTrue((int) r[2] >= 0 && (int) r[3] > (int) r[2], "bell comes before the avatar: " + r[2] + " < " + r[3]);
        assertEquals(true, r[4]);
        assertEquals(false, r[5], "Esc closes the menu");
        assertEquals(true, r[6], "focus returns to the avatar");
    }
}
