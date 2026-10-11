package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Painel de linhas rótulo/valor (Risk, Data freshness). Pode ser painel próprio ou seção de uma aba. */
final class InfoPanel extends VBox {
    private final List<KvRow> rows = new ArrayList<>();

    InfoPanel(String id, String title, String... keys) {
        getStyleClass().add("byx-desk-info");
        setId(id);
        Label head = Fx.label(title, "byx-section-title-sm");
        head.getStyleClass().add("byx-desk-panel-head");
        getChildren().add(head);
        for (String k : keys) {
            KvRow r = new KvRow(k);
            rows.add(r);
            getChildren().add(r);
        }
        setInline(false);
    }

    void setInline(boolean inline) {
        Fx.cls(this, "byx-panel", !inline);
        Fx.cls(this, "byx-desk-inline", inline);
    }

    KvRow row(int i) {
        return rows.get(i);
    }

    List<KvRow> rows() {
        return rows;
    }
}
