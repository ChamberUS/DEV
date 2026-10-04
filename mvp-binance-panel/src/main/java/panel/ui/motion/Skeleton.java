package panel.ui.motion;

import javafx.scene.layout.Region;

/** Loading placeholder; ReferenceMotion adds shimmer only while visible in FULL. */
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
