package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import panel.authview.AuthScreens;
import panel.design.ByxTheme;
import panel.design.ThemeMode;
import panel.i18n.LocaleView;
import panel.i18n.Strings;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Portable public-only layout contract. Native Scene snapshots are not physical display UAT. */
class PublicResponsiveLayoutTest {
    static Stream<Arguments> cases() {
        return Stream.of(new int[]{1100,700}, new int[]{1440,900}, new int[]{1920,1080}, new int[]{1086,663})
                .flatMap(size -> Stream.of(Strings.Lang.EN, Strings.Lang.PT_BR)
                    .flatMap(lang -> Stream.of(ThemeMode.DARK, ThemeMode.LIGHT)
                        .map(theme -> Arguments.of(size[0],size[1],lang,theme))));
    }
    static final AuthScreens.Services DENIED = new AuthScreens.Services() {
        private AssertionError refused() { return new AssertionError("public QA must never request authority or persistence"); }
        public panel.auth.AuthenticationRequest beginLogin() { throw refused(); }
        public void createInitialAdmin(String u,String e,char[] p,String phone) { throw refused(); }
        public panel.auth.SessionOperation preparePasswordChange(long id,char[] current,char[] next) { throw refused(); }
        public panel.auth.SessionOperation prepareLogout() { throw refused(); }
    };
    static void snapshot(Scene scene, Path output) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        var image = scene.snapshot(null);
        var bitmap = new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++)
            bitmap.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        assertTrue(javax.imageio.ImageIO.write(bitmap,"png",output.toFile()));
    }
    private static Bounds bounds(Node node) { return node.localToScene(node.getLayoutBounds()); }
    @ParameterizedTest @MethodSource("cases")
    void expandedGuidanceAndLocalizedHelpActionsStayUsable(int width,int height,Strings.Lang lang,ThemeMode theme) throws Exception {
        Path output = Path.of(System.getProperty("byx.visual.evidence", "docs/qa/package-c4/c4-1-f-layout-after"));
        Files.createDirectories(output);
        FxSupport.fx(() -> {
            var oldLang=Strings.language(); var oldTheme=ByxTheme.mode();
            MotionService motion=new MotionService(); motion.preference.set(MotionPreference.OFF);
            AuthScreens auth=new AuthScreens(motion,DENIED,id -> {},u -> {throw new AssertionError("no successful login");},
                    () -> {throw new AssertionError("no settings persistence");},id -> {},id -> {},null);
            var navigated=new java.util.ArrayList<String>();
            var help=new panel.helpview.SupportScreen(motion,navigated::add,true);
            var faq=new panel.helpview.FaqScreen(motion,panel.helpview.HelpContent.faq(),navigated::add);
            var host=new panel.helpview.PublicHost(motion,Map.of("h-help",help,"h-faq",faq),navigated::add,() -> {});
            try {
                Strings.use(lang); ByxTheme.select(theme); panel.design.ByxFonts.load();
                String suffix=width+"x"+height+"-"+lang+"-"+theme;
                auth.setServiceReadiness(AuthScreens.ServiceReadiness.UNAVAILABLE,() -> {});
                auth.show(AuthScreens.LOGIN,null,null);
                Scene login=new Scene((Parent)auth.node(),width,height); ByxTheme.apply(login);
                double topBottom, titleTop, viewportBottom, footerTop;
                boolean scrollable;
                double guidanceBottom, noteRight, viewportRight;
                try(LocaleView locale=new LocaleView(login)) {
                    login.getRoot().applyCss(); login.getRoot().layout();
                    ((Button)auth.layout().lookup("#registration-how")).fire();
                    snapshot(login,output.resolve("guidance-top-"+suffix+".png"));
                    topBottom=bounds(auth.layout().lookup(".byx-auth-top").getParent()).getMaxY();
                    titleTop=bounds(auth.layout().lookup(".byx-auth-title")).getMinY();
                    footerTop=bounds(auth.layout().lookup(".byx-auth-footer")).getMinY();
                    var scroll=(ScrollPane)auth.layout().lookup(".byx-auth-scroll");
                    scrollable=scroll != null && scroll.getViewportBounds().getHeight()>0;
                    viewportBottom=scroll == null ? bounds(auth.layout().formHost()).getMaxY() : bounds(scroll).getMaxY();
                    noteRight=bounds(auth.layout().lookup("#registration-note")).getMaxX();
                    viewportRight=scroll == null ? bounds(auth.layout().formHost()).getMaxX() : bounds(scroll.lookup(".viewport")).getMaxX();
                    if(scroll != null) scroll.setVvalue(1);
                    snapshot(login,output.resolve("guidance-bottom-"+suffix+".png"));
                    Bounds guidance=bounds(auth.layout().lookup("#registration-body"));
                    guidanceBottom=guidance.getMaxY();
                    Files.writeString(output.resolve("metrics-"+suffix+".txt"),
                            "AUTOMATED_OFFSCREEN_LAYOUT; NOT PHYSICAL UAT\nscene="+login.getWidth()+"x"+login.getHeight()
                            +"\ntopControlsBottom="+topBottom+"\ntitleTop="+titleTop+"\nviewportBottom="+viewportBottom
                            +"\nfooterTop="+footerTop+"\nguidanceBottomAtScrollEnd="+guidanceBottom
                            +"\nnoteRight="+noteRight+"\nviewportRight="+viewportRight+"\n");
                    assertEquals(AuthScreens.ServiceReadiness.UNAVAILABLE,auth.serviceReadiness());
                    assertThrows(panel.security.AccessDeniedException.class,() -> panel.security.ServerAuthorization.require(
                            panel.security.ServerOperation.SETTINGS_PREFERENCES_PERSIST));
                    // Keep failures until both public surfaces have been captured below.
                }
                Scene publicScene=new Scene(host,width,height); ByxTheme.apply(publicScene);
                double actionWidth,actionPreferred;
                String actionText,renderedText;
                try(LocaleView locale=new LocaleView(publicScene)) {
                    host.show("h-help"); snapshot(publicScene,output.resolve("help-"+suffix+".png"));
                    Button action=(Button)help.node().lookupAll(".byx-btn").stream().findFirst().orElseThrow();
                    actionWidth=action.getWidth(); actionPreferred=action.prefWidth(-1);
                    actionText=action.getText(); renderedText=((javafx.scene.text.Text)action.lookup(".text")).getText();
                    Files.writeString(output.resolve("metrics-"+suffix+".txt"),
                            "faqActionWidth="+actionWidth+"\nfaqActionPreferred="+actionPreferred+"\ntext="+actionText
                            +"\nrendered="+renderedText+"\nfont="+action.getFont()+"\npadding="+action.getPadding()+"\n",java.nio.file.StandardOpenOption.APPEND);
                    action.fire(); assertEquals(java.util.List.of("h-faq"),navigated);
                    host.show("h-faq"); snapshot(publicScene,output.resolve("faq-"+suffix+".png"));
                    assertNull(host.lookup("#user-menu"));
                }
                assertAll(
                    () -> assertTrue(noteRight <= viewportRight+1,"registration text must fit the viewport horizontally"),
                    () -> assertTrue(titleTop >= topBottom+8,"expanded title must not overlap language/header controls"),
                    () -> assertTrue(viewportBottom <= footerTop-8,"form viewport must not overlap footer"),
                    () -> assertTrue(scrollable,"overflow must have an accessible scroll viewport"),
                    () -> assertTrue(guidanceBottom <= viewportBottom+1,"guidance must be reachable at scroll end"),
                    () -> assertEquals(actionText,renderedText,"the button skin must render the full localized action"),
                    () -> assertTrue(actionWidth+0.5 >= actionPreferred,"full localized FAQ action must fit"));
            } catch(Exception e) { throw new RuntimeException(e); }
            finally {auth.dispose();host.dispose();help.dispose();Strings.use(oldLang);ByxTheme.select(oldTheme);}
        });
    }
}
