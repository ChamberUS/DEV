package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import panel.design.ByxFonts;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.ui.trader.CandleChart;

/**
 * Painel do gráfico V2. O {@link CandleChart} existente é preservado (mesmo desenho, mesmos dados); o V2 só
 * traz o contêiner, o tratamento dos eixos, o estado de espera e a sobreposição de STALE. Sem feed há
 * grade e o texto "Waiting for market data": nenhum candle é criado, nenhum preço animado. O candle agora
 * traz o horário real de abertura, e o eixo de tempo mostra esses horários; sem horário o eixo só aparece ("--:--") na espera.
 */
final class ChartPanel extends VBox {
    private final MotionService motion;
    private final Label title = ByxFonts.upper(Fx.label("N/A · 1m · Candles", "byx-label"));
    private final Label venue = ByxFonts.upper(Fx.label("Binance USD-M Futures", "byx-label"));
    private final StackPane plot = new StackPane();
    private final Placeholder placeholder;
    private final Label overlay = Fx.label("", "byx-stale-chip", "byx-desk-chart-overlay");
    private final HBox timeRow = new HBox();
    private final List<Label> axisLabels = new ArrayList<>();
    private static final java.time.format.DateTimeFormatter AXIS_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());
    private CandleChart chart;
    private List<TraderSnapshot.Candle> shown;

    ChartPanel(MotionService motion) {
        this.motion = motion;
        getStyleClass().addAll("byx-panel", "byx-desk-chart");
        setId("desk-chart");
        placeholder = new Placeholder();
        HBox top = new HBox(title, Fx.spacer(), venue);
        top.getStyleClass().add("byx-desk-chart-head");
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 18, 12, 18));
        plot.getChildren().addAll(placeholder, overlay);
        StackPane.setAlignment(overlay, Pos.TOP_RIGHT);
        StackPane.setMargin(overlay, new Insets(8, 78, 0, 0));
        Fx.shown(overlay, false);
        plot.setMinHeight(0);
        plot.setMinWidth(0);
        VBox.setVgrow(plot, Priority.ALWAYS);
        timeRow.setPadding(new Insets(8, 70, 10, 18));
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                timeRow.getChildren().add(Fx.spacer());
            }
            Label axis = Fx.label("--:--", "byx-desk-axis");
            axisLabels.add(axis);
            timeRow.getChildren().add(axis);
        }
        getChildren().addAll(top, plot, timeRow);
        setMinSize(0, 0);
        plot.setStyle("-fx-background-color: transparent;");
        // o gráfico não pode empurrar o layout: recorta no painel
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        setClip(clip);
    }

    boolean showingCandles() {
        return chart != null && chart.isVisible();
    }

    CandleChart chart() {
        return chart;
    }

    Placeholder placeholder() {
        return placeholder;
    }

    Label overlay() {
        return overlay;
    }

    void apply(TraderSnapshot t, DeskModel.Feed feed) {
        Fx.text(title, ((t.symbol == null || t.symbol.isBlank() ? "N/A" : t.symbol) + " · 1m · Candles").toUpperCase(java.util.Locale.ROOT));
        Fx.text(venue, DeskModel.venue(t.market).toUpperCase(java.util.Locale.ROOT));
        boolean candles = feed.showsMarketData() && !t.candles.isEmpty();
        if (candles) {
            if (chart == null) {
                chart = new CandleChart(List.copyOf(t.candles));
                chart.setMinSize(0, 0);
                chart.setPrefHeight(0);
                plot.getChildren().add(1, chart);
            } else if (!Objects.equals(shown, t.candles)) {
                chart.setCandles(List.copyOf(t.candles));
            }
            shown = List.copyOf(t.candles);
            chart.setOpacity(feed.looksStale() ? 0.6 : 1); // staleDim: mantém o valor, não parece vivo
        }
        if (chart != null) {
            Fx.visible(chart, candles);
        }
        Fx.visible(placeholder, !candles);
        // eixo de tempo: horários REAIS de abertura dos candles (hora local); sem horário (dados sem timestamp) o eixo some
        boolean timed = candles && t.candles.get(0).openTimeMs() > 0;
        Fx.shown(timeRow, !candles || timed);
        if (timed) {
            int n = t.candles.size();
            for (int k = 0; k < axisLabels.size(); k++) {
                long ms = t.candles.get((int) Math.round(k * (n - 1) / (double) (axisLabels.size() - 1))).openTimeMs();
                Fx.text(axisLabels.get(k), AXIS_TIME.format(java.time.Instant.ofEpochMilli(ms)));
            }
        } else if (!candles) {
            for (Label axis : axisLabels) {
                Fx.text(axis, "--:--");
            }
        }
        if (!candles) {
            placeholder.apply(feed, t.candles.isEmpty() || !feed.showsMarketData(), motion);
        }
        boolean chip = candles && (feed.looksStale() || feed == DeskModel.Feed.DEGRADED || feed == DeskModel.Feed.MOCK);
        Fx.shown(overlay, chip);
        if (chip) {
            Fx.text(overlay, feed == DeskModel.Feed.MOCK ? "MOCK DATA · fictional values"
                    : feed == DeskModel.Feed.DEGRADED ? "DEGRADED" : "STALE · LAST UPDATE "
                            + (t.feedUpdatedAt == null ? "—" : panel.util.Fmt.time(t.feedUpdatedAt)));
        }
    }

    /** Grade, eixo de preço "—" e mensagem centralizada (referência: grade 60/120 px, textos à direita em 6/28/50/72/92%). */
    static final class Placeholder extends Pane {
        private static final double[] AXIS = {.06, .28, .50, .72, .92};
        private final Canvas lines = new Canvas();
        private final List<Label> dashes = new ArrayList<>();
        private final Pane mark = new Pane();
        private final Label heading = Fx.label(DeskModel.WAITING_TEXT, "byx-section-title");
        private final Label body = Fx.label("", "byx-desk-secondary", "byx-desk-body");
        private final VBox center;
        private double drawnW = -1;
        private double drawnH = -1;

        Placeholder() {
            panel.design.ByxTheme.observe(this, () -> { drawnW = -1; requestLayout(); });
            getStyleClass().add("byx-desk-placeholder");
            setId("desk-chart-placeholder");
            lines.setMouseTransparent(true);
            getChildren().add(lines);
            for (double ignored : AXIS) {
                Label d = Fx.label("—", "byx-desk-axis");
                dashes.add(d);
                getChildren().add(d);
            }
            double u = 44 / 24.0;
            mark.getStyleClass().add("byx-desk-mark");
            mark.setMinSize(44, 44);
            mark.setPrefSize(44, 44);
            mark.setMaxSize(44, 44);
            double[][] bars = {{2, 3, 20}, {2, 10, 14}, {2, 17, 8}};
            for (double[] b : bars) {
                Rectangle r = new Rectangle(b[0] * u, b[1] * u, b[2] * u, 5 * u);
                r.setArcWidth(5 * u);
                r.setArcHeight(5 * u);
                r.getStyleClass().add("byx-desk-mark-bar");
                mark.getChildren().add(r);
            }
            body.setWrapText(true);
            body.setMaxWidth(340);
            body.setAlignment(Pos.CENTER);
            body.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            center = new VBox(8, mark, heading, body);
            center.setAlignment(Pos.CENTER);
            getChildren().add(center);
            setMinSize(0, 0);
        }

        Label heading() {
            return heading;
        }

        Label body() {
            return body;
        }

        Pane mark() {
            return mark;
        }

        void apply(DeskModel.Feed feed, boolean noCandles, MotionService motion) {
            String[] text = switch (feed) {
                case NO_FEED, WAITING -> new String[] {DeskModel.WAITING_TEXT,
                        "Candles appear once the market feed connects. No values are simulated."};
                case DISCONNECTED -> new String[] {"Feed disconnected", "No market data is being received. No values are simulated."};
                case ERROR -> new String[] {"Market feed error", "The feed reported an error. No values are simulated."};
                case UNAVAILABLE -> new String[] {"Market data unavailable", "No source is providing candles. No values are simulated."};
                default -> new String[] {"Waiting for candles",
                        "The feed is connected but has not delivered candles yet. No values are simulated."};
            };
            Fx.text(heading, text[0]);
            Fx.text(body, text[1]);
            // a marca só respira enquanto existe a espera (FULL); não configurado/erro fica parado
            motion.reference.setBreathing(mark, feed.waiting(), MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            lines.setWidth(w);
            lines.setHeight(h);
            if (w != drawnW || h != drawnH) {
                drawnW = w;
                drawnH = h;
                GraphicsContext g = lines.getGraphicsContext2D();
                g.clearRect(0, 0, w, h);
                g.setLineWidth(1);
                g.setStroke(panel.design.ByxTheme.paint("#ffffff08", panel.design.ThemeToken.CHART_GRID));
                for (double y = h - 0.5; y > 0; y -= 60) { // repeating-linear-gradient(0deg ... 59px)
                    g.strokeLine(0, Math.floor(y) + 0.5, w, Math.floor(y) + 0.5);
                }
                g.setStroke(panel.design.ByxTheme.paint("#ffffff06", panel.design.ThemeToken.CHART_GRID));
                for (double x = 119.5; x < w; x += 120) {
                    g.strokeLine(x, 0, x, h);
                }
            }
            for (int i = 0; i < AXIS.length; i++) {
                Label d = dashes.get(i);
                double dw = d.prefWidth(-1);
                double dh = d.prefHeight(-1);
                d.resizeRelocate(w - 16 - dw, h * AXIS[i] - dh / 2, dw, dh);
            }
            double cw = Math.max(0, w - 70);
            double pw = Math.min(cw, center.prefWidth(-1));
            double ph = center.prefHeight(pw);
            center.resizeRelocate((cw - pw) / 2, (h - ph) / 2, pw, ph);
        }
    }
}
