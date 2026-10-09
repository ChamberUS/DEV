package panel.ui;

import javafx.scene.Node;
import panel.model.Snapshot;

/** Uma tela do painel; recebe um novo Snapshot a cada leitura. */
public interface View {
    Node node();

    void onSnapshot(Snapshot s);

    /** Edição não salva: o roteador pergunta antes de sair (a rota só muda depois da confirmação). */
    default boolean hasUnsavedChanges() { return false; }

    /** Descarta a edição pendente (chamado depois que o usuário confirmou sair). */
    default void discardChanges() { }

    /** A view passou a ser a visível: inicie timers/animações próprias aqui. */
    default void onShow() { }

    /** A view deixou de ser visível (ou a sessão acabou): pare timers/animações próprias aqui. */
    default void onHide() { }

    /** The session discarded this view permanently. Release owned workers/listeners. */
    default void dispose() { onHide(); }
}
