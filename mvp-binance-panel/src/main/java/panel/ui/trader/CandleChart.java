package panel.ui.trader;

import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import panel.model.TraderSnapshot.Candle;

/** Gráfico de candles simples em Canvas; só desenha dados recebidos. */
public class CandleChart extends Pane {
    private static final javafx.scene.text.Font AXIS_FONT = javafx.scene.text.Font.font("JetBrains Mono Medium", 12);
    private final Canvas canvas = new Canvas();
    private List<Candle> candles;
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
        javafx.beans.value.ChangeListener<panel.i18n.Strings.Lang> locale = (o,a,b) -> requestLayout();
        getProperties().put("byx.i18n.localeDelegate", locale);
        javafx.beans.value.WeakChangeListener<panel.i18n.Strings.Lang> weakLocale = new javafx.beans.value.WeakChangeListener<>(locale);
        sceneProperty().addListener((o,a,b) -> {
            if (a != null) panel.i18n.Strings.languageProperty().removeListener(weakLocale);
            if (b != null) { requestLayout(); panel.i18n.Strings.languageProperty().addListener(weakLocale); }
        });
        canvas.setManaged(false);
        setMinHeight(0);
        setPrefHeight(340);
        getChildren().addAll(canvas, grid, text, up, down);
    }

    /** Redesenha no lugar com os novos candles (o V2 não recria o gráfico a cada atualização). */
    public void setCandles(List<Candle> next) {
        if (!java.util.Objects.equals(candles, next)) {
            candles = next;
            requestLayout();
        }
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
        double padR = 70, h = getHeight() - 16, w = getWidth() - padR;
        double step = w / candles.size();
        g.setStroke(paint(grid));
        g.setFill(paint(text));
        g.setFont(AXIS_FONT);
        for (int i = 0; i <= 4; i++) {
            double y = 8 + h * i / 4;
            g.strokeLine(0, y, w, y);
            g.fillText(axis(hi - (hi - lo) * i / 4, hi - lo), w + 6, y + 4);
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

    /** Rótulo do eixo de preço: casas decimais conforme a amplitude visível; localizadas apenas na apresentação. */
    private static String axis(double v, double range) {
        int decimals = range >= 20 ? 0 : range >= 2 ? 1 : range >= 0.2 ? 2 : 4;
        return String.format(panel.i18n.Strings.locale(), "%,." + decimals + "f", v);
    }

    private static double y(double v, double hi, double lo, double h) {
        return 8 + h * (hi - v) / (hi - lo);
    }
}
