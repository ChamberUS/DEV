package panel.ui.auth;

import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.auth.AuthService.LoginException;
import panel.ui.Ui;
import panel.user.User;

public class LoginView {
    private final Node node;

    public LoginView(AppContext ctx, String notice, Consumer<User> onSuccess) {
        TextField id = new TextField();
        id.setPromptText("Email or username");
        id.setAccessibleText("Email or username");
        PasswordField pw = new PasswordField();
        pw.setPromptText("Password");
        pw.setAccessibleText("Password");
        var err = AuthShell.error();
        Button go = Ui.button("Sign In", "primary");
        go.setDefaultButton(true);
        go.setMaxWidth(Double.MAX_VALUE);
        go.setPrefHeight(38);

        VBox form = AuthShell.form("Sign in", "Access the trading terminal");
        var lock = ctx.icons.icon("lock", 22, "warn");
        form.getChildren().add(0, lock.node());
        lock.play();
        if (notice != null) {
            form.getChildren().add(AuthShell.notice(notice));
        }
        form.getChildren().addAll(AuthShell.field("Email / Username", id), AuthShell.field("Password", pw), err, go,
                Ui.label("🔒  Secure local session", "muted"));

        go.setOnAction(e -> {
            err.setText("");
            char[] pass = pw.getText().toCharArray();
            String ident = id.getText();
            go.setDisable(true);
            go.setText("Signing in…");
            Thread t = new Thread(() -> {
                String message = null;
                User user = null;
                try {
                    user = ctx.auth.login(ident, pass);
                } catch (LoginException ex) {
                    message = switch (ex.failure) {
                        case INVALID_CREDENTIALS -> "Invalid username or password.";
                        case ACCOUNT_DISABLED -> "This account is disabled. Contact an administrator.";
                        case RATE_LIMITED -> "Too many attempts. Try again in " + (ex.retryAfter.toSeconds() + 1) + "s.";
                    };
                } catch (RuntimeException ex) {
                    message = "Could not sign in. Please try again.";
                }
                String m = message;
                User u = user;
                java.util.Arrays.fill(pass, '\0');
                Platform.runLater(() -> {
                    pw.clear();
                    if (u == null) {
                        go.setDisable(false);
                        go.setText("Sign In");
                    }
                    if (u != null) {
                        go.setDisable(true);
                        go.setText("✓  Signed in");
                        javafx.animation.PauseTransition ok = new javafx.animation.PauseTransition(ctx.motion.scale(javafx.util.Duration.millis(260)));
                        ok.setOnFinished(x -> onSuccess.accept(u));
                        ok.play();
                    } else {
                        err.setText(m);
                        ctx.motion.shake(form, 4);
                        pw.requestFocus();
                    }
                });
            }, "login");
            t.setDaemon(true);
            t.start();
        });
        node = AuthShell.of(form, ctx.motion);
        Platform.runLater(id::requestFocus);
    }

    public Node node() {
        return node;
    }
}
