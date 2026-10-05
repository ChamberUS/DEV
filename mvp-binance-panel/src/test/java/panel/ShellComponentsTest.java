package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.design.StatusState;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellContext;
import panel.shell.ShellRouter;
import panel.shell.StatusDock;

/** Shell V2: medidas, seleção derivada da rota, motion por modo, dock sem reconstrução e sem navegação. */
class ShellComponentsTest {
    private static final class Fixture {
        final List<String> displayed = new ArrayList<>();
        final List<String> routeChanges = new ArrayList<>();
        final ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, displayed::add);
        final MotionService motion = new MotionService();
        final ByxShell shell;
        final Stage stage = new Stage();

        Fixture(MotionPreference p, int w, int h) {
            this(p, w, h, true);
        }

        /** show=false: cena sem janela (o macOS limita janelas ao tamanho da tela; layout não precisa de janela). */
        Fixture(MotionPreference p, int w, int h, boolean show) {
            motion.preference.set(p);
            router.routeProperty().addListener((o, a, b) -> routeChanges.add(b));
            shell = new ByxShell(router, motion, new LegacyHost(new Label("legacy content")));
            Scene scene = new Scene(shell, w, h);
            ByxTheme.apply(scene);
            stage.setScene(scene);
            if (show) {
                stage.show();
            }
            router.request("t-desk");
            layout();
        }

        void layout() {
            shell.applyCss();
            shell.layout();
        }

        void close() {
            shell.dispose();
            stage.close();
        }
    }

    private static Bounds inScene(Node n) {
        return n.localToScene(n.getLayoutBounds());
    }

    @Test
    void shellMeasuresAtEveryBreakpoint() throws Exception {
        int[][] sizes = {{1280, 760}, {1440, 900}, {1600, 1000}, {1920, 1080}};
        for (int[] s : sizes) {
            double[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(MotionPreference.OFF, s[0], s[1], false);
                Bounds rail = inScene(f.shell.rail());
                Bounds top = inScene(f.shell.topBar());
                Bounds dock = inScene(f.shell.dock());
                Bounds search = inScene(f.shell.topBar().search());
                Bounds avatar = inScene(f.shell.topBar().avatar());
                Bounds bell = inScene(f.shell.topBar().notifications());
                double[] out = {rail.getWidth(), rail.getHeight(), top.getHeight(), top.getMinX(), dock.getHeight(),
                        dock.getMaxY(), search.getWidth(), search.getHeight(), avatar.getWidth(), avatar.getMaxX(),
                        bell.getWidth(), maxChildOverlap(f.shell.topBar()), maxChildOverlap(f.shell.dock()),
                        lastChildMaxX(f.shell.dock()), dock.getMaxX()};
                f.close();
                return out;
            });
            String at = s[0] + "x" + s[1];
            assertEquals(68, r[0], 0.5, at + " rail width");
            assertEquals(s[1], r[1], 0.5, at + " rail height");
            assertEquals(56, r[2], 0.5, at + " top bar");
            assertEquals(68, r[3], 0.5, at + " top bar starts after rail");
            assertEquals(38, r[4], 0.5, at + " dock");
            assertEquals(s[1], r[5], 0.5, at + " dock at the bottom");
            assertEquals(320, r[6], 0.5, at + " search width");
            assertEquals(36, r[7], 0.5, at + " search height");
            assertEquals(36, r[8], 0.5, at + " avatar");
            assertEquals(s[0] - 20, r[9], 0.5, at + " avatar right edge (padding 20)");
            assertEquals(38, r[10], 0.5, at + " notification button");
            assertTrue(r[11] <= 0.5, at + " top bar children overlap by " + r[11]);
            assertTrue(r[12] <= 0.5, at + " dock children overlap by " + r[12]);
            assertTrue(r[13] <= r[14] - 20 + 0.5, at + " dock content clipped: " + r[13] + " > " + (r[14] - 20));
        }
    }

    /** Maior sobreposição horizontal entre filhos visíveis consecutivos (0 = nenhuma). */
    private static double maxChildOverlap(javafx.scene.layout.Pane p) {
        double worst = 0;
        Bounds prev = null;
        for (Node n : p.getChildren()) {
            if (!n.isVisible() || !n.isManaged() || n.getLayoutBounds().getWidth() == 0) {
                continue;
            }
            Bounds b = n.getBoundsInParent();
            if (prev != null) {
                worst = Math.max(worst, prev.getMaxX() - b.getMinX());
            }
            prev = b;
        }
        return worst;
    }

    private static double lastChildMaxX(javafx.scene.layout.Pane p) {
        double max = 0;
        for (Node n : p.getChildren()) {
            max = Math.max(max, inScene(n).getMaxX());
        }
        return max;
    }

    @Test
    void realDockModelNeverOverflowsOrWraps() throws Exception {
        int[][] sizes = {{1280, 760}, {1440, 900}, {1600, 1000}, {1920, 1080}};
        for (int[] s : sizes) {
            Object[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(MotionPreference.OFF, s[0], s[1], false);
                panel.model.Snapshot snap = new panel.model.Snapshot();
                panel.model.TraderSnapshot trader = new panel.model.TraderSnapshot();
                trader.feed = "NOT_CONFIGURED";
                var network = panel.model.ByxSnapshot.unknown("cosmos", "UNKNOWN", "UNKNOWN", "Awaiting identity verification");
                f.shell.dock().setModel(panel.shell.DockModel.build(snap, trader, network, "Wallet unavailable", true, true));
                f.layout();
                f.layout(); // a compactação reaplica espaçamentos no passe seguinte
                Bounds dock = inScene(f.shell.dock());
                boolean truncated = f.shell.dock().lookupAll(".byx-dock-text").stream()
                        .map(n -> (javafx.scene.control.Label) n)
                        .anyMatch(l -> l.prefWidth(-1) > l.getWidth() + 0.5);
                Object[] out = {lastChildMaxX(f.shell.dock()), dock.getMaxX(), dock.getHeight(), f.shell.dock().compact(), truncated};
                f.close();
                return out;
            });
            String at = s[0] + "x" + s[1];
            assertTrue((double) r[0] <= (double) r[1] - 20 + 0.5, at + " dock content ends at " + r[0] + ", limit " + ((double) r[1] - 20));
            assertEquals(38, (double) r[2], 0.5, at + " dock stays one row");
            assertEquals(s[0] < 1440, r[3], at + " compact only below COMPACT (1440)");
            assertEquals(false, r[4], at + " no dock text is truncated with the real model");
        }
    }

    @Test
    void railAndSwitcherFollowTheRouteOnly() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF, 1440, 900);
            int desk = f.shell.rail().selectedIndex();
            ShellContext ws = f.shell.switcher().selected();
            f.router.request("t-bot");
            int bot = f.shell.rail().selectedIndex();
            f.router.request("t-byx");
            ShellContext byx = f.shell.switcher().selected();
            ShellContext railCtx = f.shell.rail().context();
            f.router.request("t-profile");
            ShellContext account = f.shell.switcher().selected();
            String chip = f.shell.topBar().contextChipText();
            f.router.request("t-strategies"); // fora do rail: nenhum item ativo
            int none = f.shell.rail().selectedIndex();
            f.close();
            return new Object[] {desk, ws, bot, byx, railCtx, account, chip, none};
        });
        assertEquals(0, r[0]);
        assertEquals(ShellContext.TRADING, r[1]);
        assertEquals(2, r[2]);
        assertEquals(ShellContext.BYX, r[3]);
        assertEquals(ShellContext.BYX, r[4]);
        assertNull(r[5], "ACCOUNT selects no workspace");
        assertEquals("ACCOUNT", r[6]);
        assertEquals(-1, r[7]);
    }

    @Test
    void railClickOnlyRequestsNavigation() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            List<String> requested = new ArrayList<>();
            // gate que nega tudo depois do primeiro: o clique pede, mas a rota não muda
            ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> {
                requested.add(t);
                return requested.size() == 1 ? ShellRouter.Decision.ALLOW : ShellRouter.Decision.DENY;
            }, id -> { });
            ByxShell shell = new ByxShell(router, new MotionService(), new LegacyHost());
            new Scene(shell, 1440, 900);
            router.request("t-desk");
            shell.rail().itemButtons().get(1).fire();
            Object[] out = {List.copyOf(requested), router.route(), shell.rail().selectedIndex()};
            shell.dispose();
            return out;
        });
        assertEquals(List.of("t-desk", "t-markets"), r[0]);
        assertEquals("t-desk", r[1], "denied request keeps the route");
        assertEquals(0, r[2], "rail selection follows the route, not the click");
    }

    @Test
    void indicatorsSlideOnlyInFullAndEndInTheSamePlace() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            Object[] r = FxSupport.fx(() -> {
                Fixture f = new Fixture(p, 1440, 900);
                f.router.request("t-orders");
                f.layout();
                boolean railSliding = f.shell.rail().sliding();
                f.router.request("t-byx"); // troca de workspace: rail salta, sublinhado desliza só em FULL
                f.layout();
                boolean switchSliding = f.shell.switcher().sliding();
                Object[] out = {railSliding, switchSliding, f.shell.rail().selectedIndex(), f.shell.switcher().selected(),
                        f.router.route()};
                f.close();
                return out;
            });
            boolean full = p == MotionPreference.FULL;
            assertEquals(full, r[0], p + " rail indicator slides");
            assertEquals(full, r[1], p + " switcher underline slides");
            assertEquals(0, r[2], p + " same logical rail state");
            assertEquals(ShellContext.BYX, r[3], p + " same logical workspace");
            assertEquals("t-byx", r[4], p + " same route");
        }
    }

    @Test
    void settledIndicatorPositionsAreIdenticalInEveryMode() throws Exception {
        double[][] settled = new double[3][];
        for (MotionPreference p : MotionPreference.values()) {
            Fixture f = FxSupport.fx(() -> {
                Fixture x = new Fixture(p, 1440, 900);
                x.router.request("t-orders");
                x.router.request("t-byx");
                x.router.request("t-treasury");
                x.layout();
                return x;
            });
            Thread.sleep(500); // > railIndicator/workspaceChange (200 ms)
            settled[p.ordinal()] = FxSupport.fx(() -> {
                f.layout();
                double[] out = {f.shell.rail().indicatorY(), f.shell.switcher().underlineX(), f.shell.rail().selectedIndex()};
                f.close();
                return out;
            });
        }
        assertEquals(java.util.Arrays.toString(settled[0]), java.util.Arrays.toString(settled[1]), "FULL vs REDUCED");
        assertEquals(java.util.Arrays.toString(settled[0]), java.util.Arrays.toString(settled[2]), "FULL vs OFF");
        assertEquals(3 * (58 + 4), settled[0][0], 0.01, "Treasury is the 4th BYX rail item");
    }

    @Test
    void dockUpdatesInPlaceAndNeverNavigates() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.FULL, 1440, 900);
            List<StatusDock.Group> a = model("Backend", StatusState.OPERATIONAL);
            f.shell.dock().setModel(a);
            int rebuilds = f.shell.dock().rebuilds();
            Button node = f.shell.dock().itemNode("backend");
            for (int i = 0; i < 20; i++) {
                f.shell.dock().setModel(model("Backend", StatusState.OPERATIONAL)); // igual: nada muda
            }
            f.shell.dock().setModel(model("Backend", StatusState.UNAVAILABLE)); // mesma estrutura: no lugar
            Object[] out = {rebuilds, f.shell.dock().rebuilds(), node == f.shell.dock().itemNode("backend"),
                    List.copyOf(f.routeChanges), f.shell.dock().itemNode("backend").isFocusTraversable(),
                    f.shell.dock().itemNode("network").isFocusTraversable()};
            f.shell.dock().itemNode("network").fire();
            Object route = f.router.route();
            f.close();
            return new Object[] {out[0], out[1], out[2], out[3], out[4], out[5], route};
        });
        assertEquals(1, r[0]);
        assertEquals(1, r[1], "equal or same-structure models never rebuild");
        assertTrue((boolean) r[2], "same node instance after updates");
        assertEquals(List.of("t-desk"), r[3], "status updates never navigate");
        assertFalse((boolean) r[4], "item without destination is not a link");
        assertTrue((boolean) r[5]);
        assertEquals("t-byx", r[6], "dock link requests navigation through the router");
    }

    private static List<StatusDock.Group> model(String backend, StatusState s) {
        return List.of(
                new StatusDock.Group("SYSTEM HEALTH", List.of(
                        StatusDock.Item.status("backend", backend, s, false, null, null),
                        StatusDock.Item.status("network", "Network", StatusState.UNKNOWN, false, "t-byx", null))),
                new StatusDock.Group("MODE", List.of(StatusDock.Item.text("live", "Live trading OFF", "primary", null, null))));
    }

    @Test
    void shortcutsRequestRailItemsButNotBehindADialog() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF, 1440, 900);
            Event.fireEvent(f.shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DIGIT3, false, false, false, true));
            String afterCmd3 = f.router.route();
            f.shell.overlay().openDialog(new Label("Session expired"), true, null, null);
            Event.fireEvent(f.shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DIGIT1, false, false, false, true));
            Event.fireEvent(f.shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.COMMA, false, false, false, true));
            String behindDialog = f.router.route();
            f.close();
            return new Object[] {afterCmd3, behindDialog};
        });
        assertEquals("t-bot", r[0]);
        assertEquals("t-bot", r[1], "no navigation behind a dialog");
    }

    @Test
    void notificationCountHiddenAtZero() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture(MotionPreference.OFF, 1440, 900);
            boolean zero = f.shell.topBar().unreadVisible();
            f.shell.topBar().setUnread(3);
            boolean three = f.shell.topBar().unreadVisible();
            String a11y = f.shell.topBar().notifications().getAccessibleText();
            f.close();
            return new boolean[] {zero, three, a11y.equals("Notifications, 3 unread")};
        });
        assertFalse(r[0]);
        assertTrue(r[1]);
        assertTrue(r[2]);
    }

    @Test
    void disposeReleasesTheRouterListener() throws Exception {
        ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
        KEEP_ROUTER = router; // vive mais que o shell, como no app (um shell por login)
        java.lang.ref.WeakReference<?> ref = FxSupport.fx(() -> {
            ByxShell shell = new ByxShell(router, new MotionService(), new LegacyHost());
            new Scene(shell, 1440, 900);
            router.request("t-desk");
            router.request("t-byx");
            shell.dispose();
            return new java.lang.ref.WeakReference<>(shell);
        });
        for (int i = 0; i < 30 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertSame(null, ref.get(), "router (longer-lived) must not retain a disposed shell");
        FxSupport.fx(() -> router.request("t-desk")); // roteador segue funcionando sem o shell
        assertEquals("t-desk", router.route());
    }

    /** Mantém o roteador vivo (como no app) enquanto o shell descartado é coletado. */
    private static ShellRouter KEEP_ROUTER;
}
