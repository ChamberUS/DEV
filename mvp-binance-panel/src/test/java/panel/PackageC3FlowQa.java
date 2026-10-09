package panel;

import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.*;
import panel.design.*;
import panel.i18n.*;
import panel.localservice.*;
import panel.notifications.*;
import panel.shell.*;

/** External native QA only: isolated synthetic authority and controlled read-only Service observations. */
public final class PackageC3FlowQa {
    static Path output; static int failures; static final List<String> lines=new ArrayList<>();static LocalServiceStatus absent;
    static void check(boolean condition,String name){lines.add((condition?"PASS ":"FAIL ")+name);if(!condition)failures++;}
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]);Files.createDirectories(output);var home=Files.createTempDirectory("byx-c3-native-");var settings=Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("settings.properties"),"dataSource=REAL\nprojectPath="+home.resolve("empty-project")+"\ncliPath=/usr/bin/false\nmotion="+System.getProperty("byx.qa.motion","OFF")+"\ndensity=COMPACT\nonboardingCompleted=true\n");System.setProperty("user.home",home.toString());
        absent=new LocalServiceClient(home.resolve("missing-service")).probe(false);
        Application.launch(App.class,args);Files.write(output.resolve("native-flow.txt"),lines);lines.forEach(System.out::println);System.exit(failures==0?0:1);
    }
    public static final class App extends PanelApp {
        Stage stage;AppContext ctx;NotificationCenter center;ServiceNotificationObserver observer;Object mascot;final List<Runnable> queue=new ArrayList<>();
        @Override protected AppContext createContext(){return QaContext.create();}
        Object field(String name){try{var f=PanelApp.class.getDeclaredField(name);f.setAccessible(true);return f.get(this);}catch(Exception e){throw new IllegalStateException(e);}}
        void invoke(String name,Class<?> type,Object value){try{var m=PanelApp.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(this,value);}catch(Exception e){throw new IllegalStateException(e);}}
        ByxShell shell(){return(ByxShell)field("shell");}NotificationPanel panel(){return(NotificationPanel)field("notificationPanel");}ShellRouter router(){return(ShellRouter)field("router");}
        @Override public void start(Stage stage){this.stage=stage;super.start(stage);ctx=(AppContext)field("ctx");center=(NotificationCenter)field("notifications");observer=(ServiceNotificationObserver)field("notificationService");QaContext.dev().add("c3-user","native@example.invalid",null,"synthetic-c3-native-pass",panel.security.Role.USER,false);plan();next();}
        void next(){var pause=new PauseTransition(Duration.millis(200));pause.setOnFinished(e->{try{if(queue.isEmpty()){stop();stage.close();Platform.exit();return;}queue.removeFirst().run();next();}catch(Throwable failure){failure.printStackTrace();check(false,failure.getClass().getSimpleName()+" "+failure.getMessage());queue.clear();next();}});pause.play();}
        void plan(){
            queue.add(()->{var u=ctx.auth.login("c3-user","synthetic-c3-native-pass".toCharArray());invoke("afterLogin",panel.user.User.class,u);mascot=shell().topBar().mascot();});
            queue.add(()->{ctx.localService.stop();center.start(ctx.sessions.user().orElseThrow());observer.reset();check(center.events().isEmpty(),"new session history contains no fabricated activity");shots("empty");});
            queue.add(()->{observer.observe(center.scope(),absent);check(center.events().stream().anyMatch(e->e.type()==NotificationEvent.Type.SERVICE_UNAVAILABLE),"actual absent Service probe publishes availability");check(center.events().stream().noneMatch(e->e.type()==NotificationEvent.Type.SERVICE_LOST),"initial absence is not a disconnect");shots("offline-unread");});
            queue.add(()->{center.readAll();check(center.unreadProperty().get()==0,"local mark all read");shots("offline-read");});
            queue.add(()->{observer.observe(center.scope(),LocalServiceStatus.failed(LocalServiceStatus.State.AUTH_FAILED,"PRIVATE_PAYLOAD_NOT_RENDERED",false,Instant.now()));shots("service-error");check(center.events().stream().noneMatch(e->e.toString().contains("PRIVATE_PAYLOAD")),"no raw Service payload retained");});
            queue.add(()->{observer.observe(center.scope(),LocalServiceStatus.failed(LocalServiceStatus.State.CONNECTED,"connected",true,Instant.now()));observer.observe(center.scope(),LocalServiceStatus.failed(LocalServiceStatus.State.UNAVAILABLE,"refused",true,Instant.now()));observer.observe(center.scope(),LocalServiceStatus.failed(LocalServiceStatus.State.CONNECTED,"connected",true,Instant.now()));check(center.events().stream().anyMatch(e->e.type()==NotificationEvent.Type.SERVICE_RESTORED),"confirmed recovery event");shots("recovered");});
            queue.add(()->{for(int i=0;i<20;i++){panel().open();Object first=shell().lookup("#notification-center");panel().open();check(first==shell().lookup("#notification-center"),"one surface iteration "+i);check(shell().overlay().openPopovers()==1,"one popover iteration "+i);panel().toggle();}check(mascot==shell().topBar().mascot(),"same mascot after repeated notifications");check(shell().topBar().mascot().sceneFilterCount()==3,"mascot listener contract preserved");check(!ctx.adminAccess.hasValidAdminSession(),"notification events never authorize Research");var old=center.scope();ctx.auth.logout();check(center.events().isEmpty(),"actual logout clears notifications");check(!center.publish(old,NotificationEvent.Type.SERVICE_LOST,UUID.randomUUID(),Instant.now()),"old callback rejected after logout");invoke("showEntry",String.class,null);check(Strings.missingKeys().isEmpty(),"no missing localization keys");check(Strings.missingTranslations().isEmpty(),"no unexpected English fallback");});
        }
        void shots(String name){
            for(Strings.Lang lang:Strings.Lang.values()){
                Strings.use(lang);panel().open();
                for(ThemeMode theme:List.of(ThemeMode.DARK,ThemeMode.LIGHT)){
                    ByxTheme.select(theme);var events=List.copyOf(center.events());Object owner=center.scope();Object shell=shell();Node focused=stage.getScene().getFocusOwner();
                    int[][] sizes=Boolean.getBoolean("byx.qa.noShots")?new int[][]{{1100,700}}:new int[][]{{1920,1080},{1440,900},{1100,700}};
                    for(int[] size:sizes){Scene original=stage.getScene();Parent root=original.getRoot();original.setRoot(new Pane());Scene off=new Scene(root,size[0],size[1]);off.getStylesheets().setAll(original.getStylesheets());try(LocaleView locale=new LocaleView(off)){
                        root.applyCss();root.layout();Node pop=shell().overlay().layer(OverlayLayer.POPOVER).getChildren().getFirst();var bounds=pop.localToScene(pop.getLayoutBounds());check(bounds.getMinX()>=0&&bounds.getMaxX()<=size[0]+1&&bounds.getMinY()>=0&&bounds.getMaxY()<=size[1]+1,name+" popover fits "+lang+" "+theme+" "+size[0]);
                        if(!Boolean.getBoolean("byx.qa.noShots"))javax.imageio.ImageIO.write(ControlGalleryTest.toAwt(off.snapshot(null)),"png",output.resolve(theme.name().toLowerCase(Locale.ROOT)+"-"+lang.tag+"-"+name+"-"+size[0]+"x"+size[1]+".png").toFile());
                    }catch(Exception e){throw new IllegalStateException(e);}finally{off.setRoot(new Pane());original.setRoot(root);}}
                    check(events.equals(List.copyOf(center.events())),name+" theme/locale preserve event identity and read state "+lang+" "+theme);check(owner.equals(center.scope())&&shell==shell(),name+" session and shell stable "+lang+" "+theme);
                    check(mascot==shell().topBar().mascot(),name+" mascot stable "+lang+" "+theme);
                }
                shell().overlay().closePopovers();
            }
        }
    }
}
