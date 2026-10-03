package panel.ui;

import java.io.IOException;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.DataSource;
import panel.model.Snapshot;

public class SettingsView extends PageView {
    public SettingsView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        var st = ctx.settings;
        TextField project = new TextField(st.projectPath);
        TextField cli = new TextField(st.cliPath);
        TextField reports = new TextField(st.reportsPath);
        Spinner<Integer> poll = new Spinner<>(2, 300, st.pollSeconds);
        poll.setEditable(true);
        ComboBox<DataSource> source = new ComboBox<>();
        source.getItems().addAll(DataSource.values());
        source.setValue(st.dataSource);
        ComboBox<String> theme = new ComboBox<>();
        theme.getItems().add("Dark");
        theme.setValue("Dark");

        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(12);
        String[] names = {"Project path", "CLI path", "Reports path", "Theme", "Polling interval (s)", "Data source"};
        javafx.scene.Node[] fields = {project, cli, reports, theme, poll, source};
        for (int i = 0; i < names.length; i++) {
            g.add(Ui.label(names[i], "muted"), 0, i);
            g.add(fields[i], 1, i);
        }
        project.setPrefWidth(520);
        cli.setPrefWidth(520);
        reports.setPrefWidth(520);

        var save = Ui.button("Save", "primary");
        var status = Ui.label("", "muted");
        save.setOnAction(e -> {
            try {
                ctx.adminAccess.requireAdmin();
            } catch (panel.security.AccessDeniedException ex) {
                status.setText(ex.getMessage());
                return;
            }
            st.projectPath = project.getText().trim();
            st.cliPath = cli.getText().trim();
            st.reportsPath = reports.getText().trim();
            st.pollSeconds = Math.max(2, poll.getValue());
            st.dataSource = source.getValue();
            try {
                st.save();
                status.setText("Saved");
            } catch (IOException ex) {
                status.setText("Could not save: " + ex.getMessage());
            }
            ctx.research.reschedule();
        });
        page.getChildren().add(Ui.pageHeader("Admin settings", "Project paths, research, security and users"));
        page.getChildren().add(Ui.card("Paths & behavior", g, new HBox(10, save, status)));
        page.getChildren().add(Ui.card("Safety",
                Ui.kv("Partition", "TRAIN only"),
                Ui.kv("VALIDATION / FINAL_HOLDOUT", "Refused by the command adapter (not configurable)"),
                Ui.kv("API keys", "Not handled in this phase")));
        security(page);
    }

    @Override
    protected int stateKey(Snapshot s) {
        return java.util.Objects.hash(super.stateKey(s), ctx.audit.recent(1), ctx.adminAccess.hasValidAdminSession());
    }

    private void security(VBox page) {
        var user = ctx.sessions.user().map(s -> s.user()).orElse(null);
        page.getChildren().add(new panel.ui.auth.SecuritySettingsPane(ctx));
        page.getChildren().add(Ui.card("Admin contact",
                Ui.kv("Email", user == null ? null : user.maskedEmail()),
                Ui.kv("Phone", user == null ? null : user.maskedPhone()),
                Ui.kv("Email verification", user == null ? null : (user.emailVerified() ? "Verified" : "Not verified")),
                Ui.kv("Phone verification", user == null ? null : (user.phoneVerified() ? "Verified" : "Not verified"))));
        VBox events = Ui.card("Recent security events");
        for (var e : ctx.audit.recent(12)) {
            events.getChildren().add(Ui.label(panel.util.Fmt.dateTime(java.time.Instant.parse(e.ts())) + "  " + e.event() + "  " + e.actor(), "mono"));
        }
        page.getChildren().add(events);
    }

    private static javafx.scene.control.Label wrapped(String text) {
        javafx.scene.control.Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }
}
