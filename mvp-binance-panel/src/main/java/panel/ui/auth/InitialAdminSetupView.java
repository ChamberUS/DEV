package panel.ui.auth;

import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.ui.Ui;

/** Só aparece quando não existe nenhum usuário. Cria o primeiro ADMIN; nada de credenciais padrão. */
public class InitialAdminSetupView {
    private final Node node;

    public InitialAdminSetupView(AppContext ctx, Consumer<String> onDone) {
        TextField user = new TextField();
        TextField email = new TextField();
        TextField phone = new TextField();
        phone.setPromptText("+55 11 90000-0000 (optional)");
        PasswordField pw = new PasswordField();
        PasswordField pw2 = new PasswordField();
        var err = AuthShell.error();
        Button go = Ui.button("Create administrator", "primary");
        go.setDefaultButton(true);
        go.setMaxWidth(Double.MAX_VALUE);
        go.setPrefHeight(38);

        VBox form = AuthShell.form("Initial admin setup", "No users exist yet. Create the first administrator. This screen is only available once.");
        form.getChildren().addAll(AuthShell.field("Username", user), AuthShell.field("Email", email), AuthShell.field("Phone (optional)", phone),
                AuthShell.field("Password", pw), AuthShell.field("Confirm password", pw2), err, go,
                Ui.label("Phone and email are used for admin two-factor verification.", "muted"));
        go.setOnAction(e -> {
            err.setText("");
            if (!pw.getText().equals(pw2.getText())) {
                err.setText("Passwords do not match.");
                return;
            }
            try {
                ctx.userService.createInitialAdmin(user.getText(), email.getText(), pw.getText().toCharArray(), phone.getText());
                pw.clear();
                pw2.clear();
                onDone.accept("Administrator created. Sign in to continue.");
            } catch (RuntimeException ex) {
                err.setText(ex instanceof IllegalArgumentException || ex instanceof panel.security.AccessDeniedException ? ex.getMessage() : "Could not create the administrator.");
            }
        });
        node = AuthShell.of(form, ctx.motion);
    }

    public Node node() {
        return node;
    }
}
