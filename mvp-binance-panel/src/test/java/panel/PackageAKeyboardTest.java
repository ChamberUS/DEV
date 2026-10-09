package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.*;

class PackageAKeyboardTest {
    private static void key(javafx.scene.Node target, KeyCode code, boolean shortcut, boolean shift) {
        boolean mac=ByxShell.isMac();Event.fireEvent(target,new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,shift,shortcut&&!mac,false,shortcut&&mac));
    }
    @Test void documentedShortcutsFireOnceAndRespectDialogsAndTyping() throws Exception {
        FxSupport.fx(() -> {
            List<String> requests=new ArrayList<>();int[] searches={0};
            ShellRouter router=new ShellRouter(new Navigator(),(t,k)->{requests.add(t);return ShellRouter.Decision.ALLOW;},t->{});
            MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);
            ByxShell shell=new ByxShell(router,motion,new LegacyHost());TextField field=new TextField();shell.v2Content().getChildren().add(field);shell.showV2(true);
            Scene scene=new Scene(shell,1100,700);ByxTheme.apply(scene);Stage stage=new Stage();stage.setScene(scene);stage.show();shell.setOnOpenSearch(()->searches[0]++);
            key(field,KeyCode.K,true,false);assertEquals(1,searches[0]);key(field,KeyCode.COMMA,true,false);assertEquals(List.of("t-settings"),requests);
            key(field,KeyCode.H,true,true);assertEquals("t-home",router.route());key(field,KeyCode.H,true,false);assertEquals(2,requests.size());
            var handle=shell.overlay().confirm("Confirm","Dialog","OK",false,()->{});
            key(scene.getFocusOwner(),KeyCode.K,true,false);key(scene.getFocusOwner(),KeyCode.COMMA,true,false);key(scene.getFocusOwner(),KeyCode.H,true,true);
            assertEquals(1,searches[0]);assertEquals(2,requests.size());handle.close();
            field.requestFocus();Event.fireEvent(field,new KeyEvent(KeyEvent.KEY_TYPED,"?","?",KeyCode.UNDEFINED,false,false,false,false));assertEquals(0,shell.overlay().openDialogs());
            shell.rail().logoButton().requestFocus();Event.fireEvent(shell.rail().logoButton(),new KeyEvent(KeyEvent.KEY_TYPED,"?","?",KeyCode.UNDEFINED,false,false,false,false));
            assertEquals(1,shell.overlay().openDialogs());key(scene.getFocusOwner(),KeyCode.ESCAPE,false,false);assertEquals(0,shell.overlay().openDialogs());
            shell.dispose();stage.close();
        });
    }
    @Test void paletteArrowsEnterAndTabStayWithinTheSurfaceAndBlockedRowsNeverActivate() throws Exception {
        FxSupport.fx(() -> {
            List<String> requests=new ArrayList<>();MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);
            Button opener=new Button("Open");var host=new panel.design.ByxOverlayHost(opener,m);Scene scene=new Scene(host,1100,700);ByxTheme.apply(scene);
            Stage stage=new Stage();stage.setScene(scene);stage.show();opener.requestFocus();
            ShellPalette palette=new ShellPalette(host,requests::add,()->List.of(ShellPalette.Entry.nav("Home","t-home",null),ShellPalette.Entry.gated(ShellPalette.Group.NAVIGATION,"Research","Admin required"),ShellPalette.Entry.nav("Settings","t-settings",null)));
            palette.open();key(scene.getFocusOwner(),KeyCode.DOWN,false,false);assertTrue(palette.selection().blocked());key(scene.getFocusOwner(),KeyCode.ENTER,false,false);assertTrue(requests.isEmpty());assertTrue(palette.isOpen());
            key(scene.getFocusOwner(),KeyCode.DOWN,false,false);key(scene.getFocusOwner(),KeyCode.TAB,false,false);assertFalse(scene.getFocusOwner()==opener);
            key(scene.getFocusOwner(),KeyCode.TAB,false,true);assertFalse(scene.getFocusOwner()==opener);key(scene.getFocusOwner(),KeyCode.ENTER,false,false);
            assertEquals(List.of("t-settings"),requests);assertFalse(palette.isOpen());assertSame(opener,scene.getFocusOwner());
            var dialog=host.confirm("Modal","Text","OK",false,()->{});palette.open();assertFalse(palette.isOpen());dialog.close();host.dispose();stage.close();
        });
    }
    @Test void accountMenuEnterActivatesOnceAndEscapeReturnsToTheAnchoredAvatar() throws Exception {
        FxSupport.fx(() -> {
            List<String> requests=new ArrayList<>();ShellRouter router=new ShellRouter(new Navigator(),(t,k)->{requests.add(t);return ShellRouter.Decision.ALLOW;},t->{});
            MotionService m=new MotionService();m.preference.set(MotionPreference.OFF);ByxShell shell=new ByxShell(router,m,new LegacyHost());Scene scene=new Scene(shell,1100,700);ByxTheme.apply(scene);Stage stage=new Stage();stage.setScene(scene);stage.show();
            UserMenu menu=new UserMenu(shell.overlay(),shell.topBar().avatar(),router);menu.setItems(List.of(UserMenu.Item.route("Settings","settings",null,"t-settings"),UserMenu.Item.route("Home","home",null,"t-home")));
            key(shell.topBar().avatar(),KeyCode.DOWN,false,false);assertTrue(menu.isOpen());key(scene.getFocusOwner(),KeyCode.DOWN,false,false);key(scene.getFocusOwner(),KeyCode.ENTER,false,false);
            assertEquals(List.of("t-home"),requests);assertFalse(menu.isOpen());assertSame(shell.topBar().avatar(),scene.getFocusOwner());
            menu.open(true);key(scene.getFocusOwner(),KeyCode.ESCAPE,false,false);assertFalse(menu.isOpen());assertSame(shell.topBar().avatar(),scene.getFocusOwner());shell.dispose();stage.close();
        });
    }
}
