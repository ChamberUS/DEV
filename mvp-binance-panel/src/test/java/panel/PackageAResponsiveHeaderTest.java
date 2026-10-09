package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.design.OverlayLayer;
import panel.design.RegionState;
import panel.motion.*;
import panel.nav.Navigator;
import panel.shell.*;

class PackageAResponsiveHeaderTest {
    @Test void fullDestinationAndAccountActionsFitAtTheSupportedMinimumWithAllBadges() throws Exception {
        FxSupport.fx(()->{
            MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);
            var router=new ShellRouter(new Navigator(),(t,k)->ShellRouter.Decision.ALLOW,t->{});
            ByxShell shell=new ByxShell(router,m,new LegacyHost());Scene scene=new Scene(shell,1100,700);ByxTheme.apply(scene);
            shell.topBar().setAdminSession(true);shell.topBar().setMockData(true);
            for(String route:List.of("t-settings","t-profile","t-security","t-sessions","t-markets","overview","h-shortcuts")){
                router.request(route);shell.applyCss();shell.layout();scene.snapshot(null);
                var crumb=(Label)shell.topBar().lookup(".byx-crumb");
                assertTrue(crumb.getWidth()+1>=crumb.prefWidth(-1),route+" critical breadcrumb truncated");
                var avatar=shell.topBar().avatar();assertTrue(avatar.localToScene(avatar.getLayoutBounds()).getMaxX()<=1100);
                assertTrue(shell.topBar().search().getWidth()>=96);
            }
            shell.resize(1440,900);shell.layout();assertEquals(320,shell.topBar().search().getWidth(),1);shell.dispose();
        });
    }
    @Test void repeatedNotificationOpenPreservesOneLiveHonestSurfaceAndClosesCleanly() throws Exception {
        FxSupport.fx(()->{
            MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);var router=new ShellRouter(new Navigator(),(t,k)->ShellRouter.Decision.ALLOW,t->{});
            ByxShell shell=new ByxShell(router,m,new LegacyHost());Scene scene=new Scene(shell,1100,700);ByxTheme.apply(scene);shell.applyCss();shell.layout();
            NotificationPanel panel=new NotificationPanel(shell.overlay(),shell.topBar().notifications(),m);panel.open();var first=shell.overlay().layer(OverlayLayer.POPOVER).getChildren().getFirst();
            for(int i=0;i<30;i++)panel.open();assertTrue(panel.isOpen());assertSame(first,shell.overlay().layer(OverlayLayer.POPOVER).getChildren().getFirst());assertEquals(1,shell.overlay().openPopovers());assertEquals(RegionState.UNAVAILABLE,panel.state());
            panel.toggle();assertFalse(panel.isOpen());assertEquals(0,shell.overlay().openPopovers());panel.dispose();shell.dispose();
        });
    }
}
