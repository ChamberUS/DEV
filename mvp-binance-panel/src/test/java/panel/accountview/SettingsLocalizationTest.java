package panel.accountview;

import static org.junit.jupiter.api.Assertions.*;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.*;
import org.junit.jupiter.api.*;
import panel.design.*;
import panel.i18n.*;
import panel.motion.*;
import panel.tradeview.DeskHarness;

class SettingsLocalizationTest {
    @AfterEach void reset() throws Exception {DeskHarness.fx(()->Strings.resetSession());}
    @Test void languageRemainsEditableUnderProductionPreferenceDenial() throws Exception {DeskHarness.fx(()->{
        var original=new AccountScreensTest.Stub();AccountData data=(AccountData)java.lang.reflect.Proxy.newProxyInstance(AccountData.class.getClassLoader(),new Class[]{AccountData.class},(proxy,method,args)->method.getName().equals("preferencesEditable")?false:method.invoke(original,args));MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);StackPane content=new StackPane();ByxOverlayHost host=new ByxOverlayHost(content,motion);SettingsScreen settings=new SettingsScreen(motion,data,t->{},()->host);content.getChildren().add(settings.node());Scene scene=new Scene(host,1100,700);ByxTheme.apply(scene);
        try(LocaleView view=new LocaleView(scene)){host.applyCss();host.layout();LanguageSelector selector=(LanguageSelector)settings.node().lookupAll(".combo-box").stream().filter(n->n instanceof LanguageSelector).findFirst().orElseThrow();assertFalse(selector.isDisabled());selector.setValue(Strings.Lang.PT_BR);assertFalse(settings.hasUnsavedChanges());assertFalse(settings.canSaveChanges());assertTrue(original.saves.isEmpty());assertFalse(host.saveBarVisible());assertThrows(panel.security.AccessDeniedException.class,()->panel.security.ServerAuthorization.require(panel.security.ServerOperation.SETTINGS_PREFERENCES_PERSIST));}settings.dispose();host.dispose();
    });}
    @Test void languageSwitchKeepsAuthorizedDraftAndCompactSaveBarAtEveryRequiredSize() throws Exception {DeskHarness.fx(()->{
        for(int[] size:new int[][]{{1920,1080},{1440,900},{1100,700}}){Strings.resetSession();MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);StackPane content=new StackPane();ByxOverlayHost host=new ByxOverlayHost(content,motion);var data=new AccountScreensTest.Stub();SettingsScreen settings=new SettingsScreen(motion,data,t->{},()->host);content.getChildren().add(settings.node());Scene scene=new Scene(host,size[0],size[1]);ByxTheme.apply(scene);
            try(LocaleView view=new LocaleView(scene)){settings.select("Appearance");host.applyCss();host.layout();((ToggleButton)settings.node().lookupAll(".byx-desk-seg-btn").stream().filter(n->n instanceof ToggleButton b&&"REDUCED".equals(b.getUserData())).findFirst().orElseThrow()).setSelected(true);var draft=settings.draft();int changes=settings.unsavedChangeCount();Strings.use(Strings.Lang.PT_BR);host.applyCss();host.layout();assertSame(draft,settings.draft());assertEquals(changes,settings.unsavedChangeCount());assertTrue(settings.hasUnsavedChanges());assertTrue(data.saves.isEmpty());Region bar=(Region)host.layer(OverlayLayer.SAVEBAR).getChildren().getFirst();assertTrue(bar.getHeight()<100);assertEquals(720,bar.getWidth(),1);Strings.use(Strings.Lang.EN);assertSame(draft,settings.draft());settings.discardChanges();assertFalse(host.saveBarVisible());}settings.dispose();host.dispose();
        }
    });}
}
