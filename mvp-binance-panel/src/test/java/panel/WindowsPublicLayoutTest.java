package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

/** Native Windows toolkit, offscreen layout evidence only; never physical-screen UAT or authenticated UI. */
@EnabledOnOs(OS.WINDOWS)
class WindowsPublicLayoutTest {
    private static final Path OUTPUT = Path.of("docs/qa/package-c4/c4-1-f-layout-public");
    static Stream<Arguments> sizesLocalesThemes() {
        return Stream.of(new int[]{1100,700},new int[]{1440,900},new int[]{1920,1080})
                .flatMap(size -> Stream.of(Strings.Lang.EN,Strings.Lang.PT_BR)
                    .flatMap(language -> Stream.of(ThemeMode.DARK,ThemeMode.LIGHT)
                        .map(theme -> Arguments.of(size[0],size[1],language,theme))));
    }
    private static AssertionError forbidden() { return new AssertionError("Public layout must not request authentication, persistence or privilege"); }
    private static final AuthScreens.Services DENIED = new AuthScreens.Services() {
        public panel.auth.AuthenticationRequest beginLogin() { throw forbidden(); }
        public void createInitialAdmin(String u,String e,char[] p,String phone) { throw forbidden(); }
        public panel.auth.SessionOperation preparePasswordChange(long id,char[] current,char[] next) { throw forbidden(); }
        public panel.auth.SessionOperation prepareLogout() { throw forbidden(); }
    };
    private static void snapshot(Scene scene, String name, int width, int height) throws Exception {
        Parent root = scene.getRoot(); root.applyCss(); root.layout();
        assertEquals(width,scene.getWidth()); assertEquals(height,scene.getHeight());
        var image = scene.snapshot(null);
        assertEquals(width,image.getWidth()); assertEquals(height,image.getHeight());
        var bitmap = new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) bitmap.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        assertTrue(javax.imageio.ImageIO.write(bitmap,"png",OUTPUT.resolve(name+".png").toFile()));
        assertThrows(panel.security.AccessDeniedException.class,
                () -> panel.security.ServerAuthorization.require(panel.security.ServerOperation.SETTINGS_PREFERENCES_PERSIST));
    }
    @ParameterizedTest @MethodSource("sizesLocalesThemes")
    void publicSurfacesRespectRequestedLayoutWithoutAnyAuthentication(int width,int height,Strings.Lang language,ThemeMode theme) throws Exception {
        Files.createDirectories(OUTPUT);
        FxSupport.fx(() -> {
            var oldLanguage = Strings.language(); var oldTheme = ByxTheme.mode();
            MotionService motion = new MotionService(); motion.preference.set(MotionPreference.OFF);
            AuthScreens auth = new AuthScreens(motion,DENIED,id -> {},user -> {throw forbidden();},
                    () -> {throw forbidden();},id -> {throw forbidden();},id -> {},null);
            var faq = new panel.helpview.FaqScreen(motion,panel.helpview.HelpContent.faq(),id -> {});
            var help = new panel.helpview.SupportScreen(motion,id -> {},true);
            var host = new panel.helpview.PublicHost(motion,Map.of("h-faq",faq,"h-help",help),id -> {},() -> {});
            try {
                Strings.use(language); ByxTheme.select(theme); panel.design.ByxFonts.load();
                String suffix = width+"x"+height+"-"+language+"-"+theme;
                auth.setServiceReadiness(AuthScreens.ServiceReadiness.UNAVAILABLE,() -> {});
                auth.show(AuthScreens.LOGIN,null,null);
                Scene login = new Scene((Parent)auth.node(),width,height); ByxTheme.apply(login);
                try (LocaleView locale = new LocaleView(login)) {
                    snapshot(login,"login-"+suffix,width,height);
                    var form = auth.layout().formHost().localToScene(auth.layout().formHost().getLayoutBounds());
                    assertTrue(form.getMinX() >= 0 && form.getMaxX() <= width+1,"form is within requested width");
                    assertTrue(form.getWidth() > 250,"usable login form width");
                    assertEquals(AuthScreens.ServiceReadiness.UNAVAILABLE,auth.serviceReadiness());
                    assertEquals(AuthScreens.LOGIN,auth.route());
                    assertFalse(auth.layout().lookupAll(".button").stream().anyMatch(n -> n instanceof Button b && "Sign in".equals(b.getText())),"unavailable UI does not offer successful sign-in");
                    Files.writeString(OUTPUT.resolve("login-"+suffix+".txt"),
                            "AUTOMATED_OFFSCREEN_LAYOUT; NOT PHYSICAL UAT\nrequested="+width+"x"+height
                            +"\nscene="+login.getWidth()+"x"+login.getHeight()+"\nform="+form
                            +"\nservice=UNAVAILABLE\nauthority=DENIED\n");
                }
                Scene publicScene = new Scene(host,width,height); ByxTheme.apply(publicScene);
                try (LocaleView locale = new LocaleView(publicScene)) {
                    for(String route : List.of("h-faq","h-help")) {
                        host.show(route); assertEquals(route,host.showing());
                        snapshot(publicScene,route+"-"+suffix,width,height);
                        assertNull(host.lookup("#user-menu")); assertNull(host.lookup("#appearance-light"));
                    }
                }
            } catch(Exception e) { throw new RuntimeException(e); }
            finally { auth.dispose(); host.dispose(); Strings.use(oldLanguage); ByxTheme.select(oldTheme); }
        });
    }
}
