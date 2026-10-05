package panel.shell;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

/**
 * Fronteira entre o shell V2 e telas ainda não portadas. As folhas legadas (panel.css, byx.css) ficam
 * presas a este nó, não à cena: valem só para o conteúdo hospedado aqui, e o tema V2 (na cena) não é
 * afetado por elas. As folhas legadas penduram suas variáveis em {@code .root}; por isso este nó carrega
 * a classe {@code root} e as classes de contexto (trader/research/byx) que antes ficavam na raiz da cena.
 * Uma View sai daqui quando o passo correspondente a porta para V2.
 */
public final class LegacyHost extends StackPane {
    public static final List<String> STYLESHEETS = List.of("/panel/panel.css", "/panel/byx.css");
    private static final List<String> CONTEXTS = List.of("trader", "research", "byx");

    public LegacyHost() {
        getStyleClass().addAll("root", "byx-legacy-host", "trader");
        for (String s : STYLESHEETS) {
            getStylesheets().add(LegacyHost.class.getResource(s).toExternalForm());
        }
    }

    public LegacyHost(Node content) {
        this();
        getChildren().add(content);
    }

    /** Contexto legado (trader, research ou byx): as folhas antigas escolhem o acento por ele. */
    public void setContext(String context) {
        if (!CONTEXTS.contains(context)) {
            throw new IllegalArgumentException(context);
        }
        getStyleClass().removeAll(CONTEXTS);
        getStyleClass().add(context);
    }

    public void setComfortable(boolean on) {
        getStyleClass().remove("comfortable");
        if (on) {
            getStyleClass().add("comfortable");
        }
    }
}
