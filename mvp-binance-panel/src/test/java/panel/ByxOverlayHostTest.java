package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import panel.design.ByxOverlayHost;
import panel.design.ByxOverlayHost.ToastKind;
import panel.design.ByxTheme;
import panel.design.OverlayLayer;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Camadas 30–80 (P3.13): ordem, Esc no topo, foco preso e devolvido, persistentes, toasts. */
class ByxOverlayHostTest {
    private record Fixture(ByxOverlayHost host, Button opener, Stage stage) {
    }

    private static Fixture fixture(MotionPreference p) {
        MotionService m = new MotionService();
        m.preference.set(p);
        Button opener = new Button("Open");
        ByxOverlayHost host = new ByxOverlayHost(new VBox(opener, new TextField()), m);
        Scene s = new Scene(host, 1440, 900);
        ByxTheme.apply(s);
        Stage stage = new Stage();
        stage.setScene(s);
        stage.show();
        opener.requestFocus();
        return new Fixture(host, opener, stage);
    }

    private static void esc(Node target) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    }

    private static void tab(Node target, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, shift, false, false, false));
    }

    private static void click(Node n) {
        Event.fireEvent(n, new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false, false,
                false, true, false, false, true, false, false, null));
    }

    private static Region panel(String text) {
        Button a = new Button(text + " A");
        Button b = new Button(text + " B");
        VBox v = new VBox(a, b);
        v.getStyleClass().add("byx-dialog");
        v.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return v;
    }

    @Test
    void layersAreStackedByToken() throws Exception {
        int[] order = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.OFF);
            int[] idx = new int[OverlayLayer.values().length];
            for (OverlayLayer l : OverlayLayer.values()) {
                idx[l.ordinal()] = f.host().getChildren().indexOf(f.host().layer(l));
            }
            f.stage().close();
            return idx;
        });
        for (int i = 1; i < order.length; i++) {
            assertTrue(order[i] > order[i - 1], "layer order " + OverlayLayer.values()[i]);
        }
        assertEquals(30, OverlayLayer.SAVEBAR.z());
        assertEquals(80, OverlayLayer.TOASTS.z());
    }

    @Test
    void escClosesOnlyTheTopLayerInEveryMode() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            Object[] r = FxSupport.fx(() -> {
                Fixture f = fixture(p);
                ByxOverlayHost h = f.host();
                h.openPalette(panel("Palette"), null);
                h.openDialog(panel("Dialog"), false, null, null);
                OverlayLayer t0 = h.topLayer();
                esc(h);
                OverlayLayer t1 = h.topLayer();
                esc(h);
                OverlayLayer t2 = h.topLayer();
                f.stage().close();
                return new Object[] {t0, t1, t2};
            });
            assertEquals(OverlayLayer.DIALOG, r[0], p.name());
            assertEquals(OverlayLayer.PALETTE, r[1], p + " dialog closed, palette stays");
            assertNull(r[2], p.name());
        }
    }

    @Test
    void openingAnyLayerClosesPopovers() throws Exception {
        int[] r = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.FULL);
            ByxOverlayHost h = f.host();
            h.openPopover(panel("Menu"), 10, 10);
            int before = h.openPopovers();
            h.openPalette(panel("Palette"), null);
            int afterPalette = h.openPopovers();
            h.openPopover(panel("Menu"), 10, 10);
            h.openDialog(panel("Dialog"), false, null, null);
            int afterDialog = h.openPopovers();
            f.stage().close();
            return new int[] {before, afterPalette, afterDialog};
        });
        assertEquals(1, r[0]);
        assertEquals(0, r[1]);
        assertEquals(0, r[2]);
    }

    @Test
    void persistentDialogIgnoresEscAndBackdrop() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.OFF);
            ByxOverlayHost h = f.host();
            h.openPalette(panel("Palette"), null);
            var d = h.openDialog(panel("Session expired"), true, null, null);
            esc(h);
            click(h.layer(OverlayLayer.DIALOG).lookup(".byx-backdrop"));
            boolean stillOpen = d.isOpen();
            boolean paletteUntouched = h.paletteOpen();
            d.close();
            f.stage().close();
            return new boolean[] {stillOpen, paletteUntouched};
        });
        assertTrue(r[0], "Esc and backdrop do nothing");
        assertTrue(r[1], "Esc does not leak to the layer below");
    }

    @Test
    void backdropCancelsNormalDialog() throws Exception {
        int cancels = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.OFF);
            AtomicInteger n = new AtomicInteger();
            ByxOverlayHost h = f.host();
            h.openDialog(panel("Dialog"), false, null, n::incrementAndGet);
            click(h.layer(OverlayLayer.DIALOG).lookup(".byx-backdrop"));
            f.stage().close();
            return n.get() * 10 + h.openDialogs();
        });
        assertEquals(10, cancels);
    }

    @Test
    void focusIsTrappedAndReturnsToOpener() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            Object[] r = FxSupport.fx(() -> {
                Fixture f = fixture(p);
                ByxOverlayHost h = f.host();
                Region dlg = panel("Dialog");
                var d = h.openDialog(dlg, false, null, null);
                Node first = f.stage().getScene().getFocusOwner();
                tab(h, false);
                Node second = f.stage().getScene().getFocusOwner();
                tab(h, false);
                Node wrapped = f.stage().getScene().getFocusOwner();
                tab(h, true);
                Node back = f.stage().getScene().getFocusOwner();
                f.opener().requestFocus(); // tentativa de sair do diálogo
                Node stillInside = f.stage().getScene().getFocusOwner();
                d.close();
                Node returned = f.stage().getScene().getFocusOwner();
                f.stage().close();
                return new Object[] {first, second, wrapped, back, stillInside, returned, dlg.getChildrenUnmodifiable().get(0),
                        dlg.getChildrenUnmodifiable().get(1), f.opener()};
            });
            assertSame(r[6], r[0], p + " first focusable");
            assertSame(r[7], r[1], p + " tab");
            assertSame(r[6], r[2], p + " tab wraps");
            assertSame(r[7], r[3], p + " shift+tab wraps");
            assertSame(r[6], r[4], p + " focus cannot leave the dialog");
            assertSame(r[8], r[5], p + " focus returns to opener");
        }
    }

    @Test
    void destructiveConfirmFocusesCancel() throws Exception {
        String[] r = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.FULL);
            f.host().confirm("Delete session", "This signs the device out.", "Sign out device", true, () -> { });
            Node focus = f.stage().getScene().getFocusOwner();
            var buttons = f.host().layer(OverlayLayer.DIALOG).lookupAll(".byx-btn").stream().toList();
            String dangerClass = buttons.stream().anyMatch(b -> b.getStyleClass().contains("danger")) ? "danger" : "none";
            f.stage().close();
            return new String[] {((Button) focus).getText(), dangerClass};
        });
        assertEquals("Cancel", r[0]);
        assertEquals("danger", r[1]);
    }

    @Test
    void toastsCapAtThreeAndNeverTakeFocus() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.OFF);
            ByxOverlayHost h = f.host();
            Node focusBefore = f.stage().getScene().getFocusOwner();
            Node first = h.toast(ToastKind.SUCCESS, "Saved");
            h.toast(ToastKind.INFO, "Two");
            h.toast(ToastKind.WARNING, "Three");
            h.toast(ToastKind.ERROR, "Four");
            Node focusAfter = f.stage().getScene().getFocusOwner();
            boolean firstGone = first.getParent() == null;
            f.stage().close();
            return new Object[] {h.visibleToasts(), firstGone, focusBefore == focusAfter};
        });
        assertEquals(3, r[0]);
        assertTrue((boolean) r[1], "4th toast removes the oldest immediately");
        assertTrue((boolean) r[2], "toasts never take focus");
    }

    @Test
    void toastTimingsComeFromMotionTokens() throws Exception {
        var m = ByxOverlayHost.class.getDeclaredMethod("autoDismiss", ToastKind.class);
        m.setAccessible(true);
        assertEquals(Duration.millis(4000), m.invoke(null, ToastKind.SUCCESS));
        assertEquals(Duration.millis(4000), m.invoke(null, ToastKind.INFO));
        assertEquals(Duration.millis(6000), m.invoke(null, ToastKind.WARNING));
        assertNull(m.invoke(null, ToastKind.ERROR), "error stays until dismissed");
    }

    @Test
    void deferredFocusIsDroppedIfALayerOpened() throws Exception {
        Fixture f = FxSupport.fx(() -> fixture(MotionPreference.OFF));
        TextField field = FxSupport.fx(() -> (TextField) f.host().lookup(".text-field"));
        FxSupport.fx(() -> {
            f.host().requestFocusDeferred(field);
            f.host().openDialog(panel("Dialog"), true, null, null);
        });
        FxSupport.fx(() -> { });
        boolean landedBehind = FxSupport.fx(field::isFocused);
        FxSupport.fx(() -> f.stage().close());
        assertFalse(landedBehind, "deferred focus must not land behind a dialog");
    }

    @Test
    void closingThePaletteReturnsFocusToItsOpenerInEveryMode() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            boolean[] r = FxSupport.fx(() -> {
                Fixture f = fixture(p);
                f.opener().requestFocus();
                Region panel = panel("Palette");
                f.host().openPalette(panel, null);
                ((Button) panel.getChildrenUnmodifiable().get(0)).requestFocus();
                esc(f.stage().getScene().getFocusOwner());
                Node owner = f.stage().getScene().getFocusOwner();
                boolean back = owner == f.opener();
                esc(owner); // a próxima tecla ainda chega ao host (foco não ficou órfão)
                f.stage().close();
                return new boolean[] {back, f.host().paletteOpen()};
            });
            assertTrue(r[0], p + " focus returns to the opener");
            assertFalse(r[1], p.name());
        }
    }

    @Test
    void closeIsLogicalImmediatelyEvenWhileAnimating() throws Exception {
        int[] r = FxSupport.fx(() -> {
            Fixture f = fixture(MotionPreference.FULL);
            ByxOverlayHost h = f.host();
            var d = h.openDialog(panel("Dialog"), false, null, null);
            d.close();
            d.close(); // idempotente
            int logical = h.openDialogs();
            OverlayLayer top = h.topLayer();
            f.stage().close();
            return new int[] {logical, top == null ? 0 : 1};
        });
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
    }
}
