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
    @Test void defaultStartsWithoutDiagnosticActions() throws Exception {
        DeskHarness.fx(()->{
            var pane=new WalletPane(new WalletGateway(WalletControllerTest.gateway(()->WalletControllerTest.ok(WalletView.disabled()),()->null)),()->true);
            var scene=new Scene(new StackPane(pane),1920,1080);panel.design.ByxTheme.apply(scene);scene.getRoot().applyCss();scene.getRoot().layout();
            for(var node:pane.lookupAll(".button"))if(node instanceof Button b && b.getText().contains("synthetic"))assertFalse(b.getParent().isVisible());
            pane.onShow();pane.onHide();
        });
    }
}
