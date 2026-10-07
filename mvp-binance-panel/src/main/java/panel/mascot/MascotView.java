package panel.mascot;

import java.util.concurrent.Executor;
import java.util.function.DoubleSupplier;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.Transition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * Componente ÚNICO do mascote. A UI pede um {@link MascotState} tipado (nunca um arquivo): {@link #setState}, {@link #play} (one-shot), {@link #transitionTo}, {@link #stop}, {@link #setMotionMode}
 * e {@link #dispose}. No máximo UMA animação ativa por view (sem MediaPlayer, sem codec, sem dependência nativa: sprite sheets PNG com alfa decodificadas em segundo plano). Todas as chamadas
 * são na thread FX; nada de I/O nem decodificação roda nela, e nada espera por carregamento: enquanto a sprite sheet não chega, o POSTER do estado é mostrado.
 *
 * <p>Motion: FULL anima (loops via {@code MotionService.loop}, um-tiro via {@code MotionService.play}); REDUCED e OFF mostram só o poster (REDUCED com um fade curto, OFF instantâneo; one-shots viram um
 * poster breve). A preferência efetiva do app (incluindo "reduzir movimento" do sistema) prevalece, salvo override explícito do modo (galeria). Falhas (asset ausente/corrompido, manifesto inválido)
 * caem no poster; se este também falhar, só o palco aparece. Uma geração (token) descarta qualquer resultado atrasado: uma animação antiga nunca reaparece.
 */
public final class MascotView extends StackPane {
    /** O que a view está mostrando (diagnóstico/testes). */
    public enum Showing { NONE, POSTER, LOOP, ONE_SHOT }

    private static final Duration SWAP = Duration.millis(90);
    /** O desenho ocupa 90% do palco (centrado): órbitas e rastros ficam DENTRO do disco. */
    static final double CONTENT = 0.90;

    private final MotionService motion;
    private final double size;
    private final MascotAssets assets;
    private final Executor ui;
    private final DoubleSupplier outputScale;
    private final ImageView content = new ImageView();
    private final ChangeListener<MotionPreference> prefListener = (o, a, b) -> safe(this::apply);

    private MascotState steady = MascotState.IDLE;
    private boolean steadyAnimated = true; // false = o estado estável fica só no poster (um-tiros ainda tocam)
    private MascotState oneShot; // um-tiro em curso (volta ao steady ao terminar)
    private MotionPreference override;
    private MascotManifest manifest;
    private boolean manifestRequested;
    private boolean manifestFailed;
    private int gen;
    private boolean disposed;
    private boolean animated; // FULL permitido para o que está em tela
    private Animation clock;
    private Animation hold;
    private Animation swap;
    private MascotAssets.Lease sheetLease;
    private MascotAssets.Lease posterLease;
    private final java.util.List<Runnable> detachers = new java.util.ArrayList<>();
    private Showing showing = Showing.NONE;
    private MascotState shown;
    private int frame = -1;
    private long applyNanos;
    private long firstFrameMicros = -1;

    public MascotView(MotionService motion, double size) {
        this(motion, size, MascotAssets.shared(), Platform::runLater, () -> {
            try {
                return javafx.stage.Screen.getPrimary().getOutputScaleX();
            } catch (RuntimeException e) {
                return 1.0;
            }
        }, MascotStage.ACCENT_BYX);
    }

    public MascotView(MotionService motion, double size, MascotAssets assets, Executor ui, DoubleSupplier outputScale, Color accent) {
        this.motion = motion;
        this.size = size;
        this.assets = assets;
        this.ui = ui;
        this.outputScale = outputScale;
        content.setSmooth(true);
        content.setPreserveRatio(false);
        content.setMouseTransparent(true);
        Pane layer = new Pane(content);
        layer.setMinSize(size, size);
        layer.setPrefSize(size, size);
        layer.setMaxSize(size, size);
        layer.setMouseTransparent(true);
        getChildren().addAll(new MascotStage(size, accent), layer);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setFocusTraversable(false);
        setAccessibleText("Assistant mascot");
        setAccessibleRole(javafx.scene.AccessibleRole.IMAGE_VIEW);
        motion.preference.addListener(prefListener);
    }

    // ---------------------------------------------------------------------------------------------------------------------- API

    /** Estado estável: loops animam em FULL; one-shots viram o poster do estado (use {@link #play} para tocá-los). */
    public void setState(MascotState s) {
        steady = s == null ? MascotState.IDLE : s;
        steadyAnimated = true;
        oneShot = null;
        safe(this::apply);
    }

    /** Estado estável SÓ com o poster (ex.: IDLE de uma chain LIVE, "sem animação constante"). Um-tiros ({@link #play}) ainda tocam e voltam a este poster. */
    public void setStaticState(MascotState s) {
        steady = s == null ? MascotState.IDLE : s;
        steadyAnimated = false;
        oneShot = null;
        safe(this::apply);
    }

    /** Troca de estado com fade curto (mesma regra de {@link #setState}; o fade já faz parte da troca). */
    public void transitionTo(MascotState s) {
        setState(s);
    }

    /** Toca UMA vez (ATTENTION, NOTIFICATION, TRANSITION), mantém a pose final e volta ao estado estável. Loops pedidos aqui viram {@link #setState}. */
    public void play(MascotState s) {
        if (s == null || s.loops()) {
            setState(s);
            return;
        }
        oneShot = s;
        safe(this::apply);
    }

    /** Para qualquer animação e mostra o poster do estado estável. */
    public void stop() {
        steadyAnimated = false;
        oneShot = null;
        safe(this::apply);
    }

    /** Override explícito do modo (galeria). null volta a seguir a preferência do app. */
    public void setMotionMode(MotionPreference mode) {
        override = mode;
        safe(this::apply);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        gen++;
        motion.preference.removeListener(prefListener);
        stopClocks();
        detachProgress();
        content.setImage(null);
        closeLeases();
        showing = Showing.NONE;
    }

    // ------------------------------------------------------------------------------------------- diagnóstico (testes/galeria)

    public MascotState steadyState() {
        return steady;
    }

    public MascotState shownState() {
        return shown;
    }

    public Showing showing() {
        return showing;
    }

    public int frameIndex() {
        return frame;
    }

    public boolean assetFailed() {
        return manifestFailed || sheetLease != null && sheetLease.failed() && posterLease != null && posterLease.failed();
    }

    /** Microssegundos entre o pedido e o primeiro frame desenhado da animação (-1 se ainda não houve). */
    public long firstFrameMicros() {
        return firstFrameMicros;
    }

    public MotionPreference effectiveMode() {
        return override != null ? override : motion.preference.get();
    }

    // ------------------------------------------------------------------------------------------------------------ núcleo

    private void apply() {
        if (disposed) {
            return;
        }
        gen++;
        stopClocks();
        applyNanos = System.nanoTime();
        firstFrameMicros = -1;
        if (manifest == null) {
            if (!manifestRequested) {
                manifestRequested = true;
                assets.manifest().whenComplete((m, ex) -> ui.execute(() -> safe(() -> {
                    if (ex != null || m == null) {
                        manifestFailed = true;
                    } else {
                        manifest = m;
                        apply();
                    }
                })));
            }
            return; // o manifesto chega em segundo plano; até lá só o palco
        }
        render(gen, true);
    }

    private void render(int token, boolean animate) {
        MascotState target = oneShot != null ? oneShot : steady;
        MascotManifest.Entry e = manifest == null ? null : manifest.entry(target).orElse(null);
        if (e == null) {
            showNothing();
            return;
        }
        MotionPreference mode = effectiveMode();
        animated = animate && mode == MotionPreference.FULL && (oneShot != null || steadyAnimated);
        int dev = devicePx();
        showPoster(e, dev, token, mode);
        if (!animated) {
            if (sheetLease != null) {
                sheetLease.close();
                sheetLease = null;
            }
            if (oneShot != null) { // REDUCED/OFF: o um-tiro vira um poster breve e volta
                MascotState done = oneShot;
                armHold(token, Math.min(900, Math.max(300, e.holdMs() > 0 ? e.holdMs() : 400)), () -> finishOneShot(done));
            }
            return;
        }
        if (sheetLease != null) {
            sheetLease.close();
        }
        sheetLease = assets.sheet(e, dev);
        if (sheetLease.failed()) {
            return; // o poster já está na tela
        }
        whenReady(sheetLease.image(), token, () -> startClock(e, token));
    }

    private void showPoster(MascotManifest.Entry e, int dev, int token, MotionPreference mode) {
        if (posterLease != null) {
            posterLease.close();
        }
        posterLease = assets.poster(e, dev);
        if (posterLease.failed()) {
            showNothing();
            return;
        }
        whenReady(posterLease.image(), token, () -> {
            swapContent(mode, () -> {
                content.setViewport(null);
                content.setImage(posterLease.image());
                content.setLayoutX(size * (1 - CONTENT) / 2);
                content.setLayoutY(size * (1 - CONTENT) / 2);
                content.setFitWidth(size * CONTENT);
                content.setFitHeight(size * CONTENT);
                showing = Showing.POSTER;
                shown = e.state();
                frame = -1;
            });
        });
    }

    private void startClock(MascotManifest.Entry e, int token) {
        if (token != gen || disposed || sheetLease == null || sheetLease.failed()) {
            return;
        }
        Image sheet = sheetLease.image();
        double fx = sheet.getWidth() / e.sheetWidth();
        double fy = sheet.getHeight() / e.sheetHeight();
        double k = size * CONTENT / MascotManifest.CANVAS;
        double off = size * (1 - CONTENT) / 2;
        FrameClock c = new FrameClock(e, fx, fy);
        swapContent(MotionPreference.FULL, () -> {
            content.setImage(sheet);
            content.setViewport(null);
            content.setLayoutX(off + e.cell().x() * k);
            content.setLayoutY(off + e.cell().y() * k);
            content.setFitWidth(e.cell().w() * k);
            content.setFitHeight(e.cell().h() * k);
            showing = e.loop() ? Showing.LOOP : Showing.ONE_SHOT;
            shown = e.state();
            c.draw(0);
        });
        if (e.loop()) {
            Animation a;
            if (override == MotionPreference.FULL && !motion.full()) { // galeria: força FULL só nesta view, fora do registro de loops do app
                c.setCycleCount(Animation.INDEFINITE);
                c.play();
                a = c;
            } else {
                a = motion.loop(this, () -> c);
            }
            if (a == null) { // não está em FULL: poster
                showing = Showing.POSTER;
                return;
            }
            clock = a;
        } else {
            MascotState done = e.state();
            c.setCycleCount(1);
            c.setOnFinished(ev -> armHold(token, Math.max(0, e.holdMs()), () -> finishOneShot(done)));
            clock = motion.play(this, c);
        }
        if (firstFrameMicros < 0) {
            firstFrameMicros = (System.nanoTime() - applyNanos) / 1000;
        }
    }

    private void finishOneShot(MascotState done) {
        if (disposed || oneShot != done) {
            return;
        }
        oneShot = null;
        apply();
    }

    private void armHold(int token, int ms, Runnable then) {
        if (hold != null) {
            hold.stop();
        }
        PauseTransition p = new PauseTransition(Duration.millis(Math.max(1, ms)));
        p.setOnFinished(ev -> {
            if (token == gen && !disposed) {
                safe(then);
            }
        });
        hold = p;
        p.play();
    }

    /** Fade curto ao trocar de conteúdo: FULL 90 ms, REDUCED só fade de entrada curto, OFF instantâneo. */
    private void swapContent(MotionPreference mode, Runnable change) {
        if (swap != null) {
            swap.stop();
            swap = null;
        }
        if (mode == MotionPreference.OFF || content.getImage() == null) {
            change.run();
            content.setOpacity(1);
            return;
        }
        change.run();
        content.setOpacity(0);
        FadeTransition in = new FadeTransition(mode == MotionPreference.FULL ? SWAP : Duration.millis(60), content);
        in.setFromValue(0);
        in.setToValue(1);
        in.setInterpolator(Interpolator.EASE_OUT);
        in.setOnFinished(ev -> content.setOpacity(1));
        swap = in;
        in.play();
    }

    private void showNothing() {
        content.setImage(null);
        showing = Showing.NONE;
        shown = null;
        frame = -1;
    }

    private void whenReady(Image img, int token, Runnable then) {
        if (img == null || img.isError()) {
            return;
        }
        if (img.getProgress() >= 1.0) {
            then.run();
            return;
        }
        @SuppressWarnings("unchecked")
        ChangeListener<Number>[] holder = new ChangeListener[1];
        holder[0] = (o, a, b) -> {
            if (b.doubleValue() >= 1.0) {
                img.progressProperty().removeListener(holder[0]);
                ui.execute(() -> safe(() -> {
                    if (token == gen && !disposed && !img.isError()) {
                        then.run();
                    }
                }));
            }
        };
        img.progressProperty().addListener(holder[0]);
        detachers.add(() -> img.progressProperty().removeListener(holder[0]));
    }

    private void detachProgress() {
        detachers.forEach(Runnable::run);
        detachers.clear();
    }

    private void stopClocks() {
        if (clock != null) {
            if (motion.isLooping(clock)) {
                motion.removeLoop(clock);
            } else {
                clock.stop();
            }
            clock = null;
        }
        if (hold != null) {
            hold.stop();
            hold = null;
        }
        detachProgress();
        showing = showing == Showing.LOOP || showing == Showing.ONE_SHOT ? Showing.POSTER : showing;
    }

    private void closeLeases() {
        if (sheetLease != null) {
            sheetLease.close();
            sheetLease = null;
        }
        if (posterLease != null) {
            posterLease.close();
            posterLease = null;
        }
    }

    private int devicePx() {
        return (int) Math.round(size * CONTENT * Math.max(1.0, outputScale.getAsDouble())); // px de dispositivo do CANVAS inteiro (o desenho ocupa 90% do palco)
    }

    /** Nenhuma exceção sai daqui para a thread FX (nunca o overlay global "Something went wrong"). */
    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            showNothing();
            manifestFailed = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------- relógio de frames

    private final class FrameClock extends Transition {
        private final MascotManifest.Entry e;
        private final double fx;
        private final double fy;
        private int last = -1;

        FrameClock(MascotManifest.Entry e, double fx, double fy) {
            this.e = e;
            this.fx = fx;
            this.fy = fy;
            long ms = e.durationMs() > 0 ? e.durationMs() : Math.round(e.frames() * 1000.0 / e.fps());
            setCycleDuration(Duration.millis(Math.max(16, ms)));
            setInterpolator(Interpolator.LINEAR);
        }

        @Override
        protected void interpolate(double frac) {
            draw(Math.min(e.frames() - 1, (int) (frac * e.frames())));
        }

        void draw(int i) {
            if (i == last || disposed) {
                return;
            }
            last = i;
            frame = i;
            int col = i % e.cols();
            int row = i / e.cols();
            Image img = content.getImage();
            if (img == null) {
                return;
            }
            double w = Math.min(e.cell().w() * fx, img.getWidth() - col * e.cell().w() * fx);
            double h = Math.min(e.cell().h() * fy, img.getHeight() - row * e.cell().h() * fy);
            content.setViewport(new Rectangle2D(col * e.cell().w() * fx, row * e.cell().h() * fy, Math.max(1, w), Math.max(1, h)));
        }
    }
}
