package panel.researchview;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Hachura diagonal de "bloqueado/selado" (referência: repeating-linear-gradient(135deg, cor 0 p, transparente p 2p)).
 * Estático: desenha uma vez por tamanho, sem animação, e recorta nos cantos arredondados do contêiner.
 */
public final class Hatch extends Pane {
    private final Canvas canvas = new Canvas();
    private final double period;
    private final Color color;
    private final double radius;
    private double drawnW = -1;
    private double drawnH = -1;

    public Hatch(double period, Color color, double radius) {
        this.period = period;
        this.color = color;
        this.radius = radius;
        setMouseTransparent(true);
        setPickOnBounds(false);
        getChildren().add(canvas);
        getStyleClass().add("byx-res-hatch");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        if (w == drawnW && h == drawnH) {
            return;
        }
        drawnW = w;
        drawnH = h;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setStroke(color);
        g.setLineWidth(period / Math.sqrt(2) * 1.0);
        double step = period * Math.sqrt(2);
        for (double x = -h; x < w + h; x += step) {
            g.strokeLine(x, h, x + h, 0);
        }
    }

    double period() {
        return period;
    }

    double radius() {
        return radius;
    }
}
