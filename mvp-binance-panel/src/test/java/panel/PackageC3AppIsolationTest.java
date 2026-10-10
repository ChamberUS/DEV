package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import panel.notifications.*;
import panel.notifications.NotificationEvent.Type;
import panel.security.Role;
import panel.user.User;

class PackageC3AppIsolationTest {
    private static String oldHome;
    private ResearchGateReproductionTest.App app;
    @BeforeAll static void isolate()throws Exception{FxSupport.start();oldHome=System.getProperty("user.home");var h=Files.createTempDirectory("byx-c3-isolation-");var s=SecureTempDirFactory.directory(h.resolve(".mvp-binance-panel"));Files.writeString(s.resolve("settings.properties"),"projectPath="+h.resolve("empty")+"\ncliPath=/usr/bin/false\nmotion=OFF\nonboardingCompleted=true\n");System.setProperty("user.home",h.toString());}
    @AfterAll static void restore(){System.setProperty("user.home",oldHome);}
    @AfterEach void close()throws Exception{if(app!=null)FxSupport.fx(()->{app.stop();((Stage)app.field("stage")).close();});}
    NotificationCenter center(){return app.field("notifications");}
    void login(boolean admin)throws Exception{app=FxSupport.fx(ResearchGateReproductionTest.App::new);app.init();FxSupport.fx(()->{app.start(new Stage());app.authority.add("c3-test","test@example.invalid",null,"synthetic-c3-pass-1",admin?Role.ADMIN:Role.USER,false);var user=app.ctx().auth.login("c3-test","synthetic-c3-pass-1".toCharArray());app.invoke("afterLogin",User.class,user);});}
    @Test void actualAuthLogoutImmediatelyClearsPrivateEvents()throws Exception{login(true);FxSupport.fx(()->{center().publish(center().scope(),Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),java.time.Instant.now());app.ctx().auth.logout();assertTrue(center().events().isEmpty());assertFalse(center().active());});}
    @Test void accountChangeRevokesOldScopeBeforeNewShell()throws Exception{login(true);FxSupport.fx(()->{var old=center().scope();center().publish(old,Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),java.time.Instant.now());app.authority.add("new-user","next@example.invalid",null,"synthetic-c3-pass-2",Role.USER,false);var next=app.ctx().auth.login("new-user","synthetic-c3-pass-2".toCharArray());assertFalse(center().accepts(old));app.invoke("afterLogin",User.class,next);assertTrue(center().events().isEmpty());assertFalse(center().publish(old,Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),java.time.Instant.now()));});}
    @Test void sameAccountRoleDemotionClearsAdminPresentation()throws Exception{login(true);FxSupport.fx(()->{center().publish(center().scope(),Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),java.time.Instant.now());var u=app.ctx().sessions.user().orElseThrow().user();app.ctx().sessions.updateUser(new User(u.id(),u.username(),u.email(),u.passwordHash(),Role.USER,u.status(),u.phone(),u.emailVerified(),u.phoneVerified(),u.mustChangePassword(),u.createdAt(),u.updatedAt(),u.lastLoginAt()));assertTrue(center().events().isEmpty());assertFalse(center().active());});}
    @Test void genericRealGateFailurePublishesOnlyForAdmin()throws Exception{login(true);FxSupport.fx(()->{app.invoke("researchGateFailed",Throwable.class,new IllegalStateException("private-payload-never-rendered"));assertTrue(center().events().stream().anyMatch(e->e.type()==Type.RESEARCH_ACCESS_FAILED));assertFalse(center().events().toString().contains("private-payload"));assertFalse(app.ctx().adminAccess.hasValidAdminSession());});}
    @Test void userGateFailureDoesNotDiscloseAdminEvents()throws Exception{login(false);FxSupport.fx(()->{app.invoke("researchGateFailed",Throwable.class,new IllegalStateException("private-payload"));assertTrue(center().events().stream().noneMatch(e->e.type().adminOnly));});}
    @Test void notificationResearchDestinationUsesRealPendingMfaGate()throws Exception{login(true);FxSupport.fx(()->{app.invoke("show",String.class,NotificationEvent.Destination.RESEARCH.route);assertNotEquals("overview",app.router().route());assertFalse(app.ctx().adminAccess.hasValidAdminSession());});}
}
