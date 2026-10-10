package panel;

import java.util.Map;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import panel.authview.AuthScreens;
import panel.design.ByxTheme;
import panel.design.ThemeMode;
import panel.i18n.LocaleView;
import panel.i18n.Strings;
import panel.motion.MotionService;

/** Opt-in interactive public component QA, never DEFAULT or an authenticated application composition. */
public final class PublicVisualQa {
    public static void main(String[] args) { Application.launch(PublicApp.class,args); }
    public static final class PublicApp extends Application {
        private AuthScreens auth;
        private panel.helpview.PublicHost host;
        private panel.helpview.SupportScreen help;
        private LocaleView locale;
        @Override public void start(Stage stage) {
            Strings.use(Strings.Lang.EN); ByxTheme.select(ThemeMode.DARK); panel.design.ByxFonts.load();
            MotionService motion=new MotionService();
            help=new panel.helpview.SupportScreen(motion,this::publicRoute,true);
            var faq=new panel.helpview.FaqScreen(motion,panel.helpview.HelpContent.faq(),this::publicRoute);
            host=new panel.helpview.PublicHost(motion,Map.of("h-faq",faq,"h-help",help),this::publicRoute,this::login);
            auth=new AuthScreens(motion,PublicResponsiveLayoutTest.DENIED,this::publicRoute,
                    user -> {throw new AssertionError("public QA cannot acquire a session");},
                    () -> {throw new AssertionError("public QA cannot persist preferences");},id -> {},this::publicRoute,null);
            auth.setServiceReadiness(AuthScreens.ServiceReadiness.UNAVAILABLE,() -> {});
            auth.show(AuthScreens.LOGIN,null,null); login();
            Scene scene=new Scene(new StackPane((Parent)auth.node(),host),1100,700);
            ByxTheme.apply(scene); locale=new LocaleView(scene);
            scene.setOnKeyPressed(key -> {
                if(key.isControlDown() && key.isAltDown() && (key.getCode()==KeyCode.L || key.getCode()==KeyCode.D)) {
                    ByxTheme.select(key.getCode()==KeyCode.L ? ThemeMode.LIGHT : ThemeMode.DARK);
                    ByxTheme.apply(scene); key.consume();
                }
            });
            stage.setTitle("BYX-MVP — C4.1-F PUBLIC QA (anonymous; Ctrl+Alt+L/D themes)");
            stage.setMinWidth(1100); stage.setMinHeight(700); stage.setScene(scene); stage.show();
        }
        private void login() { host.setVisible(false); host.setManaged(false); auth.node().setVisible(true); auth.node().setManaged(true); }
        private void publicRoute(String route) {
            if(!java.util.Set.of("h-help","h-faq").contains(route)) return;
            auth.node().setVisible(false);auth.node().setManaged(false);host.setManaged(true);host.setVisible(true);host.show(route);
        }
        @Override public void stop() { if(locale!=null)locale.close();if(auth!=null)auth.dispose();if(host!=null)host.dispose();if(help!=null)help.dispose(); }
    }
}
