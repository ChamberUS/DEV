import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import panel.i18n.*;
import java.util.concurrent.CountDownLatch;
import java.math.BigDecimal;
public class ByxC1BundleProbe {
 static int checks; static Throwable failure;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 public static void main(String[] args)throws Exception{
  var done=new CountDownLatch(1);
  Platform.startup(()->{try{
   check(Strings.table(Strings.Lang.EN).size()==2115,"packaged EN catalog 2115 keys");
   check(Strings.table(Strings.Lang.EN).keySet().equals(Strings.table(Strings.Lang.PT_BR).keySet()),"packaged EN/PT key parity");
   var label=new Label("Settings");var input=new TextField("Home");input.setPromptText("Password");var selector=new LanguageSelector();var root=new VBox(label,input,selector);var scene=new Scene(root);
   try(var view=new LocaleView(scene)){
    check(label.getText().equals("Settings"),"packaged English presentation");
    selector.setValue(Strings.Lang.PT_BR);check(label.getText().equals("Configurações"),"packaged Portuguese switches same view");
    check(input.getPromptText().equals("Senha"),"packaged Portuguese input prompt");
    check(input.getText().equals("Home"),"packaged entered text remains literal");
    check(selector.getConverter().toString(selector.getValue()).equals("Português (Brasil)"),"packaged language autonym");
    check(DisplayFormats.exact(new BigDecimal("2480.840001")).equals("2.480,840001"),"packaged exact financial display");
    check(Presentation.text("Account menu, Home, loading").equals("Menu da conta, Home, carregando"),"packaged mascot announcement");
    check(Presentation.text("Live trading OFF").equals("Real OFF"),"packaged compact real-trading OFF state");
    Strings.resetSession();check(label.getText().equals("Settings"),"packaged reset restores English");
    check(Strings.missingKeys().isEmpty()&&Strings.missingTranslations().isEmpty(),"packaged catalog no missing or fallback");
   }
   check(label.getText().equals("Settings"),"packaged disposable binding released");
  }catch(Throwable t){failure=t;t.printStackTrace();}finally{done.countDown();}});
  done.await();Platform.exit();System.out.println("RESULT checks="+checks);System.exit(failure==null?0:1);
 }
}
