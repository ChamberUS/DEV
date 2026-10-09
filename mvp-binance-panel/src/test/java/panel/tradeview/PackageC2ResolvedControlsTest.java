package panel.tradeview;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import panel.design.*;
import panel.motion.MotionPreference;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
/** Verifies cascade on real multi-level controls after initial DARK CSS resolution. */
class PackageC2ResolvedControlsTest {
 @Test void liveDeskCardAxisAndAvatarResolveApprovedLightAndRestoreDark() throws Exception {
  DeskHarness.fx(()->{var d=DeskHarness.open(1440,900,DeskFixtures.liveWithAccount(22),MotionPreference.OFF);
   try {var chart=d.desk.chart().chart();var dark=((Region)d.shell.lookup("#desk-chart")).getBackground();
    ByxTheme.select(ThemeMode.LIGHT);d.layout();
    assertEquals(Color.web("#FAFBFD"),((Region)d.shell.lookup("#desk-chart")).getBackground().getFills().getLast().getFill());
    assertEquals(Color.web("#59677D"),((Region)d.shell.lookup(".chart-text-ink")).getBackground().getFills().getLast().getFill());
    assertEquals(Color.web("#697A92"),((Region)d.shell.lookup(".byx-avatar")).getBackground().getFills().getFirst().getFill());
    var thumbs=d.shell.lookupAll(".scroll-bar .thumb");assertFalse(thumbs.isEmpty(),"native scrollbar skin must be resolved");for(var thumb:thumbs)assertEquals(Color.web("#697A92"),((Region)thumb).getBackground().getFills().getLast().getFill(),"LIGHT interactive scrollbar uses essential boundary ink");
    assertSame(chart,d.desk.chart().chart());ByxTheme.select(ThemeMode.DARK);d.layout();assertEquals(dark,((Region)d.shell.lookup("#desk-chart")).getBackground());
   }finally{d.close();ByxTheme.resetSession();}
  });
 }
}
