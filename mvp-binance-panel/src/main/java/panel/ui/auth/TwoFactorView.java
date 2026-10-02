package panel.ui.auth;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.auth.DevOtpProvider;
import panel.auth.OtpService;
import panel.auth.TwoFactorFlow;
import panel.auth.TwoFactorNotConfiguredException;
import panel.ui.Ui;
import panel.user.User;

/** Verificação adicional do admin fora de rede confiável: e-mail e depois celular. */
public class TwoFactorView {
    private final AppContext ctx;
    private final Runnable onSuccess;
    private final Runnable onCancel;
    private final VBox root = new VBox(14);
    private final VBox wrap = new VBox(root);
    private TwoFactorFlow flow;
    private final User user;
    private boolean phoneStep;

    public TwoFactorView(AppContext ctx, Runnable onSuccess, Runnable onCancel) {
        this.ctx = ctx;
        this.onSuccess = onSuccess;
        this.onCancel = onCancel;
        this.user = ctx.sessions.user().orElseThrow().user();
        root.setMaxWidth(420);
        wrap.setAlignment(Pos.CENTER);
        wrap.setPadding(new Insets(40));
        try {
            flow = ctx.adminAccess.startTwoFactor();
            render();
        } catch (TwoFactorNotConfiguredException e) {
            blocked("Two-factor authentication is not configured.", "Admin access outside the trusted network requires email and SMS verification. Configure the providers, or use the trusted network.");
        } catch (IllegalStateException e) {
            blocked("Admin contact incomplete", e.getMessage());
        }
    }

    public Node node() {
        return wrap;
    }

    private void blocked(String title, String detail) {
        Button back = Ui.button("Back to Trading", "ghost");
        back.setOnAction(e -> onCancel.run());
        root.getChildren().setAll(Ui.label("ADMIN VERIFICATION", "card-title"), Ui.label(title, "h1"), Ui.label(detail, "muted"), Ui.badge("ACCESS NOT GRANTED", "bad"), back);
        ((javafx.scene.control.Label) root.getChildren().get(2)).setWrapText(true);
    }

    private void render() {
        boolean phone = phoneStep;
        String dest = phone ? user.maskedPhone() : user.maskedEmail();
        TextField code = new TextField();
        code.setPromptText("6-digit code");
        code.setAccessibleText("6-digit code");
        code.textProperty().addListener((o, a, b) -> {
            String d = b.replaceAll("\\D", "");
            code.setText(d.length() > 6 ? d.substring(0, 6) : d);
        });
        var err = AuthShell.error();
        var info = Ui.label("", "muted");
        info.setWrapText(true);
        Button send = Ui.button("Send code", "ghost");
        Button verify = Ui.button("Verify", "primary");
        verify.setDefaultButton(true);
        Button cancel = Ui.button("Cancel", "ghost");
        cancel.setOnAction(e -> {
            flow.cancel();
            onCancel.run();
        });
        send.setOnAction(e -> {
            err.setText("");
            try {
                if (phone) {
                    flow.sendSmsCode();
                } else {
                    flow.sendEmailCode();
                }
                info.setText("Code sent to " + dest + ". It expires in 5 minutes.");
                var sent = ctx.icons.icon(phone ? "phone" : "mail", 26, "info");
                sendIcon.getChildren().setAll(sent.node());
                sent.play();
                code.requestFocus();
                refreshDev(phone);
            } catch (OtpService.CooldownException ex) {
                err.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                err.setText(ex instanceof TwoFactorNotConfiguredException ? ex.getMessage() : "Could not send the code.");
            }
        });
        verify.setOnAction(e -> {
            err.setText("");
            OtpService.Result r = phone ? flow.verifySms(code.getText()) : flow.verifyEmail(code.getText());
            switch (r) {
                case OK -> {
                    if (!phone) {
                        phoneStep = true;
                        render();
                    } else {
                        var ok = ctx.icons.icon("check", 26, "ok");
                        sendIcon.getChildren().setAll(ok.node());
                        ok.play();
                        javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(ctx.motion.scale(javafx.util.Duration.millis(450)));
                        p.setOnFinished(x -> onSuccess.run());
                        p.play();
                    }
                }
                case INVALID -> {
                    err.setText("Invalid code.");
                    var w = ctx.icons.icon("warning", 26, "warn");
                    sendIcon.getChildren().setAll(w.node());
                    w.play();
                }
                case EXPIRED -> err.setText("Code expired. Request a new one.");
                case TOO_MANY_ATTEMPTS -> err.setText("Too many attempts. Request a new code.");
                case NO_CHALLENGE -> err.setText("Request a code first.");
            }
            code.clear();
        });
        devBox = new VBox(4);
        sendIcon.getChildren().clear();
        root.getChildren().setAll(Ui.label("ADMIN VERIFICATION · STEP " + (phone ? "2" : "1") + " OF 2", "card-title"),
                Ui.label(phone ? "Phone verification" : "Email verification", "h1"),
                Ui.label("Research / Admin requires " + (phone ? "an SMS code sent to " : "an email code sent to ") + dest + ". Both steps are required.", "muted"),
                AuthShell.field("Verification code", code), new HBox(10, sendIcon, info), err, devBox, new javafx.scene.layout.HBox(8, send, verify, cancel));
        ((javafx.scene.control.Label) root.getChildren().get(2)).setWrapText(true);
    }

    private VBox devBox;
    private final javafx.scene.layout.StackPane sendIcon = new javafx.scene.layout.StackPane();

    private void refreshDev(boolean phone) {
        if (ctx.devOtp != null && devBox != null) {
            devBox.getChildren().setAll(Ui.badge(DevOtpProvider.LABEL, "warn"),
                    Ui.label("Development only — code: " + ctx.devOtp.lastCode(), "mono"));
        }
    }
}
