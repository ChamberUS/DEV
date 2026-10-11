package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.model.TraderSnapshot;

/** Atividade do bot (últimos eventos reais). Vazio: "No bot activity". Backend fora do ar: indisponível, não vazio. */
final class ActivityPanel extends VBox {
    static final int ROWS = 6;

    private final VBox list = new VBox();
    private final List<Line> lines = new ArrayList<>();
    private final Label emptyTitle = Fx.label("No bot activity", "byx-section-title-sm");
    private final Label emptyText = Fx.label("Bot events appear here once a strategy is approved.", "byx-desk-secondary", "byx-desk-body");
    private final VBox empty = new VBox(6, emptyTitle, emptyText);
    private int lastHash = Integer.MIN_VALUE;

    ActivityPanel() {
        getStyleClass().add("byx-desk-activity");
        setId("desk-activity");
        Label head = Fx.label("Bot activity", "byx-section-title-sm");
        head.getStyleClass().add("byx-desk-panel-head");
        for (int i = 0; i < ROWS; i++) {
            Line l = new Line();
            lines.add(l);
            list.getChildren().add(l);
        }
        empty.setAlignment(Pos.CENTER);
        emptyText.setWrapText(true);
        emptyText.setAlignment(Pos.CENTER);
        emptyText.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        StackPane body = new StackPane(list, empty);
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(head, body);
        setInline(false);
    }

    void setInline(boolean inline) {
        Fx.cls(this, "byx-panel", !inline);
        Fx.cls(this, "byx-desk-inline", inline);
    }

    List<Line> lines() {
        return lines;
    }

    Label emptyTitle() {
        return emptyTitle;
    }

    void apply(TraderSnapshot t) {
        boolean unavailable = t.source == panel.model.DataSource.REAL && !t.backendOnline && t.activityRows.isEmpty();
        int hash = java.util.Objects.hash(unavailable, java.util.Arrays.deepHashCode(t.activityRows.toArray()));
        if (hash == lastHash) {
            return;
        }
        lastHash = hash;
        boolean has = !t.activityRows.isEmpty();
        Fx.visible(list, has);
        Fx.visible(empty, !has);
        if (!has) {
            Fx.text(emptyTitle, unavailable ? "Bot activity unavailable" : "No bot activity");
            Fx.text(emptyText, unavailable ? "The local backend is offline." : "Bot events appear here once a strategy is approved.");
        }
        for (int i = 0; i < ROWS; i++) {
            Line l = lines.get(i);
            if (i < t.activityRows.size()) {
                String[] r = t.activityRows.get(i);
                l.set(r.length > 0 ? r[0] : "", r.length > 1 ? r[1] : "", r.length > 2 ? r[2] : "");
            } else {
                l.set("", "", "");
            }
        }
    }

    static final class Line extends HBox {
        private final Label time = Fx.label("", "byx-desk-cell", "mono", "byx-desk-t3");
        private final Label level = Fx.label("", "byx-label");
        private final Label message = Fx.label("", "byx-desk-cell");

        Line() {
            super(10);
            getStyleClass().add("byx-desk-row");
            setAlignment(Pos.CENTER_LEFT);
            message.setMinWidth(0);
            getChildren().addAll(time, level, message);
        }

        void set(String t, String l, String m) {
            Fx.text(time, t);
            Fx.text(level, l);
            Fx.text(message, m);
            Fx.shown(this, !t.isEmpty() || !m.isEmpty());
        }
    }
}
