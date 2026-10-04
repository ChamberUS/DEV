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
        Button go = Ui.button("Continue", "primary");
        go.setDefaultButton(true);
        go.setMaxWidth(Double.MAX_VALUE);
        go.setPrefHeight(44);

        VBox form = AuthShell.form("Sign in", null);
        if (notice != null) {
            form.getChildren().add(AuthShell.notice(notice));
        }
        form.getChildren().addAll(AuthShell.field("Email / Username", id), AuthShell.field("Password", pw), err, go,
                Ui.label("Admin verification is required when opening Research.", "muted"));

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
                        go.setText("Continue");
                    }
                    if (u != null) {
                        go.setDisable(true);
                        go.setText("✓  Signed in");
                        onSuccess.accept(u);
                    } else {
                        err.setText(m);
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
