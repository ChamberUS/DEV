package panel.authview;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.design.ByxIcon;
import panel.motion.MotionService;
import panel.user.PasswordPolicy;
import panel.user.User;

/**
 * Telas de entrada V2 sobre o {@link AuthLayout}. Cada painel é uma rota do roteador do app
 * ({@code auth:login}, {@code auth:forgot}, {@code auth:setup}, {@code auth:change-password}); links só pedem
 * rota. Login renderiza o estado do {@link LoginController} (serviço real). Sem endpoint, o painel diz que não
 * está disponível e nunca afirma que algo foi enviado, criado ou alterado.
 */
public final class AuthScreens {
    public static final String LOGIN = "auth:login";
    public static final String FORGOT = "auth:forgot";
    public static final String SETUP = "auth:setup";
    public static final String CHANGE_PASSWORD = "auth:change-password";
    public static final String POLICY_HINT = "At least " + PasswordPolicy.MIN_LENGTH + " characters, different from your username.";

    /** Operações reais usadas pelas telas (AuthService / UserService). */
    public interface Services {
        User login(String identifier, char[] password);

        void createInitialAdmin(String username, String email, char[] password, String phone);

        void changeOwnPassword(long userId, char[] current, char[] next);

        /** Encerra a sessão atual (sair, ou sessão aberta por tentativa descartada). */
        void endSession();
    }

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final MotionService motion;
    private final Services services;
    private final Consumer<String> request;
    private final AuthLayout layout;
    private final LoginController login;
    private final Runnable onPasswordChanged;
    private final Consumer<String> onSetupDone;
    private String route;
    private String notice;
    private User changing;
    private Timeline countdown;
    private final java.util.concurrent.ExecutorService worker;

    // campos do login: persistem entre estados (identificador mantido; senha sempre limpa após envio)
    private final ByxField identifier = ByxField.text("Email or username");
    private final ByxField password = ByxField.password("Password");
    private final ByxButton signIn;

    /**
     * request: pede rota ao roteador; onLoggedIn: sessão real aberta (o roteador decide o destino);
     * onPasswordChanged: troca obrigatória concluída; onSetupDone: primeiro admin criado (mensagem para o login).
     */
    public AuthScreens(MotionService motion, Services services, Consumer<String> request, Consumer<User> onLoggedIn,
            Runnable onPasswordChanged, Consumer<String> onSetupDone, Consumer<String> openPublic, String devBadge) {
        this.motion = motion;
        this.services = services;
        this.request = request;
        this.onPasswordChanged = onPasswordChanged;
        this.onSetupDone = onSetupDone;
        layout = new AuthLayout(motion, devBadge, openPublic);
        worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "login");
            t.setDaemon(true);
            return t;
        });
        login = new LoginController(services::login, worker, Platform::runLater, onLoggedIn, services::endSession);
        login.stateProperty().addListener((o, a, s) -> {
            layout.brand().setLoading(s == LoginController.State.LOADING);
            if (LOGIN.equals(route)) {
                renderLogin();
            }
        });
        identifier.input().setPromptText("you@example.com");
        password.input().setPromptText("Enter your password");
        Button forgot = link("Forgot password?", () -> request.accept(FORGOT));
        password.setAccessory(forgot);
        signIn = new ByxButton("Sign in", ByxButton.Variant.PRIMARY, motion).wide();
        signIn.setDefaultButton(true);
        signIn.setOnAction(e -> submit());
        layout.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && LOGIN.equals(route)) {
                login.reset(); // Esc: do erro de volta ao padrão
                e.consume();
            }
        });
    }

    public Node node() {
        return layout;
    }

    public AuthLayout layout() {
        return layout;
    }

    public LoginController loginController() {
        return login;
    }

    public String route() {
        return route;
    }

    /** Aplica a rota de entrada (chamado só pelo roteador). notice: aviso real (ex.: sessão expirada). */
    public void show(String id, String noticeText, User mustChange) {
        stopCountdown();
        route = id;
        notice = noticeText;
        changing = mustChange;
        switch (id) {
            case LOGIN -> renderLogin();
            case FORGOT -> renderForgot();
            case SETUP -> renderSetup();
            case CHANGE_PASSWORD -> renderChangePassword();
            default -> throw new IllegalArgumentException(id);
        }
    }

    private static Button link(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("byx-link");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static VBox heading(String title, String sub) {
        Label t = new Label(title);
        t.getStyleClass().add("byx-auth-title");
        Label s = new Label(sub);
        s.getStyleClass().add("byx-auth-sub");
        s.setWrapText(true);
        return new VBox(4, t, s);
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("byx-auth-sub");
        l.setWrapText(true);
        return l;
    }

    // ---------------------------------------------------------------- login

    private void submit() {
        String id = identifier.input().getText();
        char[] pw = password.input().getText().toCharArray();
        password.input().clear(); // a senha não fica no modelo de UI
        login.submit(id, pw);
    }

    private void renderLogin() {
        LoginController.State s = login.state();
        if (s != LoginController.State.RATE_LIMITED) {
            stopCountdown(); // a contagem só existe enquanto o bloqueio existe
        }
        boolean busy = s == LoginController.State.LOADING || s == LoginController.State.RATE_LIMITED || s == LoginController.State.SUCCESS;
        identifier.setDisable(busy);
        password.setDisable(busy);
        signIn.setLoading(s == LoginController.State.LOADING);
        signIn.setDisable(s == LoginController.State.RATE_LIMITED || s == LoginController.State.SUCCESS);
        boolean invalid = s == LoginController.State.INVALID;
        password.input().pseudoClassStateChanged(ERROR, invalid); // como a referência: só a senha marcada
        signIn.setText(switch (s) {
            case LOADING -> "Signing in…";
            case UNAVAILABLE -> "Retry";
            case RATE_LIMITED -> "Try again in " + clock(login.retryAfter());
            default -> "Sign in";
        });
        List<Node> nodes = new ArrayList<>();
        if (s == LoginController.State.SUCCESS) {
            StackPane okc = new StackPane(ByxIcon.of("check", 30, null));
            okc.getStyleClass().add("byx-auth-okc");
            okc.setMaxSize(64, 64);
            StackPane bar = new StackPane();
            bar.getStyleClass().add("byx-pbar");
            nodes.add(okc);
            nodes.add(heading("Signed in", "Opening your workspace…"));
            nodes.add(bar);
            layout.show("Sign in", nodes);
            return;
        }
        nodes.add(heading("Sign in", "Access the BYX-MVP terminal."));
        ByxBanner banner = switch (s) {
            case INVALID -> new ByxBanner(ByxBanner.Kind.ERROR, "Sign-in failed", "Email, username or password is incorrect.");
            case DISABLED -> new ByxBanner(ByxBanner.Kind.ERROR, "Account disabled", "This account is disabled. Contact an administrator.");
            case UNAVAILABLE -> new ByxBanner(ByxBanner.Kind.WARNING, "Sign-in unavailable",
                    "The local account store could not be read. Nothing was changed.");
            case RATE_LIMITED -> new ByxBanner(ByxBanner.Kind.WARNING, "Too many attempts", "Wait before trying again.");
            default -> notice == null ? null : new ByxBanner(ByxBanner.Kind.INFO, "Notice", notice);
        };
        if (banner != null) {
            nodes.add(banner);
        }
        nodes.add(identifier);
        nodes.add(password);
        nodes.add(signIn);
        nodes.add(note("Accounts are created by an administrator."));
        layout.show("Sign in", nodes);
        if (s == LoginController.State.RATE_LIMITED && countdown == null) {
            startCountdown();
        }
        if (!busy) {
            (s == LoginController.State.INVALID ? password.input() : identifier.input().getText().isBlank() ? identifier.input() : password.input())
                    .requestFocus();
        }
    }

    private static String clock(Duration d) {
        long s = Math.max(0, d.toSeconds() + (d.toMillisPart() > 0 ? 1 : 0));
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    /** Contagem do bloqueio real; tempo lógico, igual em FULL, REDUCED e OFF. */
    private void startCountdown() {
        stopCountdown();
        Duration[] left = {login.retryAfter()};
        countdown = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> {
            left[0] = left[0].minusSeconds(1);
            if (left[0].isNegative() || left[0].isZero()) {
                stopCountdown();
                login.lockoutEnded();
            } else {
                signIn.setText("Try again in " + clock(left[0]));
            }
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    // ---------------------------------------------------------------- forgot (sem backend)

    private void renderForgot() {
        ByxButton back = new ByxButton("Back to sign in", ByxButton.Variant.SECONDARY, motion).wide();
        back.setOnAction(e -> request.accept(LOGIN));
        layout.show("Reset password", List.of(
                heading("Reset your password", "Password recovery for this terminal."),
                new ByxBanner(ByxBanner.Kind.INFO, "Not available in this build",
                        "Password recovery by email needs a backend service this build does not have. Nothing was sent. "
                                + "An administrator can set a temporary password for you; you will choose a new one at your next sign-in."),
                back));
        back.requestFocus();
    }

    // ---------------------------------------------------------------- primeiro administrador (real)

    private void renderSetup() {
        ByxField user = ByxField.text("Username");
        ByxField email = ByxField.text("Email");
        ByxField phone = ByxField.text("Phone (optional)");
        phone.input().setPromptText("+55 11 90000-0000");
        ByxField pw = ByxField.password("Password");
        ByxField pw2 = ByxField.password("Confirm password");
        ByxButton create = new ByxButton("Create administrator", ByxButton.Variant.PRIMARY, motion).wide();
        create.setDefaultButton(true);
        create.setOnAction(e -> {
            pw2.setError(null);
            pw.setError(null);
            if (!pw.input().getText().equals(pw2.input().getText())) {
                pw2.setError("Passwords do not match.");
                return;
            }
            char[] secret = pw.input().getText().toCharArray();
            try {
                services.createInitialAdmin(user.input().getText(), email.input().getText(), secret, phone.input().getText());
                pw.input().clear();
                pw2.input().clear();
                onSetupDone.accept("Administrator created. Sign in to continue.");
            } catch (RuntimeException ex) {
                pw.setError(ex instanceof IllegalArgumentException || ex instanceof panel.security.AccessDeniedException
                        ? ex.getMessage() : "Could not create the administrator.");
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
        });
        VBox fields = new VBox(12, user, email, phone, pw, pw2);
        layout.show("First run", List.of(
                heading("Create the first administrator", "No account exists yet. This screen is only available once."),
                fields, note(POLICY_HINT), create, note("Email and phone are used for admin two-factor verification.")));
        user.input().requestFocus();
    }

    // ---------------------------------------------------------------- troca obrigatória (real)

    private void renderChangePassword() {
        ByxField current = ByxField.password("Temporary password");
        ByxField next = ByxField.password("New password");
        ByxField confirm = ByxField.password("Confirm new password");
        ByxButton change = new ByxButton("Change password", ByxButton.Variant.PRIMARY, motion).wide();
        change.setDefaultButton(true);
        ByxButton out = new ByxButton("Sign out", ByxButton.Variant.SECONDARY, motion).wide();
        out.setOnAction(e -> {
            services.endSession();
            request.accept(LOGIN);
        });
        change.setOnAction(e -> {
            confirm.setError(null);
            next.setError(null);
            if (!next.input().getText().equals(confirm.input().getText())) {
                confirm.setError("Passwords do not match.");
                return;
            }
            char[] a = current.input().getText().toCharArray();
            char[] b = next.input().getText().toCharArray();
            try {
                services.changeOwnPassword(changing.id(), a, b);
                current.input().clear();
                next.input().clear();
                confirm.input().clear();
                onPasswordChanged.run();
            } catch (IllegalArgumentException ex) {
                next.setError(ex.getMessage());
            } finally {
                java.util.Arrays.fill(a, '\0');
                java.util.Arrays.fill(b, '\0');
            }
        });
        VBox fields = new VBox(12, current, next, confirm);
        layout.show("Change password", List.of(
                heading("Choose a new password", "Your password was set by an administrator. Choose a new one to continue."),
                fields, note(POLICY_HINT), change, out));
        current.input().requestFocus();
    }

    public void dispose() {
        stopCountdown();
        login.dispose();
        worker.shutdownNow();
        layout.dispose();
    }
}
