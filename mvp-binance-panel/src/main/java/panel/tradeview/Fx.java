package panel.tradeview;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Atualizações que só tocam o nó quando algo mudou. O Desk recebe um snapshot a cada poll; um valor igual
 * não pode invalidar CSS nem layout.
 */
final class Fx {
    private Fx() {
    }

    static void text(Labeled l, String t) {
        if (!Objects.equals(l.getText(), t)) {
            l.setText(t);
        }
    }

    static void cls(Node n, String cls, boolean on) {
        var classes = n.getStyleClass();
        boolean has = classes.contains(cls);
        if (on && !has) {
            classes.add(cls);
        } else if (!on && has) {
            classes.remove(cls);
        }
    }

    /** Exatamente uma das classes de tom fica ligada (active pode ser null = nenhuma). */
    static void tone(Node n, String active, String... all) {
        for (String c : all) {
            cls(n, c, c.equals(active));
        }
    }

    static void visible(Node n, boolean v) {
        if (n.isVisible() != v) {
            n.setVisible(v);
        }
    }

    /** Visível e gerenciado juntos (some do layout). */
    static void shown(Node n, boolean v) {
        visible(n, v);
        if (n.isManaged() != v) {
            n.setManaged(v);
        }
    }

    static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        return l;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static Region skeleton() {
        Region r = new Region();
        r.getStyleClass().add("byx-desk-sk");
        r.setMouseTransparent(true);
        return r;
    }

    static HBox row(double spacing, Node... children) {
        HBox h = new HBox(spacing, children);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
}
