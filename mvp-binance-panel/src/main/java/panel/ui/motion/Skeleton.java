package panel.ui.motion;

import javafx.scene.layout.Region;

/** Placeholder estático de carregamento (sem shimmer contínuo: custo zero de CPU). */
public final class Skeleton {
    private Skeleton() {
    }

    public static Region bar(double width, double height) {
        Region r = new Region();
        r.getStyleClass().add("skeleton");
        r.setMinSize(width, height);
        r.setPrefSize(width, height);
        r.setMaxSize(width, height);
        return r;
    }
}
