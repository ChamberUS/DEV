package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.*;
import panel.motion.*;
import panel.nav.Navigator;
import panel.shell.*;

class PackageAOverlayLifecycleTest {
    @Test void closedAndReplacedGuardCannotSaveDiscardOrNavigate() throws Exception {
        FxSupport.fx(() -> {
            MotionService m=new MotionService();m.preference.set(MotionPreference.FULL);
            ByxOverlayHost h=new ByxOverlayHost(new Button("Open"),m);Scene scene=new Scene(h,1100,700);ByxTheme.apply(scene);
            AtomicInteger actions=new AtomicInteger();
            var old=NavigationGuard.open(h,m,2,true,actions::incrementAndGet,actions::incrementAndGet,()->{actions.incrementAndGet();return true;},actions::incrementAndGet);
            old.dialog().close();var current=NavigationGuard.open(h,m,1,true,actions::incrementAndGet,actions::incrementAndGet,()->true,actions::incrementAndGet);
            old.discard().fire();old.save().fire();old.stay().fire();assertEquals(0,actions.get());assertEquals(1,h.openDialogs());
            current.stay().fire();assertEquals(1,actions.get());h.dispose();
        });
    }
    @Test void reopenedSaveBarIsInteractiveAndHasNoDuplicateNodeDuringMotion() throws Exception {
        FxSupport.fx(() -> {
            for(MotionPreference mode:MotionPreference.values()) {
                MotionService m=new MotionService();m.preference.set(mode);ByxOverlayHost h=new ByxOverlayHost(new Button(),m);
                VBox bar=new VBox(new Button("Save"));new Scene(h,1100,700);
                for(int i=0;i<20;i++){h.showSaveBar(bar);h.hideSaveBar();h.showSaveBar(bar);assertFalse(bar.isMouseTransparent());assertEquals(1,h.layer(OverlayLayer.SAVEBAR).getChildren().size());}
                h.dispose();assertTrue(h.layer(OverlayLayer.SAVEBAR).getChildren().isEmpty());assertFalse(bar.getProperties().containsKey("byx.overlay.anim"));
            }
        });
    }
    @Test void disposalClosesCallbacksOnceStopsNestedAnimationsAndDeferredFocus() throws Exception {
        FxSupport.fx(() -> {
            MotionService m=new MotionService();m.preference.set(MotionPreference.FULL);Button opener=new Button("Open");
            ByxOverlayHost h=new ByxOverlayHost(opener,m);Scene scene=new Scene(h,1100,700);ByxTheme.apply(scene);AtomicInteger closed=new AtomicInteger();
            VBox pop=new VBox(new Button("Item"));h.openPopover(pop,100,100,closed::incrementAndGet);h.openPalette(new VBox(new Button("Search")),closed::incrementAndGet);
            var panel=new VBox(new Button("Cancel"));var dialog=h.openDialog(panel,false,null,null);h.toast(ByxOverlayHost.ToastKind.INFO,"Synthetic QA");
            h.requestFocusDeferred(opener);h.dispose();h.dispose();assertEquals(2,closed.get());assertFalse(dialog.isOpen());
            assertFalse(panel.getProperties().containsKey("byx.overlay.anim"));for(OverlayLayer l:OverlayLayer.values()) assertTrue(h.layer(l).lookupAll(".button").isEmpty());
        });
    }
    @Test void headerPopoverFollowsItsOwnerAndRemainsInsideEachResizedViewport() throws Exception {
        FxSupport.fx(() -> {
            MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);var router=new ShellRouter(new Navigator(),(t,k)->ShellRouter.Decision.ALLOW,t->{});
            ByxShell shell=new ByxShell(router,m,new LegacyHost());Scene scene=new Scene(shell,1920,1080);ByxTheme.apply(scene);shell.applyCss();shell.layout();
            UserMenu menu=new UserMenu(shell.overlay(),shell.topBar().avatar(),router);menu.setItems(List.of(UserMenu.Item.route("Home","home",null,"t-home")));menu.open(true);
            for(int[] size:new int[][]{{1440,900},{1100,700},{1920,1080}}){shell.resize(size[0],size[1]);shell.layout();var node=shell.overlay().layer(OverlayLayer.POPOVER).getChildren().getFirst();
                var bounds=node.localToParent(node.getLayoutBounds());assertTrue(bounds.getMinX()>=0);assertTrue(bounds.getMaxX()<=shell.getWidth());assertTrue(bounds.getMaxY()<=shell.getHeight());
                var anchor=shell.overlay().sceneToLocal(shell.topBar().avatar().localToScene(shell.topBar().avatar().getLayoutBounds()));
                assertEquals(anchor.getMaxX(),node.getLayoutX()+node.getLayoutBounds().getWidth(),1.0);
            }
            menu.dispose();shell.dispose();
        });
    }
    @Test void disposedShellCannotHandleKeysOrKeepAccountMenuActions() throws Exception {
        FxSupport.fx(() -> {
            AtomicInteger actions=new AtomicInteger();MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);var r=new ShellRouter(new Navigator(),(t,k)->{actions.incrementAndGet();return ShellRouter.Decision.ALLOW;},t->{});
            ByxShell shell=new ByxShell(r,m,new LegacyHost());Scene scene=new Scene(shell,1100,700);ByxTheme.apply(scene);Stage stage=new Stage();stage.setScene(scene);stage.show();
            UserMenu menu=new UserMenu(shell.overlay(),shell.topBar().avatar(),r);menu.setItems(List.of(UserMenu.Item.route("Home","home",null,"t-home")));menu.open(true);
            menu.dispose();shell.dispose();shell.topBar().avatar().fire();boolean mac=ByxShell.isMac();Event.fireEvent(shell,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.COMMA,false,!mac,false,mac));
            assertEquals(0,actions.get());assertFalse(menu.isOpen());assertNull(shell.topBar().avatar().getOnAction());stage.close();
        });
    }
    @Test void reentrantGateCannotCommitAnOlderAllowedRouteOverANewerRequest() {
        ShellRouter[] r={null};java.util.ArrayList<String> displayed=new java.util.ArrayList<>();
        r[0]=new ShellRouter(new Navigator(),(t,k)->{if(t.equals("old"))r[0].request("new");return ShellRouter.Decision.ALLOW;},displayed::add);
        r[0].request("old");assertEquals("new",r[0].route());assertEquals(List.of("new"),displayed);
    }
}
