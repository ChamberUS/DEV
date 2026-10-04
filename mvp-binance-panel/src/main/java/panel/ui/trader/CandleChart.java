package panel.ui.trader;

import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import panel.model.TraderSnapshot.Candle;

/** Gráfico de candles simples em Canvas; só desenha dados recebidos. */
public class CandleChart extends Pane {
    private final Canvas canvas = new Canvas();
    private final List<Candle> candles;
    private final javafx.scene.layout.Region grid = ink("chart-grid-ink");
    private final javafx.scene.layout.Region text = ink("chart-text-ink");
    private final javafx.scene.layout.Region up = ink("chart-up-ink");
    private final javafx.scene.layout.Region down = ink("chart-down-ink");

    private static javafx.scene.layout.Region ink(String style) {
        var region = new javafx.scene.layout.Region(); region.getStyleClass().add(style); region.setManaged(false); region.setVisible(false);
        return region;
    }
    private static javafx.scene.paint.Paint paint(javafx.scene.layout.Region ink) {
        return ink.getBackground() == null ? Color.TRANSPARENT : ink.getBackground().getFills().getFirst().getFill();
    }

    public CandleChart(List<Candle> candles) {
        this.candles = candles;
        canvas.setManaged(false);
        setMinHeight(0);
        setPrefHeight(340);
        getChildren().addAll(canvas, grid, text, up, down);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        if (candles.isEmpty()) {
            return;
        }
        double hi = candles.stream().mapToDouble(Candle::high).max().orElse(1);
        double lo = candles.stream().mapToDouble(Candle::low).min().orElse(0);
        double padR = 60, h = getHeight() - 16, w = getWidth() - padR;
        double step = w / candles.size();
        g.setStroke(paint(grid));
        g.setFill(paint(text));
        for (int i = 0; i <= 4; i++) {
            double y = 8 + h * i / 4;
            g.strokeLine(0, y, w, y);
            g.fillText(String.format("%,.0f", hi - (hi - lo) * i / 4), w + 6, y + 4);
        }
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            javafx.scene.paint.Paint col = c.close() >= c.open() ? paint(up) : paint(down);
            g.setStroke(col);
            g.setFill(col);
            double x = i * step + step / 2;
            g.strokeLine(x, y(c.high(), hi, lo, h), x, y(c.low(), hi, lo, h));
            double top = y(Math.max(c.open(), c.close()), hi, lo, h);
            g.fillRect(x - step * 0.3, top, step * 0.6, Math.max(1, y(Math.min(c.open(), c.close()), hi, lo, h) - top));
        }
    }

    private static double y(double v, double hi, double lo, double h) {
        return 8 + h * (hi - v) / (hi - lo);
    }
}
