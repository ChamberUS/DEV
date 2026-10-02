package panel.ui;

import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.util.Fmt;

public class DatasetView extends PageView {
    public DatasetView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        page.getChildren().add(Ui.pageHeader("Dataset", "Identity of the dataset used by the research pipeline"));
        HBox parts = new HBox(14, partition("TRAIN", "ACTIVE", "ok", "Research partition. Read and label generation allowed."),
                partition("VALIDATION", "LOCKED", "bad", "Locked until explicit future authorization."),
                partition("FINAL_HOLDOUT", "SEALED", "bad", "Sealed. Never touched by this panel."));
        parts.getChildren().forEach(n -> HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS));
        page.getChildren().add(parts);

        VBox identity = Ui.card("Identity", Ui.kv("dataset_id", s.datasetId), Ui.kv("partition", s.partition), Ui.kv("created_at", s.datasetCreatedAt),
                Ui.kv("session_count", Fmt.num(s.sessionCount)), Ui.kv("anchor_count", Fmt.num(s.anchorCount)), Ui.kv("checkpoint rows", Fmt.num(s.checkpointSamples)));
        VBox schemas = Ui.card("Schema versions");
        VBox hashes = Ui.card("Hashes");
        VBox paths = Ui.card("Storage paths");
        fill(schemas, s.schemas, "Not available");
        if (s.labelSchema != null) {
            schemas.getChildren().add(Ui.kv("labels", s.labelSchema));
        }
        fill(hashes, s.hashes, "Not available");
        fill(paths, s.paths, "Not available");
        page.getChildren().addAll(identity, schemas, hashes, paths);
    }

    private static VBox partition(String name, String state, String tone, String note) {
        HBox head = new HBox(10, Ui.label(name, "stage-title"), Ui.spacer(), Ui.badge(state, tone));
        head.setAlignment(Pos.CENTER_LEFT);
        return Ui.card("Partition", head, Ui.label(note, "muted"));
    }

    private static void fill(VBox card, Map<String, String> m, String empty) {
        m.forEach((k, v) -> card.getChildren().add(Ui.kv(k, v)));
        if (m.isEmpty()) {
            card.getChildren().add(Ui.emptyState(empty));
        }
    }
}
