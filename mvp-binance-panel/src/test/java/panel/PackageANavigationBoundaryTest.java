package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.Map;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import panel.security.Role;
import panel.shell.*;
import panel.ui.View;
import panel.user.User;

/** Real PanelApp with synthetic authority and isolated files; no Service authorization overrides. */
class PackageANavigationBoundaryTest {
    private static String originalHome;
    private ResearchGateReproductionTest.App app;
    @BeforeAll static void sandbox() throws Exception {
        FxSupport.start();originalHome=System.getProperty("user.home");var home=Files.createTempDirectory("byx-package-a-nav-");
        var settings=SecureTempDirFactory.directory(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("settings.properties"),"dataSource=REAL\nprojectPath="+home.resolve("empty")+"\ncliPath=/usr/bin/false\nmotion=OFF\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home",home.toString());
    }
    @AfterAll static void restore(){System.setProperty("user.home",originalHome);}
    @AfterEach void close() throws Exception {if(app!=null)FxSupport.fx(()->{app.stop();((Stage)app.field("stage")).close();});}
    private void open() throws Exception {
        app=FxSupport.fx(ResearchGateReproductionTest.App::new);app.init();
        // Settings.FILE is cached by an earlier suite. This fixture is explicitly post-onboarding;
        // set its non-authority UX state directly instead of relying on a later user.home change.
        app.ctx().settings.onboardingCompleted = true;
        FxSupport.fx(()->{app.start(new Stage());app.authority.add("package-a-user","user@example.invalid",null,"synthetic-pass-1",Role.USER,false);
            User user=app.ctx().auth.login("package-a-user","synthetic-pass-1".toCharArray());app.invoke("afterLogin",User.class,user);});
    }
    private void key(KeyCode code, boolean shortcut, boolean shift){Stage s=app.field("stage");Node target=s.getScene().getFocusOwner();if(target==null)target=s.getScene().getRoot();boolean mac=ByxShell.isMac();Event.fireEvent(target,new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,shift,shortcut&&!mac,false,shortcut&&mac));}
    @Test void sceneShortcutCannotOpenPaletteUnderModalAndOpensExactlyOneAfterDismissal() throws Exception {
        open();FxSupport.fx(()->{ByxShell shell=app.field("shell");ShellPalette palette=app.field("palette");
            shell.overlay().confirm("QA modal","Synthetic only","OK",false,()->{});key(KeyCode.K,true,false);assertFalse(palette.isOpen());assertEquals(1,shell.overlay().openDialogs());
            key(KeyCode.ESCAPE,false,false);key(KeyCode.K,true,false);key(KeyCode.K,true,false);assertTrue(palette.isOpen());assertEquals(2,shell.overlay().layer(panel.design.OverlayLayer.PALETTE).getChildren().size());
            key(KeyCode.ESCAPE,false,false);assertFalse(palette.isOpen());
        });
    }
    @Test void directNavigationClosesMenusAndKeepsBreadcrumbAndAvatarBoundToNewRoute() throws Exception {
        open();FxSupport.fx(()->{ByxShell shell=app.field("shell");UserMenu menu=app.field("userMenu");var mascot=shell.topBar().mascot();app.show("t-desk");menu.open(true);assertTrue(menu.isOpen());
            for(String route:new String[]{"t-home","t-desk","t-markets","t-orders","t-wallet","t-byx","t-chain-data","t-benefits","t-profile","t-security","t-settings","h-help","h-faq"}){
                app.show(route);assertEquals(route,app.router().route());assertFalse(menu.isOpen());assertSame(mascot,shell.topBar().mascot());assertEquals(3,mascot.sceneFilterCount());
            }
        });
    }
    @Test void replacingAnUnsavedPromptMakesOlderDecisionsInert() throws Exception {
        open();FxSupport.fx(()->{var dirty=new PackageBFlowQa.DirtyView();Map<String,View> views=app.field("views");ByxShell shell=app.field("shell");
            views.put("t-sessions",dirty);shell.v2Content().getChildren().add(dirty.node());app.show("t-sessions");app.show("t-home");NavigationGuard.Handle old=app.field("leaveGuard");
            app.show("t-settings");NavigationGuard.Handle current=app.field("leaveGuard");assertNotSame(old,current);assertEquals(1,shell.overlay().openDialogs());
            old.discard().fire();old.save().fire();assertEquals(0,dirty.discarded);assertEquals(0,dirty.saveCalls);assertEquals("t-sessions",app.router().route());
            current.discard().fire();assertEquals(1,dirty.discarded);assertEquals("t-settings",app.router().route());assertEquals(0,shell.overlay().openDialogs());
        });
    }
}
