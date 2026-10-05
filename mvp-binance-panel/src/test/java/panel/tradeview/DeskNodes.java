package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;

/** Percurso do grafo de cena para os testes do Desk. */
final class DeskNodes {
    private DeskNodes() {
    }

    static <T> List<T> all(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        walk(root, n -> {
            if (type.isInstance(n)) {
                out.add(type.cast(n));
            }
        });
        return out;
    }

    static void walk(Node n, java.util.function.Consumer<Node> visit) {
        visit.accept(n);
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                walk(c, visit);
            }
        }
    }

    /** Todo texto de rótulo que o usuário veria (nós visíveis e gerenciados). */
    static List<String> visibleTexts(Node root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node n, List<String> out) {
        if (!n.isVisible() || !n.isManaged()) {
            return;
        }
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (n instanceof javafx.scene.control.TableView<?> t) {
            for (Object row : t.getItems()) {
                if (row instanceof String[] cells) {
                    out.addAll(List.of(cells));
                }
            }
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                collect(c, out);
            }
        }
    }

    /** Resumo estrutural (texto, visibilidade, classes de estilo): igual em FULL, REDUCED e OFF. Não entra nas células de TableView. */
    static List<String> digest(Node root) {
        List<String> out = new ArrayList<>();
        digest(root, out);
        return out;
    }

    private static void digest(Node n, List<String> out) {
        StringBuilder b = new StringBuilder(n.getClass().getSimpleName());
        b.append(' ').append(n.isVisible()).append('/').append(n.isManaged());
        b.append(' ').append(n.getStyleClass());
        if (n instanceof Labeled l) {
            b.append(" '").append(l.getText()).append('\'');
        }
        if (n instanceof javafx.scene.control.TableView<?> t) {
            for (Object row : t.getItems()) {
                b.append(" | ").append(row instanceof String[] cells ? String.join(",", cells) : row);
            }
            out.add(b.toString());
            return; // as células virtuais são do controle
        }
        out.add(b.toString());
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                digest(c, out);
            }
        }
    }
}
