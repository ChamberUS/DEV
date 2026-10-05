package panel.accountview;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.user.User;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * Profile V2. Mostra só dados reais da conta (usuário, e-mail e telefone mascarados, papel, criação, último acesso).
 * O nome de exibição não existe na API: "Not provided by the API". A edição real é a dos contatos (e-mail e telefone,
 * com a senha atual), nunca um "salvo" fingido; erro mantém o texto digitado. Sair da tela com edição pendente pergunta.
 */
public final class ProfileScreen implements View {
    private final AccountData data;
    private final MotionService motion;
    private final Consumer<String> navigate;
    private final Runnable signOut;
    private final ScrollPane scroll;
    private final VBox identity = new VBox(10);
    private final VBox details = new VBox(0);
    private final VBox editor = new VBox(12);
    private final VBox detailsPanel;
    private ByxField email;
    private ByxField phone;
    private ByxField password;
    private ByxBanner banner;
    private ByxButton save;
    private boolean editing;
    private boolean saving;
    private String originalEmail = "";
    private String originalPhone = "";
    private final HBox actions = new HBox(10);
    private ByxButton edit;

    public ProfileScreen(MotionService motion, AccountData data, Consumer<String> navigate, Runnable signOut) {
        this.motion = motion;
        this.data = data;
        this.navigate = navigate;
        this.signOut = signOut;
        detailsPanel = Kit.panel("Account details", details, editor);
        detailsPanel.setId("profile-details");
        identity.getStyleClass().add("byx-panel");
        identity.setId("profile-identity");
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        ColumnConstraints left = new ColumnConstraints(340, 340, 340);
        ColumnConstraints main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        main.setMinWidth(0);
        grid.getColumnConstraints().addAll(left, main);
        grid.add(identity, 0, 0);
        grid.add(detailsPanel, 1, 0);
        grid.add(links(), 0, 1, 2, 1);
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Profile", "Your identity in BYX-MVP. Values come from your account and are read-only unless marked editable."), grid);
        scroll = Kit.scroll(page);
        editor.setVisible(false);
        editor.setManaged(false);
        render();
    }

    private Node links() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(12);
        String[][] items = {{"Security", "Password, verification and recovery", "t-security"}, {"Sessions and devices", "Where you are signed in", "t-sessions"},
            {"Notifications", "Alerts from the system, research and BYX", "t-notifications"}, {"Preferences", "Appearance and motion", "t-settings"},
            {"Activity", "Your account and security history", "t-account-activity"}};
        for (int i = 0; i < items.length; i++) {
            String[] it = items[i];
            ByxButton b = new ByxButton(it[0], ByxButton.Variant.SECONDARY, motion);
            b.setOnAction(e -> navigate.accept(it[2]));
            VBox cell = new VBox(6, b, Kit.dim(it[1]));
            g.add(cell, i % 3, i / 3);
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(33.3);
            if (i < 3) {
                g.getColumnConstraints().add(c);
            }
        }
        return Kit.panel("Account", g);
    }

    boolean editing() {
        return editing;
    }

    ByxField emailField() {
        return email;
    }

    ByxField passwordField() {
        return password;
    }

    ByxBanner banner() {
        return banner;
    }

    void startEdit() {
        User u = data.user().orElse(null);
        if (u == null || editing) {
            return;
        }
        editing = true;
        originalEmail = u.email() == null ? "" : u.email();
        originalPhone = u.phone() == null ? "" : u.phone();
        email = ByxField.text("Email");
        email.input().setText(originalEmail);
        phone = ByxField.text("Phone (E.164)");
        phone.input().setText(originalPhone);
        password = ByxField.password("Current password");
        banner = null;
        save = new ByxButton("Save changes", ByxButton.Variant.PRIMARY, motion);
        ByxButton cancel = new ByxButton("Cancel", ByxButton.Variant.SECONDARY, motion);
        save.setOnAction(e -> submit());
        cancel.setOnAction(e -> cancelEdit());
        Label note = Kit.muted("Username and role can not be changed here. Changing contacts needs your current password and revokes device trust and the admin session.");
        editor.getChildren().setAll(email, phone, password, note, new HBox(8, cancel, save));
        editor.setVisible(true);
        editor.setManaged(true);
        render();
        email.input().requestFocus(); // foco síncrono, sem runLater (um diálogo pode abrir entre os dois)
    }

    private void cancelEdit() {
        discardChanges();
        render();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return editing && (!email.input().getText().equals(originalEmail) || !phone.input().getText().equals(originalPhone)
                || !password.input().getText().isEmpty());
    }

    @Override
    public void discardChanges() {
        if (!editing) {
            return;
        }
        editing = false;
        saving = false;
        password.input().clear();
        editor.getChildren().clear();
        editor.setVisible(false);
        editor.setManaged(false);
    }

    private void submit() {
        if (saving) {
            return;
        }
        boolean ok = true;
        email.setError(null);
        phone.setError(null);
        password.setError(null);
        if (email.input().getText().isBlank()) {
            email.setError("Enter an email.");
            ok = false;
        }
        if (phone.input().getText().isBlank()) {
            phone.setError("Enter a phone in E.164 format.");
            ok = false;
        }
        if (password.input().getText().isEmpty()) {
            password.setError("Enter your current password.");
            ok = false;
        }
        if (!ok) {
            return;
        }
        char[] pw = password.input().getText().toCharArray();
        String e = email.input().getText().trim();
        String p = phone.input().getText().trim();
        saving = true;
        save.setLoading(true);
        Thread t = new Thread(() -> {
            String failure = null;
            try {
                data.changeContact(pw, e, p);
            } catch (RuntimeException ex) {
                failure = ex.getMessage() == null ? "Could not save your profile." : ex.getMessage();
            } finally {
                Arrays.fill(pw, '\0');
            }
            String f = failure;
            Platform.runLater(() -> done(f));
        }, "contact-update");
        t.setDaemon(true);
        t.start();
    }

    private void done(String failure) {
        if (!editing) {
            return;
        }
        saving = false;
        save.setLoading(false);
        password.input().clear();
        if (failure == null) {
            discardChanges();
            render();
            banner = null;
            return;
        }
        if (banner != null) {
            editor.getChildren().remove(banner);
        }
        banner = new ByxBanner(ByxBanner.Kind.ERROR, "Could not save your profile", failure + " Your changes are kept.");
        editor.getChildren().add(0, banner);
    }

    private void render() {
        User u = data.user().orElse(null);
        identity.getChildren().clear();
        details.getChildren().clear();
        if (u == null) {
            identity.getChildren().add(Kit.muted("No active session."));
            return;
        }
        Label av = Fx.label(AccountModel.initials(u), "byx-section-title");
        StackPane avatar = new StackPane(av);
        avatar.getStyleClass().add("byx-step-num");
        avatar.setMinSize(64, 64);
        avatar.setMaxSize(64, 64);
        edit = new ByxButton("Edit contacts", ByxButton.Variant.SECONDARY, motion);
        edit.setOnAction(e -> startEdit());
        edit.setDisable(editing);
        ByxButton out = new ByxButton("Sign out", ByxButton.Variant.DANGER_OUTLINE, motion);
        out.setOnAction(e -> signOut.run());
        HBox badges = new HBox(6, ByxBadge.of(AccountModel.role(u).toUpperCase(), ByxBadge.Tone.ACCENT),
                u.emailVerified() ? ByxBadge.of("EMAIL VERIFIED", ByxBadge.Tone.POSITIVE) : ByxBadge.of("EMAIL NOT VERIFIED", ByxBadge.Tone.NEUTRAL));
        identity.getChildren().addAll(avatar, Fx.label(u.username(), "byx-section-title"), Kit.muted("@" + u.username()), badges,
                Kit.dim("Your role comes from the authorization system. The interface never changes it."), new VBox(8, edit, out));
        details.getChildren().addAll(Kit.row("Display name", AccountModel.NOT_PROVIDED, false), Kit.row("Username", "@" + u.username(), false),
                Kit.row("Email", u.maskedEmail(), false), Kit.row("Phone", u.maskedPhone(), false), Kit.row("Role", AccountModel.role(u), false),
                Kit.row("Account created", u.createdAt() == null ? AccountModel.NOT_PROVIDED : Fmt.dateTime(u.createdAt()), false),
                Kit.row("Last sign-in", u.lastLoginAt() == null ? AccountModel.NOT_PROVIDED : Fmt.dateTime(u.lastLoginAt()), false),
                Kit.row("Account status", u.status().name(), false));
        if (editing) {
            details.getChildren().clear();
        }
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        if (!editing) {
            render();
        }
    }

    @Override
    public void onShow() {
        if (!editing) {
            render();
        }
    }
}
