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

    public CandleChart(List<Candle> candles) {
        this.candles = candles;
        canvas.setManaged(false);
        setMinHeight(0);
        setPrefHeight(340);
        getChildren().add(canvas);
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
        g.setStroke(Color.web("#2B3139"));
        g.setFill(Color.web("#848E9C"));
        for (int i = 0; i <= 4; i++) {
            double y = 8 + h * i / 4;
            g.strokeLine(0, y, w, y);
            g.fillText(String.format("%,.0f", hi - (hi - lo) * i / 4), w + 6, y + 4);
        }
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Color col = c.close() >= c.open() ? Color.web("#0ECB81") : Color.web("#F6465D");
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
