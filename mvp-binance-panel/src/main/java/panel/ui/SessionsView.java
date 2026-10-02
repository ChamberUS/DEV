package panel.ui;

import java.util.Map;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.adapter.CommandSpec;
import panel.app.AppContext;
import panel.model.SessionInfo;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.util.Fmt;

public class SessionsView implements View {
    private final AppContext ctx;
    private final ObservableList<SessionInfo> items = FXCollections.observableArrayList();
    private final FilteredList<SessionInfo> filtered = new FilteredList<>(items);
    private final TableView<SessionInfo> table = new TableView<>();
    private final TextField search = new TextField();
    private final ToggleGroup group = new ToggleGroup();
    private final VBox detail = new VBox(12);
    private final ScrollPane detailScroll = new ScrollPane(detail);
    private final BorderPane root = new BorderPane();
    private final Label count = Ui.label("", "muted");
    private String selectedId;

    public SessionsView(AppContext ctx) {
        this.ctx = ctx;
        search.setPromptText("Search session…");
        search.getStyleClass().add("search");
        HBox filters = new HBox(6);
        for (String f : new String[] {"ALL", "COMPLETE", "PARTIAL", "MISSING", "FAILED"}) {
            ToggleButton t = new ToggleButton(f);
            t.setUserData(f);
            t.setToggleGroup(group);
            t.getStyleClass().add("chip");
            filters.getChildren().add(t);
            if (f.equals("ALL")) {
                t.setSelected(true);
            }
        }
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                a.setSelected(true);
            }
            applyFilter();
        });
        search.textProperty().addListener((o, a, b) -> applyFilter());

        table.getColumns().add(col("Session", 300, s -> s.id(), 230));
        table.getColumns().add(col("Date", 95, s -> Fmt.date(s.start()), 95));
        table.getColumns().add(col("Start", 75, s -> Fmt.time(s.start()), 75));
        table.getColumns().add(col("End", 75, s -> Fmt.time(s.end()), 75));
        table.getColumns().add(col("Duration", 80, s -> Fmt.duration(s.duration()), 80));
        table.getColumns().add(col("Anchors", 80, s -> Fmt.num(s.anchors()), 80));
        table.getColumns().add(stateCol("Checkpoint", SessionInfo::checkpoint));
        table.getColumns().add(stateCol("Features", SessionInfo::features));
        table.getColumns().add(stateCol("Labels", SessionInfo::labels));
        table.getColumns().add(col("Errors", 70, s -> s.error() == null ? "0" : "1", 70));
        SortedList<SessionInfo> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No sessions", "muted"));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                selectedId = b.id();
                showDetail(b);
            }
        });

        HBox bar = new HBox(12, search, filters, Ui.spacer(), count);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(14, Ui.pageHeader("Sessions", "Recorded TRAIN sessions and their pipeline status"), bar, table);
        left.setPadding(new Insets(24, 20, 24, 28));
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        root.setCenter(left);

        detail.setPadding(new Insets(20));
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("drawer");
        detailScroll.setPrefWidth(400);
        detailScroll.setMinWidth(400);
    }

    private TableColumn<SessionInfo, String> col(String title, double w, java.util.function.Function<SessionInfo, String> f) {
        return col(title, w, f, w);
    }

    private TableColumn<SessionInfo, String> col(String title, double w, java.util.function.Function<SessionInfo, String> f, double min) {
        TableColumn<SessionInfo, String> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(f.apply(d.getValue())));
        c.setPrefWidth(w);
        c.setMinWidth(min);
        return c;
    }

    private TableColumn<SessionInfo, SessionInfo> stateCol(String title, java.util.function.Function<SessionInfo, StageState> f) {
        TableColumn<SessionInfo, SessionInfo> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new javafx.beans.property.ReadOnlyObjectWrapper<>(d.getValue()));
        c.setComparator(java.util.Comparator.comparing(f));
        c.setCellFactory(x -> new TableCell<>() {
            @Override
            protected void updateItem(SessionInfo s, boolean empty) {
                super.updateItem(s, empty);
                setGraphic(empty || s == null ? null : Ui.stateBadge(f.apply(s)));
            }
        });
        c.setPrefWidth(110);
        c.setMinWidth(104);
        return c;
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        String f = group.getSelectedToggle() == null ? "ALL" : (String) group.getSelectedToggle().getUserData();
        filtered.setPredicate(s -> s.id().toLowerCase().contains(q) && (f.equals("ALL") || s.overall().name().equals(f)));
        count.setText(filtered.size() + " / " + items.size());
    }

    private void showDetail(SessionInfo s) {
        detail.getChildren().clear();
        Button close = Ui.button("✕", "ghost");
        close.setOnAction(e -> {
            root.setRight(null);
            table.getSelectionModel().clearSelection();
            selectedId = null;
        });
        HBox h = new HBox(8, Ui.label("SESSION DETAIL", "card-title"), Ui.spacer(), close);
        h.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().addAll(h, Ui.label(s.id(), "mono-strong"),
                Ui.kvNode("Checkpoint", Ui.stateBadge(s.checkpoint())),
                Ui.kvNode("Features", Ui.stateBadge(s.features())),
                Ui.kvNode("Labels", Ui.stateBadge(s.labels())),
                Ui.kv("Errors", s.error() == null ? "None" : s.error()));
        detail.getChildren().add(Ui.label("DETAILS", "card-title"));
        for (Map.Entry<String, String> e : s.details().entrySet()) {
            detail.getChildren().add(Ui.kv(e.getKey(), e.getValue()));
        }
        Button run = Ui.button("Generate labels for this session", "primary");
        run.setDisable(s.labels() == StageState.READY || ctx.research.snapshot.get().source == panel.model.DataSource.MOCK);
        run.setOnAction(e -> Dialogs.run(ctx, CommandSpec.LABEL_RUN_SESSION, s.id()));
        detail.getChildren().add(run);
        root.setRight(detailScroll);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onSnapshot(Snapshot s) {
        items.setAll(s.sessions);
        applyFilter();
        if (selectedId != null) {
            s.sessions.stream().filter(x -> x.id().equals(selectedId)).findFirst().ifPresent(x -> {
                table.getSelectionModel().select(x);
                showDetail(x);
            });
        }
    }
}
