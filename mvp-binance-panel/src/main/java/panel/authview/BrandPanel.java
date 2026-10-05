package panel.authview;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * Região de marca das telas de entrada (P3.20): malha poligonal no Canvas, seis barras, wordmark e tagline.
 * Decorativa: sem nó de acessibilidade, sem entrada, nunca atrasa um fluxo. FULL: tudo anima a 30 fps;
 * REDUCED: malha, barras e tagline paradas, só o brilho atrás das barras respira (8 s, 12 fps); OFF: um
 * quadro estático em p = 0,62. Um único loop, só enquanto visível; resize redesenha sem reiniciar a fase.
 */
public final class BrandPanel extends StackPane {
    private static final double[] BAR_WIDTH = {0.92, 0.76, 0.60, 0.44, 0.30, 0.18};
    private static final BrandFieldModel MODEL = new BrandFieldModel();

    private final MotionService motion;
    private final Canvas canvas = new Canvas();
    private final List<Region> bars = new ArrayList<>();
    private final VBox barBox = new VBox(14);
    private final Text line1 = new Text("Capital, measured.");
    private final Text line2 = new Text("Research before risk.");
    private final VBox content;
    private AnimationTimer timer;
    private double phase = BrandFieldModel.STATIC_PHASE;
    private double t;
    private long last;
    private boolean loading;
    private boolean entered;
    private int frames;
    private boolean expanded;
    private final ChangeListener<Object> modeListener = (o, a, b) -> restart();
    // fracos: a janela vive o app inteiro e não pode reter painéis de login descartados
    private final ChangeListener<Boolean> showingListener = (o, a, b) -> restart();
    private final ChangeListener<javafx.stage.Window> windowListener = (o, oldW, win) -> {
        if (win != null) {
            win.showingProperty().addListener(new WeakChangeListener<>(showingListener));
        }
        restart();
    };
    private final List<ParallelTransition> entrance = new ArrayList<>();

    public BrandPanel(MotionService motion) {
        this.motion = motion;
        getStyleClass().add("byx-brand");
        setAccessibleRole(null);
        canvas.setMouseTransparent(true);
        canvas.setFocusTraversable(false);
        Pane canvasHost = new Pane(canvas);
        canvasHost.setMouseTransparent(true);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> drawStill());
        canvas.heightProperty().addListener((o, a, b) -> drawStill());

        Label name = new Label("BYX-MVP");
        name.getStyleClass().add("byx-brand-name");
        Label by = new Label("BY BUYNNEX");
        by.getStyleClass().add("byx-label");
        VBox wordmark = new VBox(6, name, by);

        for (int i = 0; i < 6; i++) {
            Region bar = new Region();
            bar.getStyleClass().add("byx-brand-bar");
            bar.setMinHeight(34);
            bar.setPrefHeight(34);
            bar.setMaxHeight(34);
            bar.setOpacity(BrandFieldModel.BAR_BASE[i]);
            final int n = i;
            bar.maxWidthProperty().bind(barBox.widthProperty().multiply(BAR_WIDTH[n]));
            bar.prefWidthProperty().bind(barBox.widthProperty().multiply(BAR_WIDTH[n]));
            bars.add(bar);
        }
        barBox.getChildren().addAll(bars);
        barBox.setAlignment(Pos.TOP_RIGHT);
        barBox.setFillWidth(false);
        StackPane barsRow = new StackPane(barBox);
        StackPane.setAlignment(barBox, Pos.CENTER_RIGHT);
        setExpanded(false);

        line1.getStyleClass().add("byx-tagline");
        line2.getStyleClass().addAll("byx-tagline", "second");
        TextFlow tagline = new TextFlow(line1, new Text("\n"), line2);
        tagline.getStyleClass().add("byx-tagline-flow");

        Region upper = new Region();
        Region lower = new Region();
        VBox.setVgrow(upper, Priority.ALWAYS);
        VBox.setVgrow(lower, Priority.ALWAYS);
        content = new VBox(wordmark, upper, barsRow, lower, tagline);
        content.getStyleClass().add("byx-brand-content");
        getChildren().addAll(canvasHost, content);

        motion.preference.addListener(new WeakChangeListener<>(modeListener));
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) {
                stop();
            } else {
                // a cena pode chegar antes da janela: acompanha a janela e o "showing" dela (fracos)
                scene.windowProperty().addListener(new WeakChangeListener<>(windowListener));
                if (scene.getWindow() != null) {
                    scene.getWindow().showingProperty().addListener(new WeakChangeListener<>(showingListener));
                }
                restart();
                playEntranceOnce();
            }
        });
    }

    /** Breakpoint EXPANDED (1920): padding 96, tagline 52/58, barras a 62%. */
    public void setExpanded(boolean on) {
        expanded = on;
        getStyleClass().remove("expanded");
        if (on) {
            getStyleClass().add("expanded");
        }
        double pad = on ? 96 : 64;
        barBox.maxWidthProperty().unbind();
        barBox.maxWidthProperty().bind(widthProperty().subtract(2 * pad).multiply(on ? 0.62 : 0.70));
    }

    /** Formulário carregando: período 4,5 s (só FULL percebe). */
    public void setLoading(boolean on) {
        loading = on;
    }

    public boolean running() {
        return timer != null;
    }

    public int frames() {
        return frames;
    }

    public double phase() {
        return phase;
    }

    public double barOpacity(int n) {
        return bars.get(n).getOpacity();
    }

    /** Barras entram uma vez por sessão (1000 ms, stagger 120 ms; REDUCED só opacidade; OFF nada). */
    private void playEntranceOnce() {
        if (entered) {
            return;
        }
        entered = true;
        if (motion.off()) {
            return;
        }
        Duration d = motion.full() ? Duration.millis(1000) : Duration.millis(120);
        for (int i = 0; i < bars.size(); i++) {
            Region bar = bars.get(i);
            FadeTransition f = new FadeTransition(d, bar);
            f.setFromValue(0);
            f.setToValue(BrandFieldModel.BAR_BASE[i]);
            ParallelTransition p = new ParallelTransition(f);
            if (motion.translateAllowed()) {
                TranslateTransition tr = new TranslateTransition(d, bar);
                tr.setFromY(8);
                tr.setToY(0);
                p.getChildren().add(tr);
            }
            p.setDelay(Duration.millis(motion.full() ? i * 120 : 0));
            p.setInterpolator(panel.motion.MotionSpec.STANDARD);
            p.setOnFinished(e -> entrance.remove(p));
            entrance.add(p);
            p.play();
        }
    }

    private boolean visibleNow() {
        return getScene() != null && getScene().getWindow() != null && getScene().getWindow().isShowing() && isVisible();
    }

    private void restart() {
        stop();
        MotionPreference m = motion.preference.get();
        if (m == MotionPreference.OFF || !visibleNow()) {
            phase = BrandFieldModel.STATIC_PHASE;
            drawStill();
            return;
        }
        last = 0;
        long minGap = 1_000_000_000L / (m == MotionPreference.FULL ? BrandFieldModel.FPS : BrandFieldModel.REDUCED_FPS);
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (last != 0 && now - last < minGap) {
                    return;
                }
                double dt = last == 0 ? 0 : Math.min(100, (now - last) / 1e6);
                last = now;
                t += dt;
                if (motion.preference.get() == MotionPreference.FULL) {
                    phase = BrandFieldModel.advance(phase, dt, loading);
                    draw(t, phase, true, 0);
                    paintDom(phase, true);
                } else {
                    double pulse = 0.03 * Math.sin(2 * Math.PI * t / BrandFieldModel.REDUCED_GLOW_PERIOD_MS);
                    draw(0, BrandFieldModel.STATIC_PHASE, false, pulse);
                    paintDom(BrandFieldModel.STATIC_PHASE, false);
                }
                frames++;
            }
        };
        timer.start();
    }

    private void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /** Resize ou modo parado: redesenha sem reiniciar a fase. */
    private void drawStill() {
        boolean wave = timer != null && motion.full();
        draw(wave ? t : 0, wave ? phase : BrandFieldModel.STATIC_PHASE, wave, 0);
        paintDom(wave ? phase : BrandFieldModel.STATIC_PHASE, wave);
    }

    private void paintDom(double p, boolean wave) {
        if (entrance.isEmpty()) { // durante a entrada única as barras pertencem a ela
            for (int n = 0; n < bars.size(); n++) {
                bars.get(n).setOpacity(BrandFieldModel.barOpacity(n, p, wave));
            }
        }
        // varredura da tagline: banda clara em 150% p - 20% da largura (só FULL)
        if (wave) {
            double c = 1.5 * p - 0.20;
            line1.setFill(sweep(c, Color.web("#CBD3E8"), Color.WHITE));
            line2.setFill(sweep(c, Color.web("#AAB3C7"), Color.web("#D8DFF2")));
        } else {
            line1.setFill(Color.web("#CBD3E8"));
            line2.setFill(Color.web("#AAB3C7"));
        }
    }

    private static LinearGradient sweep(double c, Color base, Color hi) {
        double a = clamp(c - 0.18);
        double b = clamp(c);
        double e = clamp(c + 0.18);
        return new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, base), new Stop(a, base),
                new Stop(b, hi), new Stop(e, base), new Stop(1, base));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private void draw(double tt, double p, boolean wave, double glowPulse) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        // fundo da região: bg1 + brilho inferior esquerdo (radial-gradient 20% 110% #7C96FF1A)
        g.setFill(Color.web("#121723"));
        g.fillRect(0, 0, w, h);
        g.setFill(new RadialGradient(0, 0, w * 0.2, h * 1.1, 900, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#7C96FF1A")), new Stop(1, Color.web("#7C96FF00"))));
        g.fillRect(0, 0, w, h);
        double front = wave ? -0.2 + 1.4 * p : -9;
        double[][] pos = new double[MODEL.nodes().size()][];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = BrandFieldModel.position(MODEL.nodes().get(i), tt);
        }
        g.setFill(new RadialGradient(0, 0, w * 0.62, h * 0.55, w * 0.55, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(170, 176, 200, 0.05)), new Stop(1, Color.rgb(170, 176, 200, 0))));
        g.fillRect(0, 0, w, h);
        double bump = (wave ? BrandFieldModel.g((p - 0.6) / 0.12) * 0.06 : 0) + glowPulse;
        g.setFill(new RadialGradient(0, 0, w * 0.78, h * 0.38, w * 0.38, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(124, 150, 255, clamp(0.14 + bump))), new Stop(1, Color.rgb(124, 150, 255, 0))));
        g.fillRect(0, 0, w, h);
        for (BrandFieldModel.Star s : MODEL.stars()) {
            double a = wave ? 0.25 + 0.35 * (0.5 + 0.5 * Math.sin(tt * s.sp() + s.ph())) : 0.4;
            g.setFill(Color.rgb(220, 228, 255, a));
            g.fillRect(s.x() * w, s.y() * h, s.s(), s.s());
        }
        for (BrandFieldModel.Triangle tr : MODEL.triangles()) {
            double[] A = pos[tr.a()];
            double[] B = pos[tr.b()];
            double[] C = pos[tr.c()];
            double cx = (A[0] + B[0] + C[0]) / 3;
            double a = tr.fa() * (0.8 + 0.2 * Math.sin(tt * tr.sp() + tr.ph()))
                    + (wave ? 0.05 * BrandFieldModel.g((cx - front) / BrandFieldModel.FRONT_WIDTH) : 0);
            g.setFill(Color.rgb(120, 140, 190, clamp(a)));
            g.fillPolygon(new double[] {A[0] * w, B[0] * w, C[0] * w}, new double[] {A[1] * h, B[1] * h, C[1] * h}, 3);
        }
        g.setLineWidth(1);
        for (int[] e : MODEL.edges()) {
            double[] A = pos[e[0]];
            double[] B = pos[e[1]];
            double a = BrandFieldModel.EDGE_ALPHA
                    + (wave ? BrandFieldModel.FRONT_ALPHA * BrandFieldModel.g(((A[0] + B[0]) / 2 - front) / BrandFieldModel.FRONT_WIDTH) : 0);
            g.setStroke(Color.rgb(150, 170, 230, clamp(a)));
            g.strokeLine(A[0] * w, A[1] * h, B[0] * w, B[1] * h);
        }
        for (double[] q : pos) {
            double a = 0.3 + (wave ? 0.5 * BrandFieldModel.g((q[0] - front) / BrandFieldModel.FRONT_WIDTH) : 0);
            g.setFill(Color.rgb(190, 205, 255, clamp(a)));
            g.fillOval(q[0] * w - 1.2, q[1] * h - 1.2, 2.4, 2.4);
        }
    }

    public void dispose() {
        stop();
        entrance.forEach(ParallelTransition::stop);
        entrance.clear();
    }
}
