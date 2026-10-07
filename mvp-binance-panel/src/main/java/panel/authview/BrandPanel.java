package panel.authview;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Decorativa: sem nó de acessibilidade, sem entrada, nunca atrasa um fluxo. FULL: faixa de luz da onda a 2,5 quadros/s e malha
 * (deriva de 18-40 s por volta) redesenhada a 0,6 Hz, nunca a 30/60 Hz; REDUCED: malha, barras e tagline paradas, só o brilho
 * atrás das barras respira (8 s, 1 quadro/s, sem redesenhar o canvas); OFF: um quadro estático em p = 0,62.
 * <p>
 * Custo: o perfil do login parado (JFR) mostrou ~98% das amostras de CPU na thread de render, dentro do rasterizador de
 * contornos (Marlin) do Canvas: 60 triângulos + 101 linhas + 42 nós + 80 estrelas redesenhados em tela cheia 30x/s.
 * Agora: (1) o preenchimento (fundo + triângulos, só áreas suaves e de baixo contraste) vive num canvas de METADE da
 * resolução e é ampliado; (2) linhas/nós/estrelas ficam num canvas de resolução cheia; (3) o brilho atrás das barras é um
 * nó de cena (opacidade), sem tocar o canvas; (4) o relógio é um agendador de baixa frequência (sem laço de animação/60 Hz);
 * (5) tudo pausa com a janela oculta, minimizada ou sem foco e retoma na mesma fase (sem salto).
 */
public final class BrandPanel extends StackPane {
    private static final double[] BAR_WIDTH = {0.92, 0.76, 0.60, 0.44, 0.30, 0.18};
    private static final BrandFieldModel MODEL = new BrandFieldModel();
    /** Largura da faixa (fração da região): ~2 x FRONT_WIDTH x 1,5. */
    private static final double BAND_WIDTH = 0.36;
    /** Passo do relógio decorativo: FULL 2,5 quadros/s (faixa de luz; a malha leva 18-40 s por volta), REDUCED 1/s (só a opacidade do brilho, passo < 1/255). */
    static final long FULL_TICK_MS = 400;
    static final long REDUCED_TICK_MS = 1000;
    /**
     * Medido (JFR + ps, janela com foco, JVM de desenvolvimento): qualquer mudança de cena custa ~0,5% de um núcleo POR Hz, quase
     * independente da área (o custo é apresentar a janela), e o redesenho de canvas+texto custa ~2x isso. Então a faixa de luz da
     * "onda" (um nó de cena) anda a 2,5 Hz e a malha/estrelas/tagline redesenham a cada 4º quadro (0,6 Hz).
     */
    static final int REDRAW_EVERY = 4;
    /** Relógio de baixa frequência compartilhado (sem laço de animação: o laço de pulsos de 60 Hz só roda quando há o que desenhar). */
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "brand-clock");
        t.setDaemon(true);
        return t;
    });

    private final MotionService motion;
    /** Preenchimento (fundo + triângulos): metade da resolução, ampliado 2x. */
    private final Canvas fillCanvas = new Canvas();
    /** Linhas, nós e estrelas: resolução cheia (traços finos). */
    private final Canvas lineCanvas = new Canvas();
    /** Brilho atrás das barras: nó de cena, só a opacidade muda. */
    private final Rectangle glow = new Rectangle();
    /** Faixa de luz da onda (substitui o realce por aresta do original): um nó de cena que só se desloca, sem tocar os canvases. */
    private final Rectangle band = new Rectangle();
    private final List<Region> bars = new ArrayList<>();
    private final VBox barBox = new VBox(14);
    private final Text line1 = new Text("Capital, measured.");
    private final Text line2 = new Text("Research before risk.");
    private final VBox content;
    private ScheduledFuture<?> clock;
    private final AtomicBoolean queued = new AtomicBoolean();
    private long generation;
    private double phase = BrandFieldModel.STATIC_PHASE;
    private double t;
    private long last;
    private boolean loading;
    private boolean entered;
    private int frames;
    private boolean expanded;
    private Boolean focusOverride;
    private final ChangeListener<Object> modeListener = (o, a, b) -> restart();
    // fracos: a janela vive o app inteiro e não pode reter painéis de login descartados
    private final ChangeListener<Boolean> showingListener = (o, a, b) -> restart();
    private final ChangeListener<javafx.stage.Window> windowListener = (o, oldW, win) -> {
        watch(win);
        restart();
    };
    private final List<ParallelTransition> entrance = new ArrayList<>();

    public BrandPanel(MotionService motion) {
        this.motion = motion;
        getStyleClass().add("byx-brand");
        setAccessibleRole(null);
        fillCanvas.setMouseTransparent(true);
        fillCanvas.setFocusTraversable(false);
        fillCanvas.getTransforms().add(new Scale(2, 2, 0, 0));
        lineCanvas.setMouseTransparent(true);
        lineCanvas.setFocusTraversable(false);
        glow.setMouseTransparent(true);
        glow.widthProperty().bind(widthProperty());
        glow.heightProperty().bind(heightProperty());
        band.setMouseTransparent(true);
        band.setVisible(false);
        band.widthProperty().bind(widthProperty().multiply(BAND_WIDTH));
        band.heightProperty().bind(heightProperty());
        Pane canvasHost = new Pane(fillCanvas, glow, lineCanvas, band);
        canvasHost.setMouseTransparent(true);
        fillCanvas.widthProperty().bind(widthProperty().divide(2));
        fillCanvas.heightProperty().bind(heightProperty().divide(2));
        lineCanvas.widthProperty().bind(widthProperty());
        lineCanvas.heightProperty().bind(heightProperty());
        ChangeListener<Number> resized = (o, a, b) -> drawStill();
        // no próprio canvas: redimensionar um Canvas limpa o conteúdo, então o redesenho tem de vir DEPOIS da nova dimensão
        lineCanvas.widthProperty().addListener(resized);
        lineCanvas.heightProperty().addListener(resized);
        fillCanvas.widthProperty().addListener(resized);
        fillCanvas.heightProperty().addListener(resized);

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
                // a cena pode chegar antes da janela: acompanha a janela e o "showing"/foco/minimizado dela (fracos)
                scene.windowProperty().addListener(new WeakChangeListener<>(windowListener));
                watch(scene.getWindow());
                restart();
                playEntranceOnce();
            }
        });
    }

    private void watch(javafx.stage.Window win) {
        if (win == null) {
            return;
        }
        win.showingProperty().addListener(new WeakChangeListener<>(showingListener));
        win.focusedProperty().addListener(new WeakChangeListener<>(showingListener));
        if (win instanceof javafx.stage.Stage stage) {
            stage.iconifiedProperty().addListener(new WeakChangeListener<>(showingListener));
        }
    }

    /** Teste/galeria: força "com foco" (true) ou "sem foco" (false); null volta ao foco real da janela. */
    public void setFocusOverride(Boolean focused) {
        focusOverride = focused;
        restart();
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
        return clock != null;
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

    /** Janela visível, não minimizada, com foco e nó visível: só então a decoração anima. */
    private boolean visibleNow() {
        var window = getScene() == null ? null : getScene().getWindow();
        if (window == null || !window.isShowing() || !isVisible()) {
            return false;
        }
        if (window instanceof javafx.stage.Stage stage && stage.isIconified()) {
            return false;
        }
        return focusOverride != null ? focusOverride : window.isFocused();
    }

    /**
     * Reavalia o relógio. Pausar NÃO zera a fase nem o tempo: ao voltar, o quadro seguinte continua do mesmo ponto (dt do primeiro
     * quadro é 0), sem salto. OFF ou sem cena: um quadro estático.
     */
    private void restart() {
        stop();
        MotionPreference m = motion.preference.get();
        if (m == MotionPreference.OFF) {
            phase = BrandFieldModel.STATIC_PHASE;
            drawStill();
            return;
        }
        if (!visibleNow()) {
            return; // pausado: o último quadro fica na tela
        }
        last = 0;
        long period = m == MotionPreference.FULL ? FULL_TICK_MS : REDUCED_TICK_MS;
        long token = ++generation;
        clock = CLOCK.scheduleWithFixedDelay(() -> {
            if (queued.compareAndSet(false, true)) {
                try {
                    javafx.application.Platform.runLater(() -> {
                        queued.set(false);
                        tick(token);
                    });
                } catch (IllegalStateException noToolkit) {
                    queued.set(false);
                }
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }

    /** Um quadro do relógio (thread FX). */
    private void tick(long token) {
        if (token != generation || clock == null) {
            return;
        }
        long now = System.nanoTime();
        double dt = last == 0 ? 0 : Math.min(250, (now - last) / 1e6);
        last = now;
        t += dt;
        if (motion.preference.get() == MotionPreference.FULL) {
            phase = BrandFieldModel.advance(phase, dt, loading);
            placeBand(phase);
            if (frames % REDRAW_EVERY == 0) {
                draw(t, phase, true);
                paintDom(phase, true);
            }
        } else {
            // REDUCED: malha parada; só a opacidade do brilho respira (nenhum redesenho do canvas)
            double pulse = 0.03 * Math.sin(2 * Math.PI * t / BrandFieldModel.REDUCED_GLOW_PERIOD_MS);
            setGlow(0.14 + pulse);
        }
        frames++;
    }

    private void stop() {
        generation++;
        if (clock != null) {
            clock.cancel(false);
            clock = null;
        }
    }

    /** Resize ou modo parado: redesenha sem reiniciar a fase. */
    private void drawStill() {
        boolean wave = motion.full() && motion.preference.get() == MotionPreference.FULL && t > 0;
        draw(wave ? t : 0, wave ? phase : BrandFieldModel.STATIC_PHASE, wave);
        paintDom(wave ? phase : BrandFieldModel.STATIC_PHASE, wave);
        if (wave) {
            placeBand(phase);
        } else {
            band.setVisible(false);
            setGlow(0.14);
        }
    }

    private void paintDom(double p, boolean wave) {
        if (entrance.isEmpty()) { // durante a entrada única as barras pertencem a ela
            for (int n = 0; n < bars.size(); n++) {
                double target = BrandFieldModel.barOpacity(n, p, wave);
                if (Math.abs(bars.get(n).getOpacity() - target) > 0.004) {
                    bars.get(n).setOpacity(target);
                }
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

    private double bandW = -1;

    /** Centro da faixa em x = (-0,2 + 1,4 p) da largura (a mesma frente da onda original). */
    private void placeBand(double p) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w != bandW) {
            bandW = w;
            band.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, Color.rgb(124, 150, 255, 0)),
                    new Stop(0.5, Color.rgb(124, 150, 255, 0.07)), new Stop(1, Color.rgb(124, 150, 255, 0))));
        }
        band.setVisible(true);
        band.setTranslateX((-0.2 + 1.4 * p) * w - band.getWidth() / 2);
    }

    private double glowW = -1;
    private double glowH = -1;

    /** Brilho radial (78%, 38%) atrás das barras; só a opacidade muda, o gradiente só é refeito no resize. */
    private void setGlow(double alpha) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w != glowW || h != glowH) {
            glowW = w;
            glowH = h;
            glow.setFill(new RadialGradient(0, 0, w * 0.78, h * 0.38, w * 0.38, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(124, 150, 255, 1)), new Stop(1, Color.rgb(124, 150, 255, 0))));
        }
        double o = clamp(alpha);
        if (Math.abs(glow.getOpacity() - o) > 0.002) {
            glow.setOpacity(o);
        }
    }

    private void draw(double tt, double p, boolean wave) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // o realce da onda é a faixa de luz (placeBand); a malha não é repintada por ele
        double[][] pos = new double[MODEL.nodes().size()][];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = BrandFieldModel.position(MODEL.nodes().get(i), tt);
        }
        // pulso do brilho: base 0,14 + um pico suave perto de p = 0,6 (só FULL)
        setGlow(0.14 + (wave ? BrandFieldModel.g((p - 0.6) / 0.12) * 0.06 : 0));

        // --- preenchimento, meia resolução (coordenadas lógicas; o canvas é ampliado 2x) ---
        GraphicsContext g = fillCanvas.getGraphicsContext2D();
        g.setTransform(0.5, 0, 0, 0.5, 0, 0);
        g.clearRect(0, 0, w, h);
        // fundo da região: bg1 + brilho inferior esquerdo (radial-gradient 20% 110% #7C96FF1A)
        g.setFill(Color.web("#121723"));
        g.fillRect(0, 0, w, h);
        g.setFill(new RadialGradient(0, 0, w * 0.2, h * 1.1, 900, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#7C96FF1A")), new Stop(1, Color.web("#7C96FF00"))));
        g.fillRect(0, 0, w, h);
        g.setFill(new RadialGradient(0, 0, w * 0.62, h * 0.55, w * 0.55, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(170, 176, 200, 0.05)), new Stop(1, Color.rgb(170, 176, 200, 0))));
        g.fillRect(0, 0, w, h);
        for (BrandFieldModel.Triangle tr : MODEL.triangles()) {
            double[] A = pos[tr.a()];
            double[] B = pos[tr.b()];
            double[] C = pos[tr.c()];
            double a = tr.fa() * (0.8 + 0.2 * Math.sin(tt * tr.sp() + tr.ph()));
            g.setFill(Color.rgb(120, 140, 190, clamp(a)));
            g.fillPolygon(new double[] {A[0] * w, B[0] * w, C[0] * w}, new double[] {A[1] * h, B[1] * h, C[1] * h}, 3);
        }

        // --- estrelas, arestas e nós, resolução cheia ---
        GraphicsContext L = lineCanvas.getGraphicsContext2D();
        L.clearRect(0, 0, w, h);
        for (BrandFieldModel.Star s : MODEL.stars()) {
            double a = wave ? 0.25 + 0.35 * (0.5 + 0.5 * Math.sin(tt * s.sp() + s.ph())) : 0.4;
            L.setFill(Color.rgb(220, 228, 255, a));
            L.fillRect(s.x() * w, s.y() * h, s.s(), s.s());
        }
        L.setLineWidth(1);
        for (int[] e : MODEL.edges()) {
            double[] A = pos[e[0]];
            double[] B = pos[e[1]];
            L.setStroke(Color.rgb(150, 170, 230, BrandFieldModel.EDGE_ALPHA));
            L.strokeLine(A[0] * w, A[1] * h, B[0] * w, B[1] * h);
        }
        for (double[] q : pos) {
            L.setFill(Color.rgb(190, 205, 255, 0.3));
            L.fillOval(q[0] * w - 1.2, q[1] * h - 1.2, 2.4, 2.4);
        }
    }

    public void dispose() {
        stop();
        entrance.forEach(ParallelTransition::stop);
        entrance.clear();
    }
}
