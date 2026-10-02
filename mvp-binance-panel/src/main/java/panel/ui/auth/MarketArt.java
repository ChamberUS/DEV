package panel.ui.auth;

import java.util.Random;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

/** Arte decorativa abstrata (candles + ladder de fluxo), sem rótulos nem números. Não representa dados. */
public class MarketArt extends Pane {
    private final Canvas canvas = new Canvas();

    public MarketArt(panel.motion.MotionService motion) {
        canvas.setManaged(false);
        setMouseTransparent(true);
        getChildren().add(canvas);
        motion.loop(this, () -> {
            // passos discretos de 400 ms: a respiração é lenta e o scene graph só é sujo a cada passo (CPU ~0 entre eles)
            javafx.animation.Timeline t = new javafx.animation.Timeline();
            int steps = 35;
            for (int i = 0; i <= steps; i++) {
                double phase = (Math.cos(2 * Math.PI * i / steps) + 1) / 2;
                t.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(400.0 * i),
                        new javafx.animation.KeyValue(canvas.opacityProperty(), 0.6 + 0.4 * phase, javafx.animation.Interpolator.DISCRETE)));
            }
            return t;
        });
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        canvas.setWidth(w);
        canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setStroke(Color.web("#F0B90B", 0.05));
        for (int i = 1; i < 8; i++) {
            g.strokeLine(0, h * i / 8, w, h * i / 8);
        }
        Random r = new Random(11);
        int n = (int) (w / 14);
        double p = h * 0.62, step = w / n;
        for (int i = 0; i < n; i++) {
            double o = p;
            p += (r.nextDouble() - 0.52) * h * 0.035;
            p = Math.max(h * 0.25, Math.min(h * 0.85, p));
            double hi = Math.min(o, p) - r.nextDouble() * 14, lo = Math.max(o, p) + r.nextDouble() * 14;
            Color c = p <= o ? Color.web("#0ECB81", 0.22) : Color.web("#F6465D", 0.20);
            g.setStroke(c);
            g.setFill(c);
            double x = i * step + step / 2;
            g.strokeLine(x, hi, x, lo);
            g.fillRect(x - 3, Math.min(o, p), 6, Math.max(2, Math.abs(p - o)));
        }
        for (int i = 0; i < 14; i++) {
            double bw = 20 + r.nextDouble() * 110;
            g.setFill(i < 7 ? Color.web("#F6465D", 0.12) : Color.web("#0ECB81", 0.12));
            g.fillRect(w - bw - 24, h * 0.06 + i * 11, bw, 7);
        }
    }
}
