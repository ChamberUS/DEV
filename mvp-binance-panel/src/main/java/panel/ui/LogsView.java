package panel.ui;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.LogEntry;
import panel.model.Snapshot;
import panel.util.Fmt;

public class LogsView implements View {
    private final VBox root = new VBox(14);
    private final ListView<LogEntry> list = new ListView<>();
    private final FilteredList<LogEntry> view;
    private final Set<LogEntry.Level> levels = EnumSet.allOf(LogEntry.Level.class);
    private Instant clearedAt = Instant.EPOCH;

    public LogsView(AppContext ctx) {
        view = new FilteredList<>(ctx.jobs.logs);
        list.setItems(view);
        list.getStyleClass().add("log-view");
        list.setCellFactory(x -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeIf(c -> c.startsWith("log-"));
                if (empty || e == null) {
                    setText(null);
                    return;
                }
                setText(Fmt.time(e.time()) + "  " + String.format("%-7s", e.level()) + e.text());
                getStyleClass().add("log-" + e.level().name().toLowerCase());
            }
        });
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        for (LogEntry.Level l : LogEntry.Level.values()) {
            ToggleButton t = new ToggleButton(l.name());
            t.getStyleClass().add("chip");
            t.setSelected(true);
            t.selectedProperty().addListener((o, a, b) -> {
                if (b) {
                    levels.add(l);
                } else {
                    levels.remove(l);
                }
                applyFilter();
            });
            bar.getChildren().add(t);
        }
        CheckBox auto = new CheckBox("Auto-scroll");
        auto.setSelected(true);
        Button clear = Ui.button("Clear view", "ghost");
        clear.setOnAction(e -> {
            clearedAt = Instant.now();
            applyFilter();
        });
        Button copy = Ui.button("Copy", "ghost");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(view.stream().map(x -> Fmt.dateTime(x.time()) + " " + x.level() + " " + x.text()).collect(Collectors.joining("\n")));
            Clipboard.getSystemClipboard().setContent(c);
        });
        bar.getChildren().addAll(Ui.spacer(), auto, clear, copy);
        ctx.jobs.logs.addListener((javafx.collections.ListChangeListener<LogEntry>) c -> {
            if (auto.isSelected() && !view.isEmpty()) {
                list.scrollTo(view.size() - 1);
            }
        });
        applyFilter();
        root.setPadding(new Insets(24, 28, 24, 28));
        root.getChildren().addAll(Ui.pageHeader("Logs", "stdout / stderr of commands launched by the panel, and panel events"), bar, list);
        VBox.setVgrow(list, Priority.ALWAYS);
    }

    private void applyFilter() {
        view.setPredicate(e -> levels.contains(e.level()) && e.time().isAfter(clearedAt));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onSnapshot(Snapshot s) {
    }
}
