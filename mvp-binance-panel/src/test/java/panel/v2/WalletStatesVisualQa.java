package panel.v2;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import panel.wallet.*;
import panel.motion.*;
import panel.model.CaptureSnapshot;
import panel.researchview.CapturePanel;
import panel.tradeview.DeskHarness;

/** Manual presentation-only fixtures. No Service, filesystem dataset, custody, or network calls. */
class WalletStatesVisualQa {
    @Test void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                var method = WalletPane.class.getDeclaredMethod("render", WalletController.State.class);
                method.setAccessible(true);
                var wrap = Class.forName("panel.researchview.V2Scroll").getDeclaredMethod("wrap", javafx.scene.Node.class);
                wrap.setAccessible(true);
                for (var mode : MotionPreference.values()) {
                    var motion = QaShots.motion(mode);
                    for (int width : new int[]{1100,1920}) {
                        for (String state : List.of("NO_WALLET","LOADING","CREATING","READY","LOCKED","SIGNER_UNAVAILABLE",
                                "DEGRADED","NEEDS_ATTENTION","OPERATION_PENDING","UNKNOWN_RESULT","DELETING","DELETED","UNAVAILABLE","STALE_RESPONSE")) {
                            boolean empty = state.equals("NO_WALLET") || state.equals("UNAVAILABLE");
                            var wallets = empty ? List.<WalletView.Wallet>of() : List.of(new WalletView.Wallet("synthetic-wallet-"+"a".repeat(32),
                                    "byx1"+"q".repeat(110),"public-only",1,state.equals("DELETED")?"DELETED":"READY",
                                    state.equals("NEEDS_ATTENTION")?"KEY_MISMATCH":"HEALTHY","LOCAL_ONLY_NO_RECOVERY",0,0));
                            var view = new WalletView(1,"a".repeat(32),5,"SYNTHETIC_QA",state,"AVAILABLE",wallets,List.of(),
                                    new WalletView.AllowedActions(false,false,false));
                            var pane = new WalletPane(new WalletGateway((panel.localservice.AuthorityGateway)null),()->false);
                            method.invoke(pane,new WalletController.State(state,view,List.of("LOADING","CREATING","DELETING","OPERATION_PENDING").contains(state),
                                    state.equals("UNKNOWN_RESULT")?"Result unknown / reconciliation required. Refresh only; do not repeat the operation.":""));
                            QaShots.shoot("t-wallet",pane,motion,"wallet-"+state+"-"+mode+"-"+width,width,width==1920?1080:700);
                            pane.onHide();
                        }
                        for (var state : CaptureSnapshot.State.values()) {
                            var now=Instant.parse("2026-10-08T12:00:00Z");
                            var pane=new CapturePanel(motion,Clock.fixed(now,ZoneOffset.UTC));
                            pane.show(new CaptureSnapshot(state,42L,state==CaptureSnapshot.State.RUNNING,now.minusSeconds(90000),
                                    "synthetic-campaign-"+"x".repeat(80),now.minusSeconds(3600),"ETHUSDT","USD-M Futures",now,now,
                                    "/synthetic/read-only/"+"long-path/".repeat(20),1024L,4096L,8192L,now,
                                    state==CaptureSnapshot.State.UNKNOWN?List.of("Observation unavailable; no state inferred."):List.of()));
                            QaShots.shoot("capture",(javafx.scene.Node) wrap.invoke(null,pane),motion,"capture-"+state+"-"+mode+"-"+width,width,width==1920?1080:700);
                            pane.stop();
                        }
                    }
                }
            } catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
