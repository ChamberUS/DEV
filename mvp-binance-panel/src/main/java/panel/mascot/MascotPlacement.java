package panel.mascot;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

/** Posiciona um mascote em relação a um conteúdo segundo uma {@link MascotAnchor}. INLINE devolve o próprio mascote (o chamador o coloca numa linha). */
public final class MascotPlacement {
    static final double MARGIN = 8;

    private MascotPlacement() { }

    public static Node place(Node content, MascotView mascot, MascotAnchor anchor) {
        switch (anchor) {
            case INLINE:
                return mascot;
            case CENTER_EMPTY_STATE: {
                StackPane p = new StackPane(mascot);
                p.setAlignment(Pos.CENTER);
                p.setPadding(new Insets(MARGIN * 2));
                return p;
            }
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
            default: {
                if (content instanceof javafx.scene.layout.Region r) { // reserva a faixa do mascote para ele não cobrir o conteúdo
                    Insets in = r.getPadding();
                    r.setPadding(new Insets(in.getTop(), in.getRight() + mascot.getPrefWidth() + MARGIN * 2, in.getBottom(), in.getLeft()));
                }
                StackPane p = new StackPane(content, mascot);
                Pos pos = anchor == MascotAnchor.TOP_RIGHT ? Pos.TOP_RIGHT : Pos.BOTTOM_RIGHT;
                StackPane.setAlignment(mascot, pos);
                StackPane.setMargin(mascot, new Insets(MARGIN));
                return p;
            }
        }
    }
}
