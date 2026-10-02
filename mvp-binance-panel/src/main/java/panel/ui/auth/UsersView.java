package panel.ui.auth;

import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.security.AccessDeniedException;
import panel.security.Role;
import panel.ui.Ui;
import panel.ui.View;
import panel.user.User;
import panel.user.UserStatus;
import panel.util.Fmt;

/** Gestão de usuários (ADMIN autorizado). Sem exclusão irreversível. Toda operação passa pela barreira de serviço. */
public class UsersView implements View {
    private final AppContext ctx;
    private final TableView<User> table = new TableView<>();
    private final VBox root = new VBox(14);
    private final javafx.scene.control.Label msg = Ui.label("", "muted");

    public UsersView(AppContext ctx) {
        this.ctx = ctx;
        table.getColumns().add(col("Username", 160, User::username));
        table.getColumns().add(col("Email", 230, User::email));
        table.getColumns().add(col("Role", 90, u -> u.role().name()));
        table.getColumns().add(col("Status", 90, u -> u.status().name()));
        table.getColumns().add(col("Last login", 160, u -> Fmt.dateTime(u.lastLoginAt())));
        table.getColumns().add(col("Created", 160, u -> Fmt.dateTime(u.createdAt())));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No users", "muted"));

        Button create = Ui.button("Create user", "primary");
        create.setOnAction(e -> createDialog());
        Button toggle = Ui.button("Disable / Enable", "ghost");
        toggle.setOnAction(e -> act(u -> ctx.userService.setStatus(u.id(), u.active() ? UserStatus.DISABLED : UserStatus.ACTIVE), "Status updated."));
        Button role = Ui.button("Change role", "ghost");
        role.setOnAction(e -> act(u -> {
            ChoiceDialog<Role> d = new ChoiceDialog<>(u.role(), Role.values());
            d.setHeaderText("Role for " + u.username());
            d.getDialogPane().getStylesheets().add(getClass().getResource("/panel/panel.css").toExternalForm());
            d.getDialogPane().getStyleClass().add("dialog");
            d.showAndWait().ifPresent(r -> ctx.userService.changeRole(u.id(), r));
        }, "Role updated."));
        Button reset = Ui.button("Reset password", "ghost");
        reset.setOnAction(e -> act(u -> {
            PasswordField pw = new PasswordField();
            pw.setPromptText("Temporary password");
            Dialog<ButtonType> d = dialog("Reset password for " + u.username(), new VBox(8, AuthShell.field("Temporary password", pw), Ui.label("The user must change it at next sign in.", "muted")));
            if (d.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
                ctx.userService.resetPassword(u.id(), pw.getText().toCharArray());
            }
        }, "Password reset. The user must change it at next sign in."));

        HBox bar = new HBox(8, create, toggle, role, reset, Ui.spacer(), msg);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        root.setPadding(new Insets(24, 28, 24, 28));
        root.getChildren().addAll(Ui.pageHeader("Users", "Accounts and roles. Public sign-up does not exist; users are created here."), bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    private void act(java.util.function.Consumer<User> action, String ok) {
        User u = table.getSelectionModel().getSelectedItem();
        if (u == null) {
            msg.setText("Select a user first.");
            return;
        }
        try {
            action.accept(u);
            msg.setText(ok);
        } catch (AccessDeniedException | IllegalArgumentException ex) {
            msg.setText(ex.getMessage());
        }
        reload();
    }

    private void createDialog() {
        TextField user = new TextField(), email = new TextField(), phone = new TextField();
        PasswordField pw = new PasswordField();
        ComboBox<Role> role = new ComboBox<>();
        role.getItems().addAll(Role.values());
        role.setValue(Role.USER);
        VBox form = new VBox(8, AuthShell.field("Username", user), AuthShell.field("Email", email), AuthShell.field("Phone (optional)", phone),
                AuthShell.field("Temporary password", pw), AuthShell.field("Role", role), Ui.label("The user must change the password at first sign in.", "muted"));
        Dialog<ButtonType> d = dialog("Create user", form);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
            try {
                ctx.userService.createUser(user.getText(), email.getText(), pw.getText().toCharArray(), phone.getText(), role.getValue());
                msg.setText("User created.");
            } catch (AccessDeniedException | IllegalArgumentException ex) {
                msg.setText(ex.getMessage());
            }
            reload();
        }
    }

    private Dialog<ButtonType> dialog(String title, Node content) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().getStylesheets().add(getClass().getResource("/panel/panel.css").toExternalForm());
        d.getDialogPane().getStyleClass().add("dialog");
        return d;
    }

    private void reload() {
        if (!ctx.adminAccess.hasValidAdminSession()) {
            table.getItems().clear();
            return;
        }
        try {
            List<User> users = ctx.userService.listUsers();
            User sel = table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(users);
            if (sel != null) {
                users.stream().filter(x -> x.id() == sel.id()).findFirst().ifPresent(x -> table.getSelectionModel().select(x));
            }
        } catch (AccessDeniedException e) {
            table.getItems().clear();
        }
    }

    private static TableColumn<User, String> col(String t, double w, Function<User, String> f) {
        TableColumn<User, String> c = new TableColumn<>(t);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(f.apply(d.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onSnapshot(Snapshot s) {
        reload();
    }
}
