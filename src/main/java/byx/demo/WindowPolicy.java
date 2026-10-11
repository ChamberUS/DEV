package byx.demo;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Initial native window geometry. JavaFX screen bounds are already logical pixels. */
public final class WindowPolicy {
    public record Geometry(double x, double y, double width, double height,
                           double minWidth, double minHeight) { }

    private WindowPolicy() { }

    public static Geometry plan(Rectangle2D visualBounds) {
        if (visualBounds == null || !Double.isFinite(visualBounds.getMinX())
                || !Double.isFinite(visualBounds.getMinY())
                || !Double.isFinite(visualBounds.getWidth())
                || !Double.isFinite(visualBounds.getHeight())
                || visualBounds.getWidth() <= 0 || visualBounds.getHeight() <= 0) {
            throw new IllegalArgumentException("A finite, positive visual area is required");
        }
        double margin = Math.min(24, Math.min(visualBounds.getWidth(), visualBounds.getHeight()) * .04);
        double usableWidth = visualBounds.getWidth() - margin * 2;
        double usableHeight = visualBounds.getHeight() - margin * 2;
        double width = Math.min(1200, usableWidth);
        double height = Math.min(760, usableHeight);
        // Keep a resize range even when a high-DPI display has a small logical work area.
        double minWidth = Math.min(900, usableWidth * .82);
        double minHeight = Math.min(560, usableHeight * .78);
        return new Geometry(visualBounds.getMinX() + (visualBounds.getWidth() - width) / 2,
                visualBounds.getMinY() + (visualBounds.getHeight() - height) / 2,
                width, height, Math.min(minWidth, width), Math.min(minHeight, height));
    }

    /** Apply once before show; navigation and authentication never call this method. */
    public static Geometry apply(Stage stage) {
        if (stage.isShowing()) throw new IllegalStateException("Window policy is startup-only");
        Geometry geometry = plan(Screen.getPrimary().getVisualBounds());
        stage.setFullScreen(false);
        stage.setMaximized(false);
        stage.setIconified(false);
        stage.setResizable(true);
        stage.setMinWidth(geometry.minWidth());
        stage.setMinHeight(geometry.minHeight());
        stage.setWidth(geometry.width());
        stage.setHeight(geometry.height());
        stage.setX(geometry.x());
        stage.setY(geometry.y());
        return geometry;
    }
}