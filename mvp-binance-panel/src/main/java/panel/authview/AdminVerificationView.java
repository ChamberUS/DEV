package panel.authview;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.auth.CooldownException;
import panel.auth.TwoFactorResult;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.design.ByxIcon;
import panel.design.ByxOtpInput;
import panel.motion.MotionService;

/**
 * Verificação de administrador V2 (handoff TwoFactorScreen, passo de entrada: AVAILABLE_IF_EXISTING_API).
 * Fluxo real: e-mail (6 dígitos, gerado localmente) → SMS (comprimento definido pelo Twilio Verify, campo
 * único) → opcional "Trust this Mac". Providers não configurados: NOT CONFIGURED com o status real. O código
 * digitado nunca é registrado; as chamadas de provider rodam fora da thread FX e o resultado só vale enquanto
 * a tela existe. Concluir chama onSuccess; quem aplica a rota é o roteador (ticket).
 */
public final class AdminVerificationView extends StackPane {
    /** Fluxo real (TwoFactorFlow) visto pela tela. */
    public interface Flow {
        void sendEmailCode();

        TwoFactorResult verifyEmail(String code);

        void sendSmsCode();

        TwoFactorResult verifySms(String code);

        void finish(boolean trustDevice);

        long resendSeconds(boolean phone);

        void cancel();
    }

    /** Abre o desafio (AdminAccessService.startTwoFactor). Lança NotConfigured ou outro erro real. */
    public interface Starter {
        Flow start();
    }

    /** Providers não configurados (TwoFactorNotConfiguredException), com o status real de cada um. */
    public static final class NotConfigured extends RuntimeException {
        public final String detail;

        public NotConfigured(String detail) {
            super("not configured");
            this.detail = detail;
        }
    }

    private final MotionService motion;
    private final Executor worker;
    private final Executor fx;
    private final Runnable onSuccess;
    private final Runnable onCancel;
    private final String maskedEmail;
    private final String maskedPhone;
    private final boolean devProvider;
    private final VBox card = new VBox(16);
    private final Timeline countdown;
    private Flow flow;
    private boolean phone;
    private boolean busy;
    private boolean closed;
    private String status;
    private ByxButton send;
    private ByxButton verify;
    private ByxOtpInput emailCode;
    private ByxField smsCode;
    private Label message;
    private String screen = "CHECKING";

    public AdminVerificationView(MotionService motion, Starter starter, Executor worker, Executor fx, String maskedEmail,
            String maskedPhone, boolean devProvider, Runnable onSuccess, Runnable onCancel) {
        this.motion = motion;
        this.worker = worker;
        this.fx = fx;
        this.onSuccess = onSuccess;
        this.onCancel = onCancel;
        this.maskedEmail = maskedEmail;
        this.maskedPhone = maskedPhone;
        this.devProvider = devProvider;
        getStyleClass().add("byx-admin-verify");
        card.getStyleClass().addAll("byx-dialog", "byx-verify-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setPrefWidth(448);
        getChildren().add(card);
        StackPane.setAlignment(card, Pos.CENTER);
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancel();
                e.consume();
            }
        });
        countdown = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> refreshSend()));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
        render(List.of(title("Verification required", "Checking verification providers…")));
        worker.execute(() -> {
            try {
                Flow f = starter.start();
                fx.execute(() -> {
                    if (closed) {
                        f.cancel();
                        return;
                    }
                    flow = f;
                    renderStep();
                });
            } catch (NotConfigured e) {
                fx.execute(() -> blocked("Verification providers not configured", e.detail));
            } catch (RuntimeException e) {
                fx.execute(() -> blocked("Verification unavailable",
                        "Check your session and set an email and an E.164 phone number in Profile."));
            }
        });
    }

    public String screen() {
        return screen;
    }

    public String statusText() {
        return status;
    }

    private VBox title(String t, String sub) {
        Label a = new Label(t);
        a.getStyleClass().add("byx-auth-title");
        Label b = new Label(sub);
        b.getStyleClass().add("byx-auth-sub");
        b.setWrapText(true);
        return new VBox(4, a, b);
    }

    private void render(List<Node> nodes) {
        card.getChildren().setAll(nodes);
        if (devProvider) {
            card.getChildren().add(ByxBadge.of("DEVELOPMENT AUTH PROVIDER", ByxBadge.Tone.WARNING));
        }
    }

    private ByxButton cancelButton(String text) {
        ByxButton b = new ByxButton(text, ByxButton.Variant.GHOST, motion);
        b.setOnAction(e -> cancel());
        return b;
    }

    private void blocked(String title, String detail) {
        if (closed) {
            return;
        }
        screen = "BLOCKED";
        status = title;
        ByxButton back = cancelButton("Back");
        render(List.of(title("Admin verification", "Research requires an administrator verification."),
                ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED),
                new ByxBanner(ByxBanner.Kind.WARNING, title, detail), back));
        back.requestFocus();
    }

    private void renderStep() {
        screen = phone ? "SMS" : "EMAIL";
        message = new Label();
        message.getStyleClass().add("byx-verify-message");
        message.setWrapText(true);
        send = new ByxButton("Send code", ByxButton.Variant.SECONDARY, motion);
        verify = new ByxButton(phone ? "Verify phone" : "Verify email", ByxButton.Variant.PRIMARY, motion);
        verify.setDefaultButton(true);
        Node input;
        if (phone) {
            smsCode = ByxField.text("SMS code");
            smsCode.input().getStyleClass().add("byx-code-input");
            smsCode.input().setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("[0-9]{0,10}") ? c : null));
            input = smsCode;
        } else {
            emailCode = new ByxOtpInput(6, motion);
            emailCode.setOnComplete(() -> verify.requestFocus());
            input = emailCode;
        }
        Label destination = new Label((phone ? "Phone " : "Email ") + (phone ? maskedPhone : maskedEmail));
        destination.getStyleClass().add("byx-auth-sub");
        send.setOnAction(e -> action(() -> {
            if (phone) {
                flow.sendSmsCode();
            } else {
                flow.sendEmailCode();
            }
            return Boolean.TRUE;
        }, ok -> {
            say("Code sent to " + (phone ? maskedPhone : maskedEmail) + ". It expires in 5 minutes.", false);
            focusInput();
        }));
        verify.setOnAction(e -> {
            String code = phone ? smsCode.input().getText() : new String(emailCode.value());
            if (phone) {
                smsCode.input().clear();
            } else {
                emailCode.clear();
            }
            action(() -> phone ? flow.verifySms(code) : flow.verifyEmail(code), result -> {
                if (result == TwoFactorResult.OK) {
                    if (!phone) {
                        phone = true;
                        renderStep();
                    } else {
                        complete();
                    }
                } else {
                    if (!phone) {
                        emailCode.setInvalid(true);
                    } else {
                        smsCode.setError(" ");
                    }
                    say(switch (result) {
                        case INVALID -> "That code is not valid. Try again.";
                        case EXPIRED -> "That code expired. Start the verification again.";
                        case TOO_MANY_ATTEMPTS -> "Too many attempts. Request a new code after the cooldown.";
                        default -> "Send a code for this step first.";
                    }, true);
                }
            });
        });
        HBox actions = new HBox(8, send, verify, cancelButton("Cancel"));
        actions.setAlignment(Pos.CENTER_LEFT);
        render(List.of(title("Verification required", "Admin verification · step " + (phone ? 2 : 1) + " of 2"),
                destination, input, message, actions));
        refreshSend();
        send.requestFocus();
    }

    private void focusInput() {
        if (phone) {
            smsCode.input().requestFocus();
        } else {
            emailCode.focusFirstEmpty();
        }
    }

    private void say(String text, boolean error) {
        status = text;
        message.setText(text);
        message.getStyleClass().remove("error");
        if (error) {
            message.getStyleClass().add("error");
        }
    }

    private <T> void action(Supplier<T> work, Consumer<T> success) {
        if (busy || closed) {
            return;
        }
        busy = true;
        verify.setLoading(true);
        refreshSend();
        worker.execute(() -> {
            try {
                T value = work.get();
                fx.execute(() -> {
                    if (closed) {
                        return;
                    }
                    busy = false;
                    verify.setLoading(false);
                    refreshSend();
                    success.accept(value);
                });
            } catch (RuntimeException e) {
                fx.execute(() -> {
                    if (closed) {
                        return;
                    }
                    busy = false;
                    verify.setLoading(false);
                    refreshSend();
                    say(e instanceof CooldownException ? e.getMessage()
                            : "Verification unavailable. Check the providers, your session or the Keychain and try again.", true);
                });
            }
        });
    }

    private void refreshSend() {
        if (flow == null || send == null || closed) {
            return;
        }
        long seconds = flow.resendSeconds(phone);
        send.setDisable(busy || seconds > 0);
        send.setText(seconds > 0 ? "Resend in " + seconds + "s" : "Send code");
    }

    private void complete() {
        screen = "COMPLETE";
        countdown.stop();
        StackPane okc = new StackPane(ByxIcon.of("check", 30, null));
        okc.getStyleClass().add("byx-auth-okc");
        okc.setMaxSize(64, 64);
        CheckBox trust = new CheckBox("Trust this Mac for 30 days");
        trust.getStyleClass().add("byx-check");
        trust.setSelected(false); // opcional, desligado a menos que escolhido
        ByxButton next = new ByxButton("Continue to Research", ByxButton.Variant.PRIMARY, motion).wide();
        next.setDefaultButton(true);
        message = new Label();
        message.getStyleClass().add("byx-verify-message");
        message.setWrapText(true);
        verify = next;
        next.setOnAction(e -> {
            boolean remember = trust.isSelected();
            action(() -> {
                flow.finish(remember);
                return Boolean.TRUE;
            }, ok -> onSuccess.run());
        });
        render(List.of(okc, title("Identity verified", "Email and SMS verified."),
                ByxBadge.of("EMAIL + SMS VERIFIED", ByxBadge.Tone.POSITIVE), trust, message, next));
        next.requestFocus();
    }

    private void cancel() {
        dispose();
        onCancel.run();
    }

    /** Sai da tela: cancela o desafio e descarta qualquer resultado que ainda chegue. */
    public void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        countdown.stop();
        if (flow != null) {
            flow.cancel();
        }
    }

    public boolean closed() {
        return closed;
    }
}
