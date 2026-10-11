package panel.mascot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.Transition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * Componente ÚNICO do mascote. A UI pede um {@link MascotState} tipado (nunca um arquivo): {@link #setState}, {@link #setStaticState}, {@link #play} (one-shot), {@link #transitionTo}, {@link #stop},
 * {@link #setMotionMode} e {@link #dispose}. No máximo UMA animação ativa por view (sem MediaPlayer, sem codec, sem dependência nativa: sprite sheets PNG com alfa decodificadas em segundo plano). Todas as
 * chamadas são na thread FX; nada de I/O nem decodificação roda nela e nada espera por carregamento: enquanto os assets não chegam, o POSTER do estado é mostrado.
 *
 * <p><b>IDLE vivo:</b> o IDLE não é um PNG parado: usa um RIG (corpo + dois olhos em camadas) e um motor procedural barato ({@link IdleLife}): piscar natural, respiração, deriva e inclinação quase
 * imperceptíveis, olhares ocasionais e, em FULL, olhar discretamente para o ponteiro e inclinar/ficar curioso quando ele chega perto ou passa sobre o mascote. O ponteiro vem SÓ dos eventos de mouse que
 * o JavaFX já entrega à janela do app (sem hook global, acessibilidade ou monitoramento nativo). O olhar é um alvo normalizado com zona morta, histerese, clamp e suavização ({@link Gaze}); os olhos
 * deslocam poucos pixels, proporcionais ao tamanho, e o corpo nunca é movido para fingir o olhar. Estados funcionais (THINKING/PROCESSING/SYNCING) e one-shots (ATTENTION/NOTIFICATION) têm prioridade:
 * nesses, não há seguimento do ponteiro.
 *
 * <p><b>Motion:</b> FULL = tudo; REDUCED = poster + piscar ocasional (sem seguir, sem deriva); OFF = estático. A preferência efetiva do app (incluindo "reduzir movimento" do sistema) prevalece; só a
 * galeria sobrescreve. Janela sem foco, minimizada, view invisível ou fora da cena: o motor e o rastreio PARAM (nenhum timer fica rodando). Falhas de asset caem no loop de sheet do IDLE e depois no poster;
 * nenhuma exceção sai para a thread FX. Uma geração (token) descarta resultados atrasados.
 */
public final class MascotView extends StackPane {
    /** O que a view está mostrando (diagnóstico/testes). */
    public enum Showing { NONE, POSTER, RIG, LOOP, ONE_SHOT }

    private static final Duration SWAP = Duration.millis(90);
    /** O desenho ocupa 90% da view (centrado): órbitas e rastros ficam dentro da área do mascote. */
    static final double CONTENT = 0.90;
    static final double MAX_GAZE_X = 0.040;
    static final double MAX_GAZE_Y = 0.028;
    /** Degraus mínimos VISÍVEIS (≈0,2 px): abaixo disso nada é reaplicado e a cena não é suja à toa. */
    static final double BREATH_STEP = 0.005;
    static final double TILT_STEP = 0.15;
    static final double DRIFT_STEP = 0.22;
    static final double GAZE_STEP_PX = 0.12;
    static final long TICK_BUSY_MS = 42;
    static final long TICK_CALM_MS = 160;
    /** Agendador de BAIXA frequência (nenhuma animação JavaFX ativa): o loop de pulsos de 60 Hz só roda quando algo muda de fato. */
    private static final ScheduledExecutorService LIFE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mascot-life");
        t.setDaemon(true);
        return t;
    });

    private final MotionService motion;
    private final double size;
    private final MascotAssets assets;
    private final Executor ui;
    private final DoubleSupplier outputScale;
    private final ImageView content = new ImageView();
    private final ImageView bodyView = new ImageView();
    private final ImageView eyeLeftView = new ImageView();
    private final ImageView eyeRightView = new ImageView();
    private final ImageView haloView = new ImageView(); // contorno PRÉ-CALCULADO do corpo (sem efeito em tempo de execução)
    private final Group rig = new Group(haloView, bodyView, eyeLeftView, eyeRightView);
    private final Pane layer = new Pane();
    private final MascotStage stage;
    private final ChangeListener<MotionPreference> prefListener = (o, a, b) -> safe(this::apply);
    private final List<Runnable> windowDetachers = new ArrayList<>();
    private final Scale breathScale = new Scale();
    private final Rotate tilt = new Rotate();

    private MascotStage.Mode stageMode = MascotStage.DEFAULT_MODE;
    private MascotState steady = MascotState.IDLE;
    private boolean steadyAnimated = true;
    private MascotState oneShot;
    private MotionPreference override;
    private MascotManifest manifest;
    private boolean manifestRequested;
    private boolean manifestFailed;
    private int gen;
    private boolean disposed;
    private boolean animated;
    private Animation clock;
    private Animation hold;
    private Animation swap;
    private MascotAssets.Lease sheetLease;
    private MascotAssets.Lease posterLease;
    private final List<MascotAssets.Lease> rigLeases = new ArrayList<>();
    private final List<Runnable> detachers = new ArrayList<>();
    private Showing showing = Showing.NONE;
    private MascotState shown;
    private int frame = -1;
    private long applyNanos;
    private long firstFrameMicros = -1;

    // vida do IDLE
    private MascotManifest.Rig rigSpec;
    private IdleLife life;
    private ScheduledFuture<?> lifeFuture;
    private int lifeToken;
    private boolean lifeActive;
    private IdleLife.Pose applied = IdleLife.Pose.REST;
    private boolean rigVisible;
    private IdleLife.Pose pose = IdleLife.Pose.REST;
    private Boolean focusOverride;
    private boolean windowFocused = true;
    private boolean windowShowing = true;
    private boolean windowIconified;
    private EventHandler<MouseEvent> sceneMouse;
    private Scene mouseScene;
    private long seed = System.nanoTime();
    private long nowOverride = -1;

    // interação
    private boolean interactive;
    private Runnable onActivate;
    private boolean hover;

    public MascotView(MotionService motion, double size) {
        this(motion, size, MascotAssets.shared(), Platform::runLater, () -> {
            try {
                return javafx.stage.Screen.getPrimary().getOutputScaleX();
            } catch (RuntimeException e) {
                return 1.0;
            }
        }, MascotStage.ACCENT_BYX);
    }

    public MascotView(MotionService motion, MascotSize size) {
        this(motion, size.px());
    }

    public MascotView(MotionService motion, double size, MascotAssets assets, Executor ui, DoubleSupplier outputScale, Color ignoredAccent) {
        this.motion = motion;
        this.size = size;
        this.assets = assets;
        this.ui = ui;
        this.outputScale = outputScale;
        for (ImageView v : new ImageView[] {content, haloView, bodyView, eyeLeftView, eyeRightView}) {
            v.setSmooth(true);
            v.setPreserveRatio(false);
            v.setMouseTransparent(true);
        }
        rig.setVisible(false);
        rig.getTransforms().setAll(breathScale, tilt);
        layer.getChildren().addAll(content, rig);
        layer.setMinSize(size, size);
        layer.setPrefSize(size, size);
        layer.setMaxSize(size, size);
        layer.setMouseTransparent(true);
        stage = new MascotStage(size, stageMode);
        applyStageEffect();
        getChildren().addAll(stage, layer);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setFocusTraversable(false);
        setPickOnBounds(false); // só intercepta o mouse quando interativo (ver setInteractive)
        setMouseTransparent(true);
        setAccessibleText("Assistant mascot");
        setAccessibleRole(javafx.scene.AccessibleRole.IMAGE_VIEW);
        motion.preference.addListener(prefListener);
        sceneProperty().addListener((o, was, now) -> safe(() -> onSceneChanged(was, now)));
        visibleProperty().addListener((o, a, b) -> safe(this::updateLife));
        setOnMouseEntered(e -> { hover = true; if (life != null) life.hover(true); });
        setOnMouseExited(e -> { hover = false; if (life != null) life.hover(false); });
        setOnMouseClicked(e -> { if (interactive && onActivate != null) { onActivate.run(); } });
        setOnKeyPressed(e -> { if (interactive && onActivate != null && (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE)) { onActivate.run(); e.consume(); } });
    }

    // ---------------------------------------------------------------------------------------------------------------------- API

    public void setState(MascotState s) {
        steady = s == null ? MascotState.IDLE : s;
        steadyAnimated = true;
        oneShot = null;
        safe(this::apply);
    }

    /** Estado estável SÓ com o poster (sem vida procedural nem loop). Um-tiros ({@link #play}) ainda tocam e voltam a este poster. */
    public void setStaticState(MascotState s) {
        steady = s == null ? MascotState.IDLE : s;
        steadyAnimated = false;
        oneShot = null;
        safe(this::apply);
    }

    public void transitionTo(MascotState s) {
        setState(s);
    }

    /** Toca UMA vez (ATTENTION, NOTIFICATION, TRANSITION), mantém a pose final e volta ao estado estável. */
    public void play(MascotState s) {
        if (s == null || s.loops()) {
            setState(s);
            return;
        }
        oneShot = s;
        safe(this::apply);
    }

    public void stop() {
        steadyAnimated = false;
        oneShot = null;
        safe(this::apply);
    }

    public void setMotionMode(MotionPreference mode) {
        override = mode;
        safe(this::apply);
    }

    /** Modo de contraste: HALO (padrão), TRANSPARENT ou SURFACE. Um só lugar define as cores (MascotStage). */
    public void setStageMode(MascotStage.Mode mode) {
        stageMode = mode == null ? MascotStage.DEFAULT_MODE : mode;
        stage.apply(stageMode);
        applyStageEffect();
    }

    /**
     * Contorno de silhueta: no quadro (sheets) e SÓ no corpo do rig (os olhos ficam dentro dele). O corpo é cacheado como textura (CacheHint.SPEED): respiração/inclinação só transformam a textura pronta,
     * sem recomputar o efeito a cada mudança (custo medido: ~2,5 pontos de CPU a menos no IDLE vivo).
     */
    private void applyStageEffect() {
        javafx.scene.effect.Effect e = MascotStage.effectFor(stageMode, size);
        content.setEffect(e);
        bodyView.setEffect(rigSpec != null && rigSpec.halo() != null ? null : e); // com o halo pré-calculado não há efeito no rig
        haloView.setVisible(stageMode == MascotStage.Mode.HALO);
    }

    public MascotStage.Mode stageMode() {
        return stageMode;
    }

    /** Interativo: recebe hover/clique/teclado (Enter, Espaço) e chama {@code onActivate}; decorativo (padrão): transparente ao mouse. */
    public void setInteractive(boolean on, Runnable onActivate) {
        this.interactive = on;
        this.onActivate = onActivate;
        setMouseTransparent(!on);
        setPickOnBounds(on);
        setFocusTraversable(on);
        if (!on) {
            hover = false;
            if (life != null) {
                life.hover(false);
            }
        }
    }

    /** Galeria/testes: força o foco da janela (null = seguir a janela real). */
    public void setFocusOverride(Boolean focused) {
        focusOverride = focused;
        safe(this::updateLife);
    }

    /** Galeria/testes: ponteiro sintético em coordenadas da cena (equivale a um MOUSE_MOVED real). */
    public void pointerAtScene(double sceneX, double sceneY) {
        feedPointer(sceneX, sceneY);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        gen++;
        motion.preference.removeListener(prefListener);
        stopClocks();
        stopLife();
        detachScene();
        content.setImage(null);
        rig.setVisible(false);
        closeLeases();
        showing = Showing.NONE;
    }

    // ------------------------------------------------------------------------------------------- diagnóstico (testes/galeria)

    public MascotState steadyState() { return steady; }

    public MascotState shownState() { return shown; }

    public Showing showing() { return showing; }

    public int frameIndex() { return frame; }

    public boolean assetFailed() { return manifestFailed; }

    public long firstFrameMicros() { return firstFrameMicros; }

    public MotionPreference effectiveMode() { return override != null ? override : motion.preference.get(); }

    public IdleLife.Pose pose() { return pose; }

    public boolean lifeRunning() { return lifeActive; }

    public boolean pointerTracking() { return sceneMouse != null; }

    public boolean hovered() { return hover; }

    public double size() { return size; }

    /** Prioridade de presença atual: funcional &gt; atenção/notificação &gt; ponteiro &gt; idle. */
    public MascotPriority priority() {
        if (oneShot != null) {
            return MascotPriority.of(oneShot);
        }
        MascotPriority p = MascotPriority.of(steady);
        if (p == MascotPriority.IDLE && hover) {
            return MascotPriority.POINTER;
        }
        return p;
    }

    /** O mascote aceita agora uma apresentação de prioridade {@code requested}? (um hint automático nunca interrompe estado funcional nem um-tiro) */
    public boolean accepts(MascotPriority requested) {
        return MascotPriority.mayShow(requested, priority());
    }

    /** Testes: avança o motor procedural com um relógio controlado. */
    void tickAt(long nowMs) {
        nowOverride = nowMs;
        tick();
    }

    // ------------------------------------------------------------------------------------------------------------ núcleo

    private void apply() {
        if (disposed) {
            return;
        }
        gen++;
        stopClocks();
        stopLife();
        hideRig();
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
            return;
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
        boolean wantRig = oneShot == null && target == MascotState.IDLE && steadyAnimated && mode != MotionPreference.OFF && e.rig() != null;
        if (wantRig) {
            startRig(e, dev, token, mode);
            return;
        }
        renderSheet(e, dev, token, mode);
    }

    private void renderSheet(MascotManifest.Entry e, int dev, int token, MotionPreference mode) {
        if (!animated) {
            if (sheetLease != null) {
                sheetLease.close();
                sheetLease = null;
            }
            if (oneShot != null) {
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
            return;
        }
        whenReady(sheetLease.image(), token, () -> startClock(e, token));
    }

    // ------------------------------------------------------------------------------------------------------------ rig / vida

    private void startRig(MascotManifest.Entry e, int dev, int token, MotionPreference mode) {
        closeRigLeases();
        MascotManifest.Rig r = e.rig();
        boolean halo = r.halo() != null;
        MascotAssets.Lease[] ls = halo ? new MascotAssets.Lease[] {assets.rigPart(r.body(), dev), assets.rigPart(r.eyeLeft(), dev), assets.rigPart(r.eyeRight(), dev), assets.rigPart(r.halo(), dev)}
                : new MascotAssets.Lease[] {assets.rigPart(r.body(), dev), assets.rigPart(r.eyeLeft(), dev), assets.rigPart(r.eyeRight(), dev)};
        for (MascotAssets.Lease l : ls) {
            rigLeases.add(l);
        }
        for (MascotAssets.Lease l : ls) {
            if (l.failed()) { // rig quebrado: cai no loop de sheet do IDLE, e depois no poster
                closeRigLeases();
                renderSheet(e, dev, token, mode);
                return;
            }
        }
        int[] pending = {ls.length};
        for (MascotAssets.Lease l : ls) {
            whenReady(l.image(), token, () -> {
                if (--pending[0] == 0) {
                    showRig(e, r, ls, token, mode);
                }
            });
        }
    }

    private void showRig(MascotManifest.Entry e, MascotManifest.Rig r, MascotAssets.Lease[] ls, int token, MotionPreference mode) {
        if (token != gen || disposed) {
            return;
        }
        double k = size * CONTENT / MascotManifest.CANVAS;
        double off = size * (1 - CONTENT) / 2;
        place(bodyView, ls[0].image(), r.body(), k, off);
        place(eyeLeftView, ls[1].image(), r.eyeLeft(), k, off);
        place(eyeRightView, ls[2].image(), r.eyeRight(), k, off);
        if (ls.length > 3) {
            place(haloView, ls[3].image(), r.halo(), k, off);
        }
        rigSpec = r;
        applyStageEffect();
        double px = off + r.body().pivotX() * k;
        double bottom = off + r.body().pivotY() * k;
        breathScale.setPivotX(px);
        breathScale.setPivotY(bottom);
        tilt.setPivotX(px);
        tilt.setPivotY(off + (r.body().cell().y() + r.body().cell().h() / 2.0) * k);
        swapContent(mode, () -> {
            content.setVisible(false);
            rig.setVisible(true);
            rigVisible = true;
            rigSpec = r;
            showing = Showing.RIG;
            shown = MascotState.IDLE;
            frame = -1;
        });
        life = new IdleLife(new Random(seed++), mode == MotionPreference.FULL);
        life.hover(hover);
        if (firstFrameMicros < 0) {
            firstFrameMicros = (System.nanoTime() - applyNanos) / 1000;
        }
        applied = new IdleLife.Pose(1, 1, 1, 1, 1, 1, 1); // força a primeira aplicação completa
        applyPose(IdleLife.Pose.REST);
        updateLife();
    }

    private void place(ImageView v, Image img, MascotManifest.RigPart p, double k, double off) {
        v.setImage(img);
        v.setViewport(null);
        v.setLayoutX(off + p.cell().x() * k);
        v.setLayoutY(off + p.cell().y() * k);
        v.setFitWidth(p.cell().w() * k);
        v.setFitHeight(p.cell().h() * k);
        v.setScaleX(1);
        v.setScaleY(1);
        v.setTranslateX(0);
        v.setTranslateY(0);
    }

    private void hideRig() {
        if (rigVisible) {
            rig.setVisible(false);
            content.setVisible(true);
            rigVisible = false;
        }
        life = null;
    }

    /** O motor roda só quando pode: rig na tela, modo não OFF, janela com foco/visível/não minimizada, view visível e na cena. */
    private void updateLife() {
        boolean run = rigVisible && !disposed && life != null && effectiveMode() != MotionPreference.OFF && focused() && windowShowing && !windowIconified && getScene() != null && treeVisible();
        if (run && !lifeActive) {
            lifeActive = true;
            int token = ++lifeToken;
            scheduleLife(TICK_BUSY_MS, token);
        } else if (!run && lifeActive) {
            stopTimer();
        }
        boolean track = run && life != null && life.full();
        if (track && sceneMouse == null) {
            installPointer();
        } else if (!track && sceneMouse != null) {
            removePointer();
            if (life != null) {
                life.clearPointer();
            }
        }
    }

    private void scheduleLife(long delayMs, int token) {
        try {
            lifeFuture = LIFE.schedule(() -> ui.execute(() -> onLifeTick(token)), delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            lifeActive = false;
        }
    }

    private void onLifeTick(int token) {
        if (token != lifeToken || !lifeActive || disposed) {
            return;
        }
        safe(this::tick);
        if (token == lifeToken && lifeActive) {
            long now = nowOverride >= 0 ? nowOverride : System.currentTimeMillis();
            scheduleLife(life != null && life.busy(now) ? TICK_BUSY_MS : TICK_CALM_MS, token);
        }
    }

    private void tick() {
        if (disposed || life == null || !rigVisible) {
            return;
        }
        if (!treeVisible()) {
            updateLife();
            return;
        }
        long now = nowOverride >= 0 ? nowOverride : System.currentTimeMillis();
        applyPose(life.step(now));
    }

    private void applyPose(IdleLife.Pose p) {
        pose = p;
        IdleLife.Pose a = applied;
        double sy = 1 + 0.012 * p.breath();
        double asy = 1 + 0.012 * a.breath();
        if (Math.abs(sy - asy) > BREATH_STEP) {
            breathScale.setX(1 - 0.006 * p.breath());
            breathScale.setY(sy);
        }
        if (Math.abs(p.tiltDeg() - a.tiltDeg()) > TILT_STEP) {
            tilt.setAngle(p.tiltDeg());
        }
        double dy = p.drift() * size * 0.008;
        if (Math.abs(dy - a.drift() * size * 0.008) > DRIFT_STEP) {
            rig.setTranslateY(dy);
        }
        boolean eyes = Math.abs(p.gazeX() - a.gazeX()) * size * MAX_GAZE_X > GAZE_STEP_PX || Math.abs(p.gazeY() - a.gazeY()) * size * MAX_GAZE_Y > GAZE_STEP_PX
                || Math.abs(p.blink() - a.blink()) > 0.04 || Math.abs(p.curiosity() - a.curiosity()) > 0.03;
        if (eyes) {
            double gx = p.gazeX() * size * MAX_GAZE_X;
            double gy = p.gazeY() * size * MAX_GAZE_Y;
            double squash = 1 - 0.92 * p.blink();
            double grow = 1 + 0.08 * p.curiosity();
            for (ImageView eye : new ImageView[] {eyeLeftView, eyeRightView}) {
                eye.setTranslateX(gx);
                eye.setTranslateY(gy);
                eye.setScaleX(grow);
                eye.setScaleY(Math.max(0.06, squash) * grow);
            }
        }
        // guarda só o que foi de fato aplicado (os limiares comparam com o último valor VISÍVEL)
        applied = new IdleLife.Pose(eyes ? p.gazeX() : a.gazeX(), eyes ? p.gazeY() : a.gazeY(), eyes ? p.blink() : a.blink(), Math.abs(sy - asy) > BREATH_STEP ? p.breath() : a.breath(),
                Math.abs(dy - a.drift() * size * 0.008) > DRIFT_STEP ? p.drift() : a.drift(), Math.abs(p.tiltDeg() - a.tiltDeg()) > TILT_STEP ? p.tiltDeg() : a.tiltDeg(), eyes ? p.curiosity() : a.curiosity());
    }

    private void stopTimer() {
        lifeActive = false;
        lifeToken++;
        if (lifeFuture != null) {
            lifeFuture.cancel(false);
            lifeFuture = null;
        }
    }

    private void stopLife() {
        stopTimer();
        removePointer();
    }

    private boolean focused() {
        return focusOverride != null ? focusOverride : windowFocused;
    }

    private boolean treeVisible() {
        for (Node n = this; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void installPointer() {
        Scene sc = getScene();
        if (sc == null) {
            return;
        }
        mouseScene = sc;
        sceneMouse = e -> feedPointer(e.getSceneX(), e.getSceneY());
        sc.addEventFilter(MouseEvent.MOUSE_MOVED, sceneMouse);
        sc.addEventFilter(MouseEvent.MOUSE_EXITED, ex -> { if (life != null) { life.clearPointer(); } });
    }

    private void removePointer() {
        if (mouseScene != null && sceneMouse != null) {
            mouseScene.removeEventFilter(MouseEvent.MOUSE_MOVED, sceneMouse);
        }
        sceneMouse = null;
        mouseScene = null;
    }

    private void feedPointer(double sx, double sy) {
        if (life == null || !rigVisible || !life.full() || oneShot != null) {
            return;
        }
        Point2D c = localToScene(size / 2, size / 2);
        if (c != null) {
            life.pointer(sx, sy, c.getX(), c.getY(), size, nowOverride >= 0 ? nowOverride : System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------------------------------- cena / janela

    private void onSceneChanged(Scene was, Scene now) {
        detachScene();
        if (now == null) {
            stopLife();
            return;
        }
        Window w = now.getWindow();
        if (w != null) {
            attachWindow(w);
        }
        ChangeListener<Window> wl = (o, a, b) -> safe(() -> {
            detachWindowOnly();
            if (b != null) {
                attachWindow(b);
            }
            updateLife();
        });
        now.windowProperty().addListener(wl);
        windowDetachers.add(() -> now.windowProperty().removeListener(wl));
        updateLife();
    }

    private void attachWindow(Window w) {
        windowFocused = w.isFocused();
        windowShowing = w.isShowing();
        ChangeListener<Boolean> f = (o, a, b) -> { windowFocused = b; safe(this::updateLife); };
        ChangeListener<Boolean> s = (o, a, b) -> { windowShowing = b; safe(this::updateLife); };
        w.focusedProperty().addListener(f);
        w.showingProperty().addListener(s);
        windowDetachers.add(() -> { w.focusedProperty().removeListener(f); w.showingProperty().removeListener(s); });
        if (w instanceof Stage st) {
            windowIconified = st.isIconified();
            ChangeListener<Boolean> i = (o, a, b) -> { windowIconified = b; safe(this::updateLife); };
            st.iconifiedProperty().addListener(i);
            windowDetachers.add(() -> st.iconifiedProperty().removeListener(i));
        }
    }

    private void detachWindowOnly() {
        windowDetachers.forEach(Runnable::run);
        windowDetachers.clear();
    }

    private void detachScene() {
        detachWindowOnly();
    }

    // ------------------------------------------------------------------------------------------------ poster / sheets

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
            if (rigVisible) {
                return; // o rig já está na tela
            }
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
            if (override == MotionPreference.FULL && !motion.full()) {
                c.setCycleCount(Animation.INDEFINITE);
                c.play();
                a = c;
            } else {
                a = motion.loop(this, () -> c);
            }
            if (a == null) {
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

    private void swapContent(MotionPreference mode, Runnable change) {
        if (swap != null) {
            swap.stop();
            swap = null;
        }
        if (mode == MotionPreference.OFF || content.getImage() == null && !rigVisible) {
            change.run();
            content.setOpacity(1);
            rig.setOpacity(1);
            return;
        }
        change.run();
        Node n = rigVisible ? rig : content;
        n.setOpacity(0);
        FadeTransition in = new FadeTransition(mode == MotionPreference.FULL ? SWAP : Duration.millis(60), n);
        in.setFromValue(0);
        in.setToValue(1);
        in.setInterpolator(Interpolator.EASE_OUT);
        in.setOnFinished(ev -> n.setOpacity(1));
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

    private void closeRigLeases() {
        rigLeases.forEach(MascotAssets.Lease::close);
        rigLeases.clear();
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
        closeRigLeases();
    }

    private int devicePx() {
        return (int) Math.round(size * CONTENT * Math.max(1.0, outputScale.getAsDouble()));
    }

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
            double w = Math.min(e.cellPxW() * fx, img.getWidth() - col * e.cellPxW() * fx);
            double h = Math.min(e.cellPxH() * fy, img.getHeight() - row * e.cellPxH() * fy);
            content.setViewport(new Rectangle2D(col * e.cellPxW() * fx, row * e.cellPxH() * fy, Math.max(1, w), Math.max(1, h)));
        }
    }
}
