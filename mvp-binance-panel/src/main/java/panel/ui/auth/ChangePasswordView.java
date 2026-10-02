package panel.ui.auth;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.ui.Ui;
import panel.user.User;

/** Troca obrigatória após login com senha temporária. */
public class ChangePasswordView {
    private final Node node;

    public ChangePasswordView(AppContext ctx, User user, Runnable onDone, Runnable onCancel) {
        PasswordField current = new PasswordField();
        PasswordField next = new PasswordField();
        PasswordField confirm = new PasswordField();
        var err = AuthShell.error();
        Button go = Ui.button("Change password", "primary");
        go.setDefaultButton(true);
        go.setMaxWidth(Double.MAX_VALUE);
        go.setPrefHeight(38);
        Button cancel = Ui.button("Sign out", "ghost");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> onCancel.run());

        VBox form = AuthShell.form("Change password", "Your password was set by an administrator. Choose a new one to continue.");
        form.getChildren().addAll(AuthShell.field("Temporary password", current), AuthShell.field("New password", next), AuthShell.field("Confirm new password", confirm), err, go, cancel);
        go.setOnAction(e -> {
            err.setText("");
            if (!next.getText().equals(confirm.getText())) {
                err.setText("Passwords do not match.");
                return;
            }
            try {
                ctx.userService.changeOwnPassword(user.id(), current.getText().toCharArray(), next.getText().toCharArray());
                onDone.run();
            } catch (IllegalArgumentException ex) {
                err.setText(ex.getMessage());
            }
        });
        node = AuthShell.of(form, ctx.motion);
    }

    public Node node() {
        return node;
    }
}
