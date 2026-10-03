package panel.ui.auth;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.ui.Ui;
import panel.ui.View;
import panel.user.User;
import panel.util.Fmt;

/** Conta do usuário logado. Nunca mostra hash; telefone e e-mail aparecem mascarados. */
public class ProfileView implements View {
    private final AppContext ctx;
    private final VBox account = new VBox(10);
    private final VBox security = new VBox(10);
    private final VBox root = new VBox(18);
    private final javafx.scene.control.ScrollPane scroll = Ui.scroll(root);

    public ProfileView(AppContext ctx) {
        this.ctx = ctx;
        root.setPadding(new javafx.geometry.Insets(24, 28, 28, 28));
        root.getStyleClass().add("page");
        PasswordField current = new PasswordField();
        PasswordField next = new PasswordField();
        PasswordField confirm = new PasswordField();
        var msg = Ui.label("", "muted");
        Button change = Ui.button("Change password", "primary");
        change.setOnAction(e -> {
            msg.getStyleClass().remove("auth-error");
            if (!next.getText().equals(confirm.getText())) {
                fail(msg, "Passwords do not match.");
                return;
            }
            try {
                User u = ctx.sessions.user().orElseThrow().user();
                ctx.userService.changeOwnPassword(u.id(), current.getText().toCharArray(), next.getText().toCharArray());
                current.clear();
                next.clear();
                confirm.clear();
                msg.setText("Password changed.");
            } catch (IllegalArgumentException ex) {
                fail(msg, ex.getMessage());
            }
        });
        VBox pw = Ui.card("Change password", AuthShell.field("Current password", current), AuthShell.field("New password", next),
                AuthShell.field("Confirm new password", confirm), msg, change);
        security.setSpacing(14);
        HBox cols = new HBox(16, colOf(Ui.card("Account", account)), colOf(security, pw, contactEditor()));
        cols.getChildren().forEach(n -> HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS));
        root.getChildren().addAll(Ui.pageHeader("Profile", "Your account and security"), cols);
        refresh();
    }

    private VBox contactEditor() {
        VBox box=Ui.card("Contact details");
        Button edit=Ui.button("Edit email / phone","ghost");box.getChildren().add(edit);
        edit.setOnAction(e->{
            User user=ctx.sessions.user().orElseThrow().user();
            var email=new javafx.scene.control.TextField(user.email());var phone=new javafx.scene.control.TextField(user.phone());
            PasswordField password=new PasswordField();var notice=Ui.label("Current password required. Changing contacts revokes device trust and AdminSession.","muted");notice.setWrapText(true);
            Button save=Ui.button("Save contacts","primary"),cancel=Ui.button("Cancel","ghost");
            Runnable reset=()->{email.clear();phone.clear();password.clear();box.getChildren().setAll(Ui.label("Contact details","card-title"),edit);refresh();};
            cancel.setOnAction(x->reset.run());
            save.setOnAction(x->{
                char[] current=password.getText().toCharArray();String newEmail=email.getText(),newPhone=phone.getText();password.clear();save.setDisable(true);
                Thread worker=new Thread(()->{
                    boolean ok=false;
                    try{ctx.userService.changeOwnContact(user.id(),current,newEmail,newPhone);ok=true;}
                    catch(RuntimeException ignored){}finally{java.util.Arrays.fill(current,'\0');}
                    boolean success=ok;javafx.application.Platform.runLater(()->{save.setDisable(false);if(success)reset.run();else notice.setText("Could not update contacts. Check current password, email and E.164 phone.");});
                },"contact-update");worker.setDaemon(true);worker.start();
            });
            box.getChildren().setAll(Ui.label("Edit contact details","card-title"),AuthShell.field("Email",email),AuthShell.field("Phone (E.164)",phone),AuthShell.field("Current password",password),notice,new HBox(8,save,cancel));
        });return box;
    }

    private static void fail(javafx.scene.control.Label l, String text) {
        l.setText(text);
        l.getStyleClass().add("auth-error");
    }

    private static VBox colOf(javafx.scene.Node... nodes) {
        VBox c = new VBox(16, nodes);
        c.setMinWidth(340);
        c.setMaxWidth(Double.MAX_VALUE);
        return c;
    }

    private void refresh() {
        account.getChildren().clear();
        security.getChildren().clear();
        ctx.sessions.user().ifPresent(s -> {
            User u = s.user();
            account.getChildren().addAll(Ui.kv("Username", u.username()), Ui.kv("Email", u.maskedEmail()), Ui.kv("Phone", u.maskedPhone()),
                    Ui.kvNode("Role", Ui.badge(u.role().name(), u.admin() ? "purple" : "info")), Ui.kv("Created", Fmt.dateTime(u.createdAt())),
                    Ui.kv("Last login", Fmt.dateTime(u.lastLoginAt())), Ui.kv("Password", "••••••••••"));
            String admin = !u.admin() ? "Not applicable" : ctx.adminAccess.adminSession().map(a -> "Authorized · " + a.method().label).orElse("Locked");
            VBox access = Ui.card("Admin access", Ui.kvNode("Status", Ui.badge(admin.toUpperCase(), ctx.adminAccess.hasValidAdminSession() ? "ok" : "muted")),
                    Ui.kv("Session started", Fmt.dateTime(s.loggedInAt())), Ui.kv("Account status", u.status().name()));
            VBox events = Ui.card("Recent security events");
            for (var e : ctx.audit.recentFor(u.username(), 6)) {
                events.getChildren().add(Ui.label(panel.util.Fmt.dateTime(java.time.Instant.parse(e.ts())) + "  " + e.event(), "mono"));
            }
            if (events.getChildren().size() == 1) {
                events.getChildren().add(Ui.label("No events yet", "muted"));
            }
            security.getChildren().addAll(access, events);
        });
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot s) {
        refresh();
    }
}
