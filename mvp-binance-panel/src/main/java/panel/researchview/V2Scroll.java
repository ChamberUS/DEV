package panel.researchview;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;

/** Rolagem V2 das telas de Research: ocupa a largura, preenche a altura e só rola quando a janela é menor que o conteúdo. */
final class V2Scroll {
    private V2Scroll() {
    }

    static ScrollPane wrap(Node content) {
        ScrollPane s = new ScrollPane(content);
        s.getStyleClass().add("byx-desk-scroll");
        s.setFitToWidth(true);
        s.setFitToHeight(true);
        s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        s.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        s.setMinSize(0, 0);
        s.setPannable(false);
        return s;
    }
}
