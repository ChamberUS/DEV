package panel.accountview;
import static org.junit.jupiter.api.Assertions.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.*;
import panel.design.*;
import panel.i18n.*;
import panel.motion.*;
import panel.tradeview.DeskHarness;
class PackageC2SettingsTest {
 @AfterEach void reset() throws Exception {DeskHarness.fx(()->{ByxTheme.resetSession();Strings.resetSession();});}
 @Test void deniedPreferencesStillAllowThemeWithoutBackendWrite() throws Exception {DeskHarness.fx(()->{
  var original=new AccountScreensTest.Stub();AccountData data=(AccountData)java.lang.reflect.Proxy.newProxyInstance(AccountData.class.getClassLoader(),new Class[]{AccountData.class},(proxy,method,args)->method.getName().equals("preferencesEditable")?false:method.invoke(original,args));MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);var content=new StackPane();var host=new ByxOverlayHost(content,motion);var settings=new SettingsScreen(motion,data,t->fail("theme must not navigate"),()->host);content.getChildren().add(settings.node());ByxTheme.apply(new Scene(host,1100,700));settings.select("Appearance");host.applyCss();((RadioButton)settings.node().lookup("#appearance-light")).fire();assertEquals(ThemeMode.LIGHT,ByxTheme.mode());assertFalse(settings.hasUnsavedChanges());assertFalse(settings.canSaveChanges());assertTrue(original.saves.isEmpty());assertFalse(host.saveBarVisible());assertTrue(settings.node().lookup("#appearance-system").isDisabled());settings.dispose();host.dispose();
 });}
 @Test void authorizedDraftAndSaveBarStayIntactAcrossThemeAndLanguageChanges() throws Exception {DeskHarness.fx(()->{
  for(var size:new int[][]{{1920,1080},{1440,900},{1100,700}}){var motion=new MotionService();motion.preference.set(MotionPreference.OFF);var content=new StackPane();var host=new ByxOverlayHost(content,motion);var data=new AccountScreensTest.Stub();var settings=new SettingsScreen(motion,data,t->{},()->host);content.getChildren().add(settings.node());ByxTheme.apply(new Scene(host,size[0],size[1]));settings.select("Appearance");host.applyCss();((ToggleButton)settings.node().lookupAll(".byx-desk-seg-btn").stream().filter(n->n instanceof ToggleButton b&&"REDUCED".equals(b.getUserData())).findFirst().orElseThrow()).setSelected(true);var draft=settings.draft();int count=settings.unsavedChangeCount();try(var locale=new LocaleView(host.getScene())){for(var lang:Strings.Lang.values())for(var mode:java.util.List.of(ThemeMode.DARK,ThemeMode.LIGHT)){Strings.use(lang);ByxTheme.select(mode);host.applyCss();host.layout();var selector=settings.node().lookup(".byx-theme-selector");assertNotNull(selector);var bounds=selector.localToScene(selector.getLayoutBounds());assertTrue(bounds.getMaxX()<=host.getScene().getWidth()+1,"appearance must fit native viewport "+lang+" "+mode+" "+size[0]+" max="+bounds.getMaxX());assertSame(draft,settings.draft());assertEquals(count,settings.unsavedChangeCount());assertTrue(host.saveBarVisible());assertTrue(data.saves.isEmpty());}}settings.discardChanges();settings.dispose();host.dispose();}
 });}
}
