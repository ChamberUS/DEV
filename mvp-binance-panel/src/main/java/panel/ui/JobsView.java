package panel.ui;

import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.model.JobRecord;
import panel.model.JobState;
import panel.model.Snapshot;
import panel.process.ExternalProcessDetector;
import panel.util.Fmt;

public class JobsView implements View {
    private final AppContext ctx;
    private final TableView<JobRecord> table = new TableView<>();
    private final VBox external = new VBox(8);
    private final VBox root = new VBox(16);
    private final panel.ui.motion.OrbIndicator orb;
    private final javafx.scene.control.Label phase = Ui.label("IDLE", "stage-title");
    private final javafx.scene.control.Label detail = Ui.label("No active jobs", "muted");
    private final javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
    private final javafx.scene.layout.StackPane resultIcon = new javafx.scene.layout.StackPane();
    private final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
        if (table.getScene() != null) table.refresh();
    }));
    private JobState lastState;
    private Snapshot lastSnapshot;

    public JobsView(AppContext ctx) {
        this.ctx = ctx;
        orb = new panel.ui.motion.OrbIndicator(ctx.motion, 16, "warn");
        table.setItems(ctx.jobs.jobs);
        table.getColumns().add(text("Job", 280, j -> j.title));
        table.getColumns().add(badgeCol());
        table.getColumns().add(text("Progress", 150, j -> j.progress.get()));
        table.getColumns().add(text("Started", 90, j -> Fmt.time(j.startedAt.get())));
        table.getColumns().add(text("Elapsed", 90, j -> Fmt.duration(j.elapsed())));
        table.getColumns().add(text("Finished", 90, j -> Fmt.time(j.finishedAt.get())));
        table.getColumns().add(text("Exit", 60, j -> j.exitCode.get() == Integer.MIN_VALUE ? Fmt.NA : String.valueOf(j.exitCode.get())));
        table.getColumns().add(text("Command", 500, j -> j.command));
        table.setPlaceholder(EmptyState.of("refresh", "No jobs yet", "Commands launched from Labels or Sessions appear here, with their logs and exit codes."));
        Button cancel = Ui.button("Cancel selected", "danger");
        cancel.setOnAction(e -> {
            JobRecord j = table.getSelectionModel().getSelectedItem();
            if (j != null) {
                ctx.jobs.cancel(j);
            }
        });
        tick.setCycleCount(Timeline.INDEFINITE); // só roda enquanto a view está visível (onShow/onHide)
        ctx.jobs.externals.addListener((javafx.collections.ListChangeListener<ExternalProcessDetector.External>) c -> renderExternal());
        root.setPadding(new Insets(24, 28, 24, 28));
        bar.setPrefWidth(220);
        bar.setVisible(false);
        bar.setManaged(false);
        HBox strip = new HBox(14, new javafx.scene.layout.StackPane(orb, resultIcon), new VBox(2, phase, detail), Ui.spacer(), bar);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("job-strip");
        ctx.jobs.jobs.addListener((javafx.collections.ListChangeListener<JobRecord>) c -> {
            while (c.next()) {
                for (JobRecord j : c.getAddedSubList()) {
                    j.state.addListener((o, a, b) -> refreshStrip(lastSnapshot));
                }
            }
            refreshStrip(lastSnapshot);
        });
        root.getChildren().addAll(Ui.pageHeader("Jobs", "Commands launched by the panel, plus backend processes started elsewhere", cancel), strip, table, external);
        VBox.setVgrow(table, Priority.ALWAYS);
        renderExternal();
    }

    private void renderExternal() {
        external.getChildren().clear();
        VBox card = Ui.card("External processes (read-only)");
        for (ExternalProcessDetector.External e : ctx.jobs.externals) {
            HBox row = new HBox(12, Ui.badge("RUNNING", "warn"), Ui.label("pid " + e.pid(), "mono"), Ui.label(e.command().replaceAll(".*adaptive-trader", "adaptive-trader"), "mono"));
            row.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(row);
        }
        if (ctx.jobs.externals.isEmpty()) {
            card.getChildren().add(Ui.label("None detected", "muted"));
        }
        external.getChildren().add(card);
    }

    private TableColumn<JobRecord, String> text(String title, double w, Function<JobRecord, String> f) {
        TableColumn<JobRecord, String> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(f.apply(d.getValue())));
        c.setPrefWidth(w);
        return c;
    }

    private TableColumn<JobRecord, JobRecord> badgeCol() {
        TableColumn<JobRecord, JobRecord> c = new TableColumn<>("State");
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setCellFactory(x -> new TableCell<>() {
            @Override
            protected void updateItem(JobRecord j, boolean empty) {
                super.updateItem(j, empty);
                if (empty || j == null) {
                    setGraphic(null);
                    return;
                }
                JobState st = j.state.get();
                String tone = switch (st) {
                    case SUCCESS -> "ok";
                    case RUNNING -> "warn";
                    case FAILED -> "bad";
                    default -> "muted";
                };
                setGraphic(Ui.badge(st.name(), tone));
            }
        });
        c.setPrefWidth(110);
        return c;
    }

    @Override
    public Node node() {
        return root;
    }

    private void refreshStrip(Snapshot s) {
        lastSnapshot = s;
        JobRecord running = ctx.jobs.jobs.stream().filter(j -> j.state.get() == JobState.RUNNING).findFirst().orElse(null);
        JobRecord queued = ctx.jobs.jobs.stream().filter(j -> j.state.get() == JobState.QUEUED).findFirst().orElse(null);
        boolean external = !ctx.jobs.externals.isEmpty();
        boolean labelJob = running != null && running.spec != null && running.spec.subcommand.startsWith("label-run")
                || external && ctx.jobs.externals.stream().anyMatch(e -> e.command().contains("label-run"));
        JobState now = running != null || external ? JobState.RUNNING : queued != null ? JobState.QUEUED : ctx.jobs.jobs.isEmpty() ? null : ctx.jobs.jobs.get(0).state.get();
        orb.setActive(now == JobState.RUNNING);
        if (now == JobState.RUNNING) {
            phase.setText("PROCESSING");
            detail.setText(running != null ? running.title : "External process (read-only)");
        } else if (now == JobState.QUEUED) {
            phase.setText("QUEUED");
            detail.setText(queued.title);
        } else if (now == null) {
            phase.setText("IDLE");
            detail.setText("No active jobs");
        } else {
            phase.setText(now.name());
            detail.setText(ctx.jobs.jobs.get(0).title);
        }
        boolean known = labelJob && s != null && s.labelDone != null && s.labelsTotalSessions() > 0;
        bar.setVisible(known);
        bar.setManaged(known);
        if (known) {
            bar.setProgress((double) s.labelDone / s.labelsTotalSessions());
            detail.setText(detail.getText() + " · " + s.labelDone + " / " + s.labelsTotalSessions() + " sessions");
        }
        if (now != lastState) {
            if (now == JobState.SUCCESS || now == JobState.FAILED) {
                var icon = ctx.icons.icon(now == JobState.SUCCESS ? "check" : "error", 20, now == JobState.SUCCESS ? "ok" : "bad");
                resultIcon.getChildren().setAll(icon.node());
                icon.play();
            } else {
                resultIcon.getChildren().clear();
            }
            lastState = now;
        }
    }

    @Override
    public void onShow() {
        table.refresh();
        tick.play();
    }

    @Override
    public void onHide() {
        tick.stop();
    }

    @Override
    public void onSnapshot(Snapshot s) {
        table.refresh();
        refreshStrip(s);
    }
}
