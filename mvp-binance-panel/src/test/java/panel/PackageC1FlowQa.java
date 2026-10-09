package panel;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.*;
import panel.i18n.*;
import panel.shell.*;
import panel.ui.View;

/** Native C1 evidence on the real PanelApp; authority/user/files are isolated synthetic test fixtures. */
public final class PackageC1FlowQa {
    static Path output;static final List<String> lines=new ArrayList<>();static final Map<String,List<String>> copy=new TreeMap<>();static int failures;
    public static void main(String[] args) throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);Path home=Files.createTempDirectory("byx-c1-native-");Path settings=Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("settings.properties"),"dataSource=REAL\nprojectPath="+home.resolve("empty-project")+"\ncliPath=/usr/bin/false\nmotion="+System.getProperty("byx.qa.motion","OFF")+"\ndensity=COMPACT\nonboardingCompleted=true\n");System.setProperty("user.home",home.toString());
        Application.launch(App.class,args);Files.write(output.resolve("native-flow.txt"),lines);new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("visible-copy.json").toFile(),copy);lines.forEach(System.out::println);System.exit(failures==0?0:1);
    }
    static void check(boolean ok,String message){lines.add((ok?"PASS ":"FAIL ")+message);if(!ok)failures++;}
    public static final class App extends PanelApp {
        Stage stage;AppContext ctx;List<Runnable> queue=new ArrayList<>();Object mascot;View view;
        @Override protected AppContext createContext(){return QaContext.create();}
        Object field(String name){try{Field f=PanelApp.class.getDeclaredField(name);f.setAccessible(true);return f.get(this);}catch(Exception e){throw new RuntimeException(e);}}
        void invoke(String name,Class<?> type,Object arg){try{Method m=PanelApp.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(this,arg);}catch(Exception e){throw new RuntimeException(e);}}
        ByxShell shell(){return (ByxShell)field("shell");}ShellRouter router(){return(ShellRouter)field("router");}
        @Override public void start(Stage stage){this.stage=stage;super.start(stage);ctx=(AppContext)field("ctx");QaContext.dev().add("c1-user","user@example.invalid",null,"c1-synthetic-pass-1",panel.security.Role.USER,false);QaContext.dev().add("c1-admin","admin@example.invalid","+5511999991234","c1-synthetic-pass-1",panel.security.Role.ADMIN,false);plan();later();}
        void later(){PauseTransition pause=new PauseTransition(Duration.millis(180));pause.setOnFinished(e->{try{if(queue.isEmpty()){stop();stage.close();Platform.exit();return;}queue.removeFirst().run();later();}catch(Throwable failure){failure.printStackTrace();check(false,failure.toString());queue.clear();later();}});pause.play();}
        void closeDialogs(){try {Field f=panel.design.ByxOverlayHost.class.getDeclaredField("dialogs");f.setAccessible(true);var dialogs=(java.util.Deque<?>)f.get(shell().overlay());for(Object handle:List.copyOf(dialogs))((panel.design.ByxOverlayHost.DialogHandle)handle).close();}catch(Exception e){throw new RuntimeException(e);}}
        void plan(){
            queue.add(()->{for(Strings.Lang lang:Strings.Lang.values()){Strings.use(lang);check(labels().contains(lang==Strings.Lang.EN?"Sign in":"Entrar"),"login labels "+lang);shots("login",lang);}check("auth:login".equals(router().route()),"locale does not authenticate");Strings.use(Strings.Lang.EN);var user=ctx.auth.login("c1-user","c1-synthetic-pass-1".toCharArray());invoke("afterLogin",panel.user.User.class,user);});
            queue.add(()->{mascot=shell().topBar().mascot();closeDialogs();});
            for(String route:new String[]{"t-home","t-desk","t-markets","t-bot","t-strategies","t-signals","t-portfolio","t-positions","t-orders","t-performance","t-activity","t-wallet","t-byx","t-chain-data","t-benefits","t-treasury","t-profile","t-security","t-sessions","t-settings","t-notifications","t-account-activity","h-faq","h-help","h-about","h-overview","h-diagnostics","h-whats-new","h-terms","h-privacy","h-shortcuts","sys-status"}){
                queue.add(()->{invoke("show",String.class,route);});
                queue.add(()->{Object session=ctx.sessions.user().orElseThrow();view=(View)field("activeView");Node node=view==null?null:view.node();for(Strings.Lang lang:Strings.Lang.values()){Strings.use(lang);check(route.equals(router().route()),route+" stable route "+lang);check(ctx.sessions.user().orElseThrow().id().equals(((panel.auth.UserSession)session).id()) && ctx.sessions.user().orElseThrow().loggedInAt().equals(((panel.auth.UserSession)session).loggedInAt()) && ctx.sessions.user().orElseThrow().user().id()==((panel.auth.UserSession)session).user().id(),route+" stable authority "+lang);check(view==field("activeView")&&(view==null||node==view.node()),route+" same view "+lang);check(mascot==shell().topBar().mascot()&&shell().topBar().mascot().sceneFilterCount()==3,route+" same mascot and handlers "+lang);shots(route,lang);}});
            }
            queue.add(()->{for(Strings.Lang lang:Strings.Lang.values()){Strings.use(lang);shell().topBar().avatar().fire();check(((UserMenu)field("userMenu")).isOpen(),"account menu "+lang);shots("account-menu",lang);((UserMenu)field("userMenu")).close();shell().topBar().notifications().fire();shots("notifications-popover",lang);shell().overlay().closePopovers();ShellPalette palette=(ShellPalette)field("palette");palette.open();check(palette.isOpen(),"palette "+lang);shots("palette",lang);palette.close();var dialog=shell().overlay().confirm("Sign out","You will need to sign in again to use BYX-MVP.","Sign out",true,()->check(false,"locale must not activate confirm"));Strings.use(lang==Strings.Lang.EN?Strings.Lang.PT_BR:Strings.Lang.EN);check(shell().overlay().openDialogs()==1,"modal retained across switch "+lang);shots("modal-switched-from-"+lang,Strings.language());dialog.close();}Strings.use(Strings.Lang.PT_BR);invoke("show",String.class,"overview");check(!"overview".equals(router().route()),"USER cannot open Research in PT");check(!ctx.adminAccess.hasValidAdminSession(),"USER locale cannot elevate authority");});
            queue.add(()->{ctx.auth.logout();invoke("showEntry",String.class,null);check(Strings.language()==Strings.Lang.EN,"logout resets session UI locale");check(shell()==null,"old shell disposed");var user=ctx.auth.login("c1-admin","c1-synthetic-pass-1".toCharArray());invoke("afterLogin",panel.user.User.class,user);});
            queue.add(()->{closeDialogs();Strings.use(Strings.Lang.PT_BR);invoke("show",String.class,"overview");check(!ctx.adminAccess.hasValidAdminSession(),"ADMIN still needs MFA after locale switch");shots("research-gate",Strings.Lang.PT_BR);});
            // The normal approved test authority flow provides contact verification, MFA and elevation.
            queue.add(()->{ctx.auth.logout();invoke("showEntry",String.class,null);var user=ctx.auth.login("c1-admin","c1-synthetic-pass-1".toCharArray());invoke("afterLogin",panel.user.User.class,user);var tf=ctx.adminAccess.startTwoFactor();tf.sendEmailCode();check(tf.verifyEmail(QaContext.dev().lastEmailCode())==panel.auth.TwoFactorResult.OK,"synthetic email verification");tf.sendSmsCode();check(tf.verifySms(QaContext.dev().lastSmsCode())==panel.auth.TwoFactorResult.OK,"synthetic SMS verification");tf.finish(false);check(ctx.adminAccess.hasValidAdminSession(),"normal synthetic MFA gate completed");closeDialogs();});
            for(String route:new String[]{"overview","capture","sessions","dataset","labels","features","hypotheses","validation","execution","paper","live","jobs","logs","settings","users"}) {queue.add(()->invoke("show",String.class,route));queue.add(()->{for(Strings.Lang lang:Strings.Lang.values()){Strings.use(lang);check(route.equals(router().route()),"authorized Research "+route+" "+lang);check(ctx.adminAccess.hasValidAdminSession(),"MFA authority preserved "+route+" "+lang);shots("research-"+route,lang);}});}
            queue.add(()->{Strings.use(Strings.Lang.PT_BR);invoke("show",String.class,"sys-onboarding");check(shell().overlay().openDialogs()>0,"onboarding shown in PT");shots("onboarding",Strings.Lang.PT_BR);Strings.use(Strings.Lang.EN);check(shell().overlay().openDialogs()>0,"onboarding remains during switch");shots("onboarding",Strings.Lang.EN);closeDialogs();check(Strings.missingKeys().isEmpty(),"no missing key during native flow: "+Strings.missingKeys());check(Strings.missingTranslations().isEmpty(),"no English fallback during native flow");});
        }
        List<String> labels(){List<String> values=new ArrayList<>();walk(stage.getScene().getRoot(),values);return values;}
        void walk(Node node,List<String> values){if(!node.isVisible()||node.getOpacity()==0)return;if(node instanceof Labeled label&&label.getText()!=null&&!label.getText().isBlank())values.add(label.getText());if(node instanceof javafx.scene.text.Text text&&text.getText()!=null&&!text.getText().isBlank())values.add(text.getText());if(node instanceof Parent parent)parent.getChildrenUnmodifiable().forEach(n->walk(n,values));}
        void shots(String name,Strings.Lang lang){
            int[][] sizes=System.getProperty("byx.qa.small")!=null?new int[][]{{1100,700}}:new int[][]{{1920,1080},{1440,900},{1100,700}};
            for(int[] size:sizes)try{Scene original=stage.getScene();Parent root=original.getRoot();original.setRoot(new Pane());Scene off=new Scene(root,size[0],size[1]);off.getStylesheets().setAll(original.getStylesheets());try(LocaleView view=new LocaleView(off)){root.applyCss();root.layout();String nameKey=lang.tag+"-"+name+"-"+size[0]+"x"+size[1];List<String> texts=new ArrayList<>();walk(root,texts);copy.put(nameKey,texts);if(System.getProperty("byx.qa.noShots")==null)javax.imageio.ImageIO.write(ControlGalleryTest.toAwt(off.snapshot(null)),"png",output.resolve(nameKey+".png").toFile());}finally{off.setRoot(new Pane());original.setRoot(root);} }catch(Exception e){throw new RuntimeException(e);}
        }
    }
}
