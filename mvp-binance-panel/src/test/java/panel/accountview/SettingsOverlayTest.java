package panel.accountview;

import static org.junit.jupiter.api.Assertions.*;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import panel.design.ByxOverlayHost;
import panel.design.ByxTheme;
import panel.design.OverlayLayer;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;

class SettingsOverlayTest {
    private static void edit(SettingsScreen s, String value) {
        s.select("Appearance"); s.node().applyCss();
        ((ToggleButton)s.node().lookupAll(".byx-desk-seg-btn").stream()
                .filter(n -> n instanceof ToggleButton b && value.equals(b.getText())).findFirst().orElseThrow()).setSelected(true);
    }
    @Test void authorizedDraftUsesCompactNonModalBarAtEverySupportedSizeAndMotionMode() throws Exception {
        DeskHarness.fx(() -> {
            for (MotionPreference mode : MotionPreference.values()) for(int[] size:new int[][]{{1920,1080},{1440,900},{1100,700}}) {
                MotionService motion=new MotionService(); motion.preference.set(mode);
                StackPane content=new StackPane(); ByxOverlayHost host=new ByxOverlayHost(content,motion);
                var data=new AccountScreensTest.Stub(); SettingsScreen s=new SettingsScreen(motion,data,t->{},()->host);
                content.getChildren().add(s.node()); Scene scene=new Scene(host,size[0],size[1]); ByxTheme.apply(scene);
                host.applyCss();host.layout();edit(s,"REDUCED");host.applyCss();host.layout();
                var bar=(Region)host.layer(OverlayLayer.SAVEBAR).getChildren().getFirst();
                assertTrue(bar.getHeight()<100,"savebar stretched to parent: "+bar.getHeight());
                assertTrue(bar.getWidth()<=720);assertTrue(bar.getHeight()>30);
                assertNull(host.topLayer());assertEquals(0,host.openDialogs());assertTrue(data.saves.isEmpty());
                assertFalse(bar.isMouseTransparent());s.dispose();host.dispose();
            }
        });
    }
    @Test void saveFailureKeepsDraftAndDiscardAndSaveUnmountTheBar() throws Exception {
        DeskHarness.fx(() -> {
            MotionService motion=new MotionService(); motion.preference.set(MotionPreference.OFF);
            StackPane content=new StackPane(); ByxOverlayHost host=new ByxOverlayHost(content,motion);
            var data=new AccountScreensTest.Stub(); SettingsScreen s=new SettingsScreen(motion,data,t->{},()->host);
            content.getChildren().add(s.node()); Scene scene=new Scene(host,1100,700);ByxTheme.apply(scene);host.applyCss();host.layout();
            edit(s,"OFF");data.saveFails=true;assertFalse(s.saveChanges());assertTrue(s.hasUnsavedChanges());assertTrue(host.saveBarVisible());
            host.applyCss();host.layout();assertTrue(((Region)host.layer(OverlayLayer.SAVEBAR).getChildren().getFirst()).getHeight()<100);
            s.discardChanges();assertFalse(host.saveBarVisible());assertTrue(host.layer(OverlayLayer.SAVEBAR).getChildren().isEmpty());
            edit(s,"REDUCED");data.saveFails=false;assertTrue(s.saveChanges());assertFalse(host.saveBarVisible());assertEquals(1,data.saves.size());
            s.dispose();host.dispose();
        });
    }
    @Test void hideAndDisposeUnmountEvenADirtyBarAndShowRemountsOnlyOnce() throws Exception {
        DeskHarness.fx(() -> {
            MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);
            StackPane content=new StackPane();ByxOverlayHost host=new ByxOverlayHost(content,motion);
            SettingsScreen s=new SettingsScreen(motion,new AccountScreensTest.Stub(),t->{},()->host);content.getChildren().add(s.node());
            Scene scene=new Scene(host,1100,700);ByxTheme.apply(scene);host.applyCss();host.layout();edit(s,"OFF");
            s.onHide();assertTrue(s.hasUnsavedChanges());assertFalse(host.saveBarVisible());
            s.onShow();s.onShow();assertEquals(1,host.layer(OverlayLayer.SAVEBAR).getChildren().size());
            s.dispose();assertFalse(host.saveBarVisible());assertTrue(host.layer(OverlayLayer.SAVEBAR).getChildren().isEmpty());host.dispose();
        });
    }
    @Test void productionReadOnlyControlsCannotCreateADraftOrWritePreferences() throws Exception {
        DeskHarness.fx(() -> {
            MotionService motion=new MotionService();var original=new AccountScreensTest.Stub();
            AccountData readOnly=(AccountData)java.lang.reflect.Proxy.newProxyInstance(AccountData.class.getClassLoader(),new Class[]{AccountData.class},
                    (proxy,method,args)->method.getName().equals("preferencesEditable")?false:method.invoke(original,args));
            ByxOverlayHost host=new ByxOverlayHost(new StackPane(),motion);
            SettingsScreen s=new SettingsScreen(motion,readOnly,t->{},()->host);Scene scene=new Scene((javafx.scene.Parent)s.node(),1100,700);ByxTheme.apply(scene);
            s.node().applyCss();edit(s,"REDUCED");assertFalse(s.hasUnsavedChanges());assertFalse(s.canSaveChanges());assertFalse(s.saveChanges());
            assertTrue(original.saves.isEmpty());assertFalse(host.saveBarVisible());s.dispose();host.dispose();
        });
    }
}
