package panel.ui;

import javafx.scene.Node;
import panel.model.Snapshot;

/** Uma tela do painel; recebe um novo Snapshot a cada leitura. */
public interface View {
    Node node();

    void onSnapshot(Snapshot s);

    /** A view passou a ser a visível: inicie timers/animações próprias aqui. */
    default void onShow() { }

    /** A view deixou de ser visível (ou a sessão acabou): pare timers/animações próprias aqui. */
    default void onHide() { }
}
