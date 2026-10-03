package panel.ui.auth;

import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import panel.app.AppContext;
import panel.auth.*;
import panel.ui.Ui;
import panel.user.User;

public final class TwoFactorView {
    private final AppContext ctx; private final Runnable onSuccess,onCancel; private final User user;
    private final VBox root=new VBox(14); private final VBox wrap=new VBox(root);
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"two-factor-provider");t.setDaemon(true);return t;});
    private final Timeline countdown;
    private TwoFactorFlow flow; private boolean phone; private boolean busy; private volatile boolean closed;
    private Button send; private Button verify; private Button finishButton; private Label message;
    public TwoFactorView(AppContext ctx,Runnable onSuccess,Runnable onCancel){
        this.ctx=ctx;this.onSuccess=onSuccess;this.onCancel=onCancel;user=ctx.sessions.user().orElseThrow().user();
        root.setMaxWidth(460);wrap.setAlignment(Pos.CENTER);wrap.setPadding(new Insets(40));
        root.getChildren().add(Ui.label("Checking verification providers…","muted"));
        countdown=new Timeline(new KeyFrame(javafx.util.Duration.seconds(1),e->refreshCountdown()));countdown.setCycleCount(Timeline.INDEFINITE);countdown.play();
        wrap.sceneProperty().addListener((o,a,b)->{if(a!=null&&b==null)dispose();});
        worker.execute(()->{
            try{TwoFactorFlow challenge=ctx.adminAccess.startTwoFactor();Platform.runLater(()->{if(closed){challenge.cancel();return;}flow=challenge;render();});}
            catch(RuntimeException e){
                String detail=e instanceof TwoFactorNotConfiguredException
                        ? ctx.emailProvider.name()+": "+ctx.emailProvider.status().state()+" · "+ctx.emailProvider.status().detail()+"\n"
                          +ctx.smsProvider.name()+": "+ctx.smsProvider.status().state()+" · "+ctx.smsProvider.status().detail()+"\nRun setup-local-2fa.sh and restart the app."
                        : "Check your session and set an email and E.164 phone in Profile.";
                Platform.runLater(()->{if(!closed)blocked(detail);});
            }
        });
    }
    public Node node(){return wrap;}
    private void dispose(){closed=true;countdown.stop();if(flow!=null)flow.cancel();worker.shutdownNow();}
    private void blocked(String detail){
        Button back=Ui.button("Back to Trading","ghost");back.setOnAction(e->{dispose();onCancel.run();});
        Label text=Ui.label(detail,"muted");text.setWrapText(true);
        root.getChildren().setAll(Ui.label("ADMIN VERIFICATION","card-title"),Ui.badge("ACCESS NOT GRANTED","bad"),text,back);
    }
    private <T> void action(Supplier<T> work,java.util.function.Consumer<T> success){
        if(busy||closed)return;busy=true;refreshCountdown();if(message!=null)message.setText("Working…");
        worker.execute(()->{
            try{T value=work.get();Platform.runLater(()->{if(closed)return;busy=false;refreshCountdown();success.accept(value);});}
            catch(RuntimeException e){Platform.runLater(()->{if(closed)return;busy=false;refreshCountdown();
                if(message!=null)message.setText(e instanceof OtpService.CooldownException ? e.getMessage():"Verification unavailable. Check providers, session or Keychain and try again.");});}
        });
    }
    private void render(){
        TextField code=new TextField();code.setPromptText(phone?"SMS verification code":"6-digit email code");code.setAccessibleText("Verification code");
        code.setTextFormatter(new TextFormatter<String>(c->c.getControlNewText().matches("[0-9]{0,"+(phone?10:6)+"}")?c:null));
        message=AuthShell.error();message.setWrapText(true);
        Label destination=Ui.label("Send code to: "+(phone?user.maskedPhone():user.maskedEmail()),"muted");
        send=Ui.button("Send code","ghost");verify=Ui.button(phone?"Verify Phone":"Verify Email","primary");
        Button cancel=Ui.button("Cancel","ghost");cancel.setOnAction(e->{dispose();onCancel.run();});
        send.setOnAction(e->action(()->{if(phone)flow.sendSmsCode();else flow.sendEmailCode();return true;},ok->{destination.setText("Code sent to: "+(phone?user.maskedPhone():user.maskedEmail()));message.setText("Code sent. Expires in 5 minutes.");code.requestFocus();refreshCountdown();}));
        verify.setOnAction(e->{String input=code.getText();code.clear();action(()->phone?flow.verifySms(input):flow.verifyEmail(input),result->{
            if(result==OtpService.Result.OK){if(!phone){phone=true;render();}else complete();}
            else {message.setText(switch(result){case INVALID->"Invalid code.";case EXPIRED->"Code expired. Start verification again.";case TOO_MANY_ATTEMPTS->"Too many attempts. Request a new code after cooldown.";default->"Send a code for this step first.";});ctx.motion.shake(code,3);}
        });});
        root.getChildren().setAll(Ui.label("ADMIN VERIFICATION","card-title"),Ui.label("Step "+(phone?"2":"1")+" of 2","muted"),
                Ui.label(phone?"Phone verification":"Email verification","h1"),destination,AuthShell.field("Verification code",code),message,new HBox(8,send,verify,cancel));
        if(ctx.devOtp!=null)root.getChildren().add(Ui.badge(DevOtpProvider.LABEL,"warn"));
        ctx.motion.fadeIn(root,javafx.util.Duration.millis(160));refreshCountdown();
    }
    private void refreshCountdown(){
        if(finishButton!=null)finishButton.setDisable(busy);
        if(flow==null||send==null)return;
        long seconds=flow.resendSeconds(phone);send.setDisable(busy||seconds>0);verify.setDisable(busy);
        send.setText(seconds>0?"Resend in "+seconds+"s":"Resend code");
    }
    private void complete(){
        countdown.stop();CheckBox trust=new CheckBox("Trust this Mac for 30 days");
        Button next=Ui.button("Continue to Research","primary");finishButton=next;message=AuthShell.error();
        root.getChildren().setAll(Ui.label("Identity verified","h1"),Ui.badge("EMAIL + SMS VERIFIED","ok"),trust,message,next);
        next.setOnAction(e->{boolean remember=trust.isSelected();action(()->{flow.finish(remember);return true;},ok->onSuccess.run());});
        ctx.motion.fadeIn(root,javafx.util.Duration.millis(180));
    }
}
