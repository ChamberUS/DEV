package panel.wallet;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import panel.tradeview.DeskHarness;
import static org.junit.jupiter.api.Assertions.*;

class WalletPaneTest {
    @Test void gatewayNeverBlocksFxAndUserHasNoDiagnosticActions() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        var gateway=new WalletGateway(WalletControllerTest.gateway(()->{
            assertFalse(javafx.application.Platform.isFxApplicationThread());started.countDown();
            try { assertTrue(release.await(5,TimeUnit.SECONDS)); }catch(InterruptedException e){throw new AssertionError(e);}
            return WalletControllerTest.ok(WalletControllerTest.view(0,"NO_WALLET"));
        },()->null));
        WalletPane pane=DeskHarness.fx(()->{var p=new WalletPane(gateway,()->false);new Scene(new StackPane(p),1920,1080);p.onShow();return p;});
        assertTrue(started.await(5,TimeUnit.SECONDS));
        DeskHarness.fx(()->{assertTrue(javafx.application.Platform.isFxApplicationThread());pane.onHide();});
        release.countDown();
        DeskHarness.fx(()->{
            for(var node:pane.lookupAll(".button"))if(node instanceof Button b && b.getText().contains("synthetic"))assertFalse(b.getParent().isVisible());
        });
    }
    @Test void walletConfirmationDefaultsToCancelRejectsDuplicatesAndClosesWhenHidden() throws Exception {
        DeskHarness.fx(() -> {
            var pane = new WalletPane(new WalletGateway(WalletControllerTest.gateway(
                    () -> WalletControllerTest.ok(WalletView.disabled()), () -> null)), () -> true);
            var stage = new javafx.stage.Stage(); stage.setScene(new Scene(new StackPane(pane), 1100, 700));
            panel.design.ByxTheme.apply(stage.getScene()); stage.show();
            pane.onShow();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            try {
                var confirm = WalletPane.class.getDeclaredMethod("confirm", String.class, String.class); confirm.setAccessible(true);
                var field = WalletPane.class.getDeclaredField("confirmation"); field.setAccessible(true);
                javafx.application.Platform.runLater(() -> {
                    try {
                        var alert = (javafx.scene.control.Alert) field.get(pane);
                        assertNotNull(alert); assertTrue(alert.isShowing());
                        var cancel = (Button) alert.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL);
                        assertTrue(cancel.isDefaultButton());
                        assertFalse(((Button) alert.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK)).isDefaultButton());
                        assertEquals(cancel, alert.getDialogPane().getScene().getFocusOwner());
                        assertEquals(false, confirm.invoke(pane, "Duplicate", "Must not open another confirmation"));
                        String out = System.getProperty("byx.qa.out");
                        if (out != null) {
                            var file = java.nio.file.Path.of(out, "wallet-confirmation.png"); java.nio.file.Files.createDirectories(file.getParent());
                            var image = alert.getDialogPane().getScene().snapshot(null);
                            javax.imageio.ImageIO.write(panel.ControlGalleryTestAccess.toAwt(image), "png", file.toFile());
                        }
                    } catch (Throwable error) { failure.set(error); }
                    finally { pane.onHide(); }
                });
                assertEquals(false, confirm.invoke(pane, "Delete synthetic QA wallet", "Deletion is irreversible in QA. No recovery."));
                assertNull(failure.get());
                assertNull(field.get(pane));
                assertEquals(false, confirm.invoke(pane, "Hidden", "Hidden view cannot confirm"));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { pane.onHide(); stage.close(); }
        });
    }
    @Test void defaultStartsWithoutDiagnosticActions() throws Exception {
        DeskHarness.fx(()->{
            var pane=new WalletPane(new WalletGateway(WalletControllerTest.gateway(()->WalletControllerTest.ok(WalletView.disabled()),()->null)),()->true);
            var scene=new Scene(new StackPane(pane),1920,1080);panel.design.ByxTheme.apply(scene);scene.getRoot().applyCss();scene.getRoot().layout();
            for(var node:pane.lookupAll(".button"))if(node instanceof Button b && b.getText().contains("synthetic"))assertFalse(b.getParent().isVisible());
            pane.onShow();pane.onHide();
        });
    }
}
