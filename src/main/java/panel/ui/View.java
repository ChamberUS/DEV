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

    /** Quantas alterações não salvas (para o aviso de navegação); 0 sem edição pendente. */
    default int unsavedChangeCount() { return hasUnsavedChanges() ? 1 : 0; }

    /** A view consegue salvar a edição pendente ao sair (false: o aviso não oferece "Save and go"). */
    default boolean canSaveChanges() { return false; }

    /**
     * Salva a edição pendente de forma síncrona e devolve true só quando ficou salva (leitura de volta). Nunca finge: se o serviço
     * não autoriza ou a gravação falha, devolve false e a edição continua pendente.
     */
    default boolean saveChanges() { return false; }

    /** A view passou a ser a visível: inicie timers/animações próprias aqui. */
    default void onShow() { }

    /** A view deixou de ser visível (ou a sessão acabou): pare timers/animações próprias aqui. */
    default void onHide() { }

    /** The session discarded this view permanently. Release owned workers/listeners. */
    default void dispose() { onHide(); }
}
