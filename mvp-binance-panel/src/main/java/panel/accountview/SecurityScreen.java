package panel.accountview;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.design.ByxOverlayHost;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.security.SecurityAuditService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.user.User;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * Security V2. Reutiliza o Auth real do Passo 6: troca de senha (serviço real), verificação de admin por e-mail/SMS (estado
 * real dos provedores), dispositivos confiáveis. O app autenticador não existe (NOT CONFIGURED) e nenhum segundo fluxo de
 * 2FA é criado aqui. Atividade vem da auditoria local; nenhum valor demonstrativo.
 */
public final class SecurityScreen implements View {
    private final AccountData data;
    private final MotionService motion;
    private final Clock clock;
    private final Consumer<String> navigate;
    private final Supplier<ByxOverlayHost> overlay;
    private final ScrollPane scroll;
    private final HBox summary = new HBox(14);
    private final VBox rows = new VBox(0);
    private final VBox activity = new VBox(0);
    private final Label providersNote = Kit.dim("Checking verification providers…");
    private long generation;

    public SecurityScreen(MotionService motion, Clock clock, AccountData data, Consumer<String> navigate, Supplier<ByxOverlayHost> overlay) {
        this.motion = motion;
        this.clock = clock;
        this.data = data;
        this.navigate = navigate;
        this.overlay = overlay;
        VBox signIn = Kit.panel("Sign-in and verification", rows);
        signIn.setId("security-rows");
        HBox head = Kit.titled("Recent security activity", link("View all", "t-account-activity"));
        VBox act = Kit.panel(null, head, activity);
        act.setId("security-activity");
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Security", "How you sign in, verify admin access and manage where you are signed in."), summary, signIn, act);
        scroll = Kit.scroll(page);
        render();
    }

    private Node link(String text, String route) {
        ByxButton b = new ByxButton(text, ByxButton.Variant.GHOST, motion);
        b.small();
        b.setOnAction(e -> navigate.accept(route));
        return b;
    }

    private static Node tile(String title, String value, String note, ByxBadge.Tone tone) {
        VBox v = new VBox(4, Kit.label(title), Fx.label(value, "byx-section-title"), Kit.dim(note));
        v.getStyleClass().add("byx-panel");
        HBox.setHgrow(v, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        v.setPrefWidth(1);
        v.setAccessibleText(title + ": " + value + ". " + note);
        return v;
    }

    private Node row(String title, String text, Node trailing) {
        VBox copy = new VBox(2, Fx.label(title, "byx-section-title-sm"), Kit.muted(text));
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox r = new HBox(16, copy, trailing);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("byx-desk-row");
        return r;
    }

    private void render() {
        User u = data.user().orElse(null);
        summary.getChildren().clear();
        rows.getChildren().clear();
        boolean admin = u != null && u.admin();
        int devices = -1;
        try {
            devices = admin ? (int) data.trustedDevices().stream().filter(d -> "ACTIVE".equals(d.status(clock.instant()))).count() : -1;
        } catch (RuntimeException unavailable) {
            devices = -1;
        }
        summary.getChildren().addAll(
                tile("Admin verification", admin ? "ON" : "N/A", admin ? "Email or SMS code" : "Not applicable to this role", ByxBadge.Tone.NEUTRAL),
                tile("Authenticator app", "N/A", "Not configured", ByxBadge.Tone.NEUTRAL),
                tile("Active sessions", u == null ? "—" : "1", "This device only", ByxBadge.Tone.NEUTRAL),
                tile("Trusted devices", devices < 0 ? "—" : Integer.toString(devices), devices < 0 ? "Unavailable without admin access" : "Skip codes on trusted Macs", ByxBadge.Tone.NEUTRAL),
                tile("Recovery", "N/A", "Not configured", ByxBadge.Tone.NEUTRAL));
        ByxButton change = new ByxButton("Change password", ByxButton.Variant.SECONDARY, motion);
        change.setOnAction(e -> openPasswordDialog());
        change.setDisable(u == null);
        rows.getChildren().add(row("Password", "Last changed date is not provided by the API.", change));
        Node adminState = admin ? new HBox(6, ByxBadge.of(data.adminSession() ? "ADMIN SESSION ACTIVE" : "ADMIN SESSION LOCKED",
                data.adminSession() ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.NEUTRAL))
                : ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED);
        VBox adminCopy = new VBox(6, adminState, providersNote);
        rows.getChildren().add(row("Admin verification", "Required to open Research. Confirmed with an email or SMS code.", adminCopy));
        rows.getChildren().add(row("Authenticator app", "Adds a code from an authenticator app when you sign in. Not part of this build.",
                ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED)));
        rows.getChildren().add(row("Recovery options", "Backup methods to regain access to your account.",
                ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED)));
        rows.getChildren().add(row("Sessions and devices", "Where you are signed in and which devices skip codes.", link("Review", "t-sessions")));
        renderActivity(u);
    }

    private void renderActivity(User u) {
        activity.getChildren().clear();
        List<SecurityAuditService.Entry> entries = u == null ? List.of() : safeActivity(u);
        if (entries.isEmpty()) {
            activity.getChildren().add(Kit.muted("No security events recorded yet."));
            return;
        }
        for (SecurityAuditService.Entry e : entries) {
            Label t = Fx.label(Fmt.dateTime(java.time.Instant.parse(e.ts())), "byx-table-cell", "mono");
            t.setMinWidth(180);
            Label ev = Fx.label(AccountModel.eventLabel(e.event()), "byx-table-cell");
            HBox.setHgrow(ev, Priority.ALWAYS);
            ev.setMaxWidth(Double.MAX_VALUE);
            HBox r = new HBox(12, t, ev, ByxBadge.of(AccountModel.result(e.event()).toUpperCase(), tone(AccountModel.result(e.event()))));
            r.setAlignment(Pos.CENTER_LEFT);
            r.getStyleClass().add("byx-table-row");
            activity.getChildren().add(r);
        }
    }

    static ByxBadge.Tone tone(String result) {
        return "Failed".equals(result) ? ByxBadge.Tone.NEGATIVE : "Success".equals(result) ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.NEUTRAL;
    }

    private List<SecurityAuditService.Entry> safeActivity(User u) {
        try {
            return data.activity(6);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void loadProviders() {
        long g = ++generation;
        Thread t = new Thread(() -> {
            List<AccountData.ProviderLine> lines;
            try {
                lines = data.providers();
            } catch (RuntimeException e) {
                lines = List.of();
            }
            List<AccountData.ProviderLine> result = lines;
            Platform.runLater(() -> {
                if (g != generation) {
                    return; // escondida ou descartada
                }
                providersNote.setText(result.isEmpty() ? "Provider status unavailable."
                        : String.join(" · ", result.stream().map(l -> l.name() + " " + l.state()).toList()));
            });
        }, "security-providers");
        t.setDaemon(true);
        t.start();
    }

    /** Diálogo de troca de senha (camada 70). Senhas não ficam no modelo depois do envio. */
    void openPasswordDialog() {
        ByxOverlayHost host = overlay.get();
        if (host == null) {
            return;
        }
        ByxField current = ByxField.password("Current password");
        ByxField next = ByxField.password("New password");
        ByxField confirm = ByxField.password("Confirm new password");
        ByxButton save = new ByxButton("Change password", ByxButton.Variant.PRIMARY, motion);
        ByxButton cancel = new ByxButton("Cancel", ByxButton.Variant.SECONDARY, motion);
        VBox error = new VBox();
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        VBox panel = new VBox(12, Fx.label("Change password", "byx-section-title"), Kit.muted("At least 10 characters and different from your username."),
                current, next, confirm, error, new HBox(8, sp, cancel, save));
        panel.getStyleClass().add("byx-dialog");
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        panel.setPrefWidth(440);
        panel.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        panel.setAccessibleText("Change password");
        ByxOverlayHost.DialogHandle[] h = new ByxOverlayHost.DialogHandle[1];
        cancel.setOnAction(e -> h[0].close());
        boolean[] busy = new boolean[1];
        save.setOnAction(e -> {
            if (busy[0]) {
                return;
            }
            current.setError(current.input().getText().isEmpty() ? "Enter your current password." : null);
            next.setError(next.input().getText().isEmpty() ? "Enter a new password." : null);
            confirm.setError(!next.input().getText().equals(confirm.input().getText()) ? "Passwords do not match." : null);
            if (current.hasError() || next.hasError() || confirm.hasError()) {
                return;
            }
            char[] c = current.input().getText().toCharArray();
            char[] n = next.input().getText().toCharArray();
            current.input().clear();
            next.input().clear();
            confirm.input().clear();
            busy[0] = true;
            save.setLoading(true);
            Thread t = new Thread(() -> {
                String failure = null;
                try {
                    data.changePassword(c, n);
                } catch (RuntimeException ex) {
                    failure = ex.getMessage() == null ? "Could not change the password." : ex.getMessage();
                } finally {
                    Arrays.fill(c, '\0');
                    Arrays.fill(n, '\0');
                }
                String f = failure;
                Platform.runLater(() -> {
                    busy[0] = false;
                    save.setLoading(false);
                    if (f == null) {
                        h[0].close();
                        host.toast(ByxOverlayHost.ToastKind.SUCCESS, "Password changed.");
                    } else {
                        error.getChildren().setAll(new ByxBanner(ByxBanner.Kind.ERROR, "Could not change the password", f));
                    }
                });
            }, "password-change");
            t.setDaemon(true);
            t.start();
        });
        h[0] = host.openDialog(panel, false, current.input(), null);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override
    public void onShow() {
        render();
        if (data.user().map(User::admin).orElse(false)) {
            loadProviders();
        } else {
            providersNote.setText("");
        }
    }

    @Override
    public void onHide() {
        generation++;
    }

    public void dispose() {
        onHide();
    }
}
