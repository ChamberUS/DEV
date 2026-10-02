package panel.ui.trader;

import java.util.List;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import panel.ui.Ui;

/** Tabela compacta de linhas de texto (posições, ordens, sinais...). */
public final class TTable {
    private TTable() {
    }

    public static TableView<String[]> of(String[] headers, List<String[]> rows, String empty, double height) {
        return of(headers, rows, Ui.label(empty, "muted"), height);
    }

    public static TableView<String[]> of(String[] headers, List<String[]> rows, javafx.scene.Node placeholder, double height) {
        TableView<String[]> t = new TableView<>(FXCollections.observableArrayList(rows));
        for (int i = 0; i < headers.length; i++) {
            int idx = i;
            TableColumn<String[], String> c = new TableColumn<>(headers[i]);
            c.setCellValueFactory(d -> new ReadOnlyStringWrapper(idx < d.getValue().length ? d.getValue()[idx] : "N/A"));
            t.getColumns().add(c);
        }
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(placeholder);
        t.setPrefHeight(height);
        t.setMinHeight(height);
        return t;
    }
}
