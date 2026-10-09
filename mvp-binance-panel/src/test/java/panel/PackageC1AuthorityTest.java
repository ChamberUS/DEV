package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import panel.i18n.*;
import panel.security.Role;
import panel.shell.ByxShell;
import panel.user.User;

class PackageC1AuthorityTest {
    static String oldHome;ResearchGateReproductionTest.App app;
    @BeforeAll static void isolate() throws Exception {FxSupport.start();oldHome=System.getProperty("user.home");Path home=Files.createTempDirectory("byx-c1-authority-");Path settings=Files.createDirectories(home.resolve(".mvp-binance-panel"));Files.writeString(settings.resolve("settings.properties"),"projectPath="+home.resolve("empty")+"\ncliPath=/usr/bin/false\nmotion=OFF\nonboardingCompleted=true\n");System.setProperty("user.home",home.toString());}
    @AfterAll static void restore(){System.setProperty("user.home",oldHome);}
    @AfterEach void close() throws Exception {FxSupport.fx(()->{if(app!=null){app.stop();((Stage)app.field("stage")).close();}Strings.resetSession();});}
    void login(Role role)throws Exception{app=FxSupport.fx(ResearchGateReproductionTest.App::new);app.init();FxSupport.fx(()->{app.start(new Stage());app.authority.add("c1-test","user@example.invalid","+5511999991234","synthetic-c1-pass-1",role,false);User user=app.ctx().auth.login("c1-test","synthetic-c1-pass-1".toCharArray());app.invoke("afterLogin",User.class,user);});}
    @Test void localeNeverGrantsUserResearchAccessOrChangesSession()throws Exception{login(Role.USER);FxSupport.fx(()->{var session=app.ctx().sessions.user().orElseThrow();var market=app.ctx().market;ByxShell shell=app.field("shell");var mascot=shell.topBar().mascot();for(Strings.Lang lang:Strings.Lang.values()){var before=app.ctx().sessions.user().orElseThrow();Strings.use(lang);assertEquals(before.id(),app.ctx().sessions.user().orElseThrow().id());assertEquals(before.loggedInAt(),app.ctx().sessions.user().orElseThrow().loggedInAt());app.show("overview");assertNotEquals("overview",app.router().route());assertFalse(app.ctx().adminAccess.hasValidAdminSession());assertEquals(session.id(),app.ctx().sessions.user().orElseThrow().id());assertEquals(session.user().id(),app.ctx().sessions.user().orElseThrow().user().id());assertSame(market,app.ctx().market);assertSame(mascot,shell.topBar().mascot());assertEquals(3,mascot.sceneFilterCount());}});}
    @Test void adminLocaleSwitchStillRequiresFreshSecondFactor()throws Exception{login(Role.ADMIN);FxSupport.fx(()->{for(Strings.Lang lang:Strings.Lang.values()){Strings.use(lang);assertFalse(app.ctx().adminAccess.hasValidAdminSession());assertThrows(panel.security.AccessDeniedException.class,()->panel.security.ServerAuthorization.require(panel.security.ServerOperation.SETTINGS_PREFERENCES_PERSIST));}assertEquals(0,app.authority.elevationCalls);});}
    @Test void accountReplacementResetsLanguageAndKeepsNewIdentity()throws Exception{login(Role.USER);FxSupport.fx(()->{Strings.use(Strings.Lang.PT_BR);app.authority.add("second-account","second@example.invalid",null,"synthetic-second-pass-1",Role.USER,false);User next=app.ctx().auth.login("second-account","synthetic-second-pass-1".toCharArray());app.invoke("afterLogin",User.class,next);assertEquals(Strings.Lang.EN,Strings.language());assertEquals("second-account",app.ctx().sessions.user().orElseThrow().user().username());});}
    @Test void signOutToEntryResetsSessionLanguageWithoutWritingPreferences()throws Exception{login(Role.USER);FxSupport.fx(()->{Strings.use(Strings.Lang.PT_BR);app.ctx().auth.logout();app.invoke("showEntry",String.class,null);assertEquals(Strings.Lang.EN,Strings.language());assertEquals(panel.authview.AuthScreens.LOGIN,((panel.authview.AuthScreens)app.field("authScreens")).route());assertNull(app.field("shell"));});}
}
