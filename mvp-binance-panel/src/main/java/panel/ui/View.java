package panel.ui;

import javafx.scene.Node;
import panel.model.Snapshot;

/** Uma tela do painel; recebe um novo Snapshot a cada leitura. */
public interface View {
    Node node();

    void onSnapshot(Snapshot s);
}
