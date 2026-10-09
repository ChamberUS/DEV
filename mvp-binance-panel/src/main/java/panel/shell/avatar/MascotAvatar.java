package panel.shell.avatar;

import java.util.function.LongSupplier;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.Window;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * The persistent account avatar (B03): the mascot drawn with JavaFX vector primitives inside the EXISTING header button, which stays the
 * semantic control (focus, Enter/Space, accessible name, menu). This class owns only the artwork and its motion.
 *
 * <ul>
 *   <li><b>Long-lived:</b> created once with the shell's top bar; never re-created per page, never re-parented. The menu is opened by the
 *       button's own action — the reaction is a separate, additional listener and cannot delay or replace it.</li>
 *   <li><b>Pointer:</b> follows scene mouse events only (no global cursor, no OS permission). Events outside the scene bounds are ignored and
 *       leaving the window returns the eyes to idle. Nothing is allocated per mouse move.</li>
 *   <li><b>One AnimationTimer</b>, started only while the window is focused, not minimised and the avatar is in a visible scene, and only while
 *       something needs frames (FULL idle drift at ~30 fps, busy states at ~60 fps, REDUCED/OFF at ~10 fps while an operation/hold is timing).
 *       It stops entirely otherwise.</li>
 *   <li><b>Operations:</b> the {@link #operations()} registry is the only source of loading/error looks. Nothing here simulates work.</li>
 *   <li><b>Cleanup:</b> {@link #dispose()} removes every listener/filter/handler, stops the timer, invalidates outstanding operation tokens and
 *       detaches the graphic.</li>
 * </ul>
 */
public final class MascotAvatar {
    private static final double BOX = 100;
    private static final double ERROR_EYE_DROP = 12;
    private static final long FULL_IDLE_FRAME_MS = 33;
    private static final long REDUCED_FRAME_MS = 100;

    private final Button button;
    private MotionService motion; // not final: read by the listener lambdas below, assigned in the constructor
    private final MascotTokens tk;
    private LongSupplier clockMs;
    private AvatarMotion engine;
    private final OperationRegistry registry;
    private final boolean useTimer;
    private final AnimationTimer timer;

    // artwork (design units; the unit group is scaled to the header size)
    private final Pane holder = new Pane();
    private final Group unit = new Group();
    private final Group dynamic = new Group();
    private final Group eyes = new Group();
    private final Rectangle eyeL;
    private final Rectangle eyeR;
    private final Group bang = new Group();
    private final Group rings = new Group();
    private final Ellipse[] ringShapes;

    // wiring (all removed in dispose())
    private final ChangeListener<Boolean> hoverL = (o, a, b) -> {
        engine.setHover(b);
        wake();
    };
    private final ChangeListener<Boolean> pressedL = (o, a, b) -> {
        engine.setPressed(b);
        wake();
    };
    private final EventHandler<ActionEvent> actionH = e -> {
        engine.react(clockMs.getAsLong()); // additional to the menu handler; never replaces or delays it
        wake();
    };
    private final ChangeListener<Scene> sceneL = (o, a, b) -> {
        detachScene();
        if (b != null) {
            attachScene(b);
        }
        evaluate();
    };
    private final ChangeListener<Window> windowL = (o, a, b) -> {
        detachWindow();
        if (b != null) {
            attachWindow(b);
        }
        evaluate();
    };
    private final ChangeListener<Boolean> windowStateL = (o, a, b) -> {
        if (!windowActive()) {
            engine.clearPointer();
        }
        evaluate();
    };
    private final ChangeListener<MotionPreference> motionL = (o, a, b) -> {
        engine.setMode(modeOf(b));
        if (b != MotionPreference.FULL) {
            engine.clearPointer();
        }
        frame(clockMs.getAsLong());
        evaluate();
    };
    private final ChangeListener<Boolean> menuL = (o, a, b) -> {
        engine.setMenuOpen(b);
        wake();
    };
    private final EventHandler<MouseEvent> moveH = this::onMouse;
    private final EventHandler<MouseEvent> exitH = e -> {
        if (this.attachedScene != null && e.getTarget() == this.attachedScene.getRoot()) {
            engine.clearPointer();
            wake();
        }
    };
    private final ChangeListener<Boolean> visibleL = (o, a, b) -> evaluate();

    private Scene attachedScene;
    private Window attachedWindow;
    private ReadOnlyBooleanProperty menuOpen;
    private int sceneFilters;
    private boolean disposed;
    private String userName = "";
    private OperationRegistry.Presentation shownPresentation = OperationRegistry.Presentation.NONE;
    private long lastFrameMs = Long.MIN_VALUE;
    private double ptrX = Double.NaN;
    private double ptrY = Double.NaN;
    private AvatarMotion.Pose applied = AvatarMotion.Pose.NEUTRAL;

    public MascotAvatar(Button button, MotionService motion) {
        this(button, motion, () -> System.nanoTime() / 1_000_000L, true);
    }

    /** @param useTimer false in unit tests that drive {@link #frame(long)} by hand */
    MascotAvatar(Button button, MotionService motion, LongSupplier clockMs, boolean useTimer) {
        this.button = button;
        this.motion = motion;
        this.tk = MascotTokens.shared();
        this.clockMs = clockMs;
        this.useTimer = useTimer;
        this.engine = new AvatarMotion(tk);
        this.engine.setMode(modeOf(motion.preference.get()));
        this.registry = new OperationRegistry(clockMs, tk);
        this.registry.setOnChange(this::wakeFromAnyThread);
        this.timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                onFrame();
            }
        };

        // ---- artwork
        SVGPath body = new SVGPath();
        body.setContent(AvatarPaths.BODY);
        body.getStyleClass().add("byx-mascot-body");
        eyeL = eye(AvatarPaths.EYE_LEFT);
        eyeR = eye(AvatarPaths.EYE_RIGHT);
        eyes.getChildren().addAll(wrapEye(eyeL, AvatarPaths.EYE_LEFT), wrapEye(eyeR, AvatarPaths.EYE_RIGHT));
        // "!" sits in the gap between the eyes (x 52..63.3) and above them: it never overlaps the eyes (surprise) nor the squinted dashes (error)
        Rectangle bar = new Rectangle(55.1, 10, 5.1, 17);
        bar.setArcWidth(5.1);
        bar.setArcHeight(5.1);
        bar.getStyleClass().add("byx-mascot-eye");
        Circle dot = new Circle(57.65, 33, 3.0);
        dot.getStyleClass().add("byx-mascot-eye");
        bang.getChildren().addAll(bar, dot);
        bang.setOpacity(0);
        dynamic.getChildren().addAll(body, eyes, bang);
        Group figure = new Group(dynamic);
        figure.getTransforms().add(new Scale(tk.bodyScale(), tk.bodyScale(), 0, 0));
        figure.setTranslateX(AvatarPaths.BODY_TRANSLATE);
        figure.setTranslateY(AvatarPaths.BODY_TRANSLATE);
        ringShapes = new Ellipse[tk.ringCount()];
        for (int i = 0; i < ringShapes.length; i++) {
            Ellipse e = new Ellipse(BOX / 2, BOX / 2, 44, 17);
            e.getStyleClass().addAll("byx-mascot-ring", "byx-mascot-ring-" + (i + 1));
            e.setFill(null);
            e.setRotate(i * 180.0 / ringShapes.length);
            ringShapes[i] = e;
            rings.getChildren().add(e);
        }
        rings.setVisible(false);
        unit.getChildren().addAll(figure, rings);
        double s = tk.sizeHeader() / BOX;
        unit.getTransforms().add(new Scale(s, s, 0, 0));
        holder.getChildren().add(unit);
        holder.setMinSize(tk.sizeHeader(), tk.sizeHeader());
        holder.setPrefSize(tk.sizeHeader(), tk.sizeHeader());
        holder.setMaxSize(tk.sizeHeader(), tk.sizeHeader());
        holder.setClip(new Circle(tk.sizeHeader() / 2.0, tk.sizeHeader() / 2.0, tk.sizeHeader() / 2.0 - 1));
        holder.setMouseTransparent(true); // the button owns every pointer event; the art never steals input or changes the hit target
        holder.setFocusTraversable(false);

        button.setText("");
        button.setGraphic(holder);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setAccessibleRole(AccessibleRole.MENU_BUTTON);
        button.hoverProperty().addListener(hoverL);
        button.pressedProperty().addListener(pressedL);
        button.addEventHandler(ActionEvent.ACTION, actionH);
        button.sceneProperty().addListener(sceneL);
        button.visibleProperty().addListener(visibleL);
        motion.preference.addListener(motionL);
        updateLabel();
        if (button.getScene() != null) {
            attachScene(button.getScene());
        }
        frame(clockMs.getAsLong());
        evaluate();
    }

    private static AvatarMotion.Mode modeOf(MotionPreference p) {
        return switch (p) {
            case FULL -> AvatarMotion.Mode.FULL;
            case REDUCED -> AvatarMotion.Mode.REDUCED;
            case OFF -> AvatarMotion.Mode.OFF;
        };
    }

    private static Rectangle eye(AvatarPaths.Eye e) {
        Rectangle r = new Rectangle(e.x(), e.y(), e.w(), e.h());
        r.setArcWidth(e.r() * 2);
        r.setArcHeight(e.r() * 2);
        r.getStyleClass().add("byx-mascot-eye");
        return r;
    }

    private static Group wrapEye(Rectangle r, AvatarPaths.Eye e) {
        Group g = new Group(r);
        g.setRotate(e.rotationDeg());
        return g;
    }

    // ------------------------------------------------------------------ public

    /** The registry real operations report to (dependency D-13). */
    public Operations operations() {
        return registry;
    }

    OperationRegistry registry() {
        return registry;
    }

    AvatarMotion engine() {
        return engine;
    }

    /** Accessible name: "Account menu, {name}" plus the operation state while one is shown. */
    public void setUserName(String name) {
        userName = name == null ? "" : name;
        updateLabel();
    }

    /** Menu-open state comes from the existing user menu (never a second menu): the eyes look at it and aria state follows. */
    public void bindMenuOpen(ReadOnlyBooleanProperty open) {
        if (menuOpen != null) {
            menuOpen.removeListener(menuL);
        }
        menuOpen = open;
        engine.setMenuOpen(open != null && open.get());
        if (open != null) {
            open.addListener(menuL);
        }
        wake();
    }

    public Button button() {
        return button;
    }

    public Node art() {
        return holder;
    }

    public OperationRegistry.Presentation presentation() {
        return shownPresentation;
    }

    public AvatarMotion.Pose pose() {
        return applied;
    }

    public boolean timerRunning() {
        return timerOn;
    }

    /** Test/diagnostic: number of scene event filters this avatar currently has registered (must be 0 or 2, never grows). */
    public int sceneFilterCount() {
        return sceneFilters;
    }

    public boolean disposed() {
        return disposed;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        stopTimer();
        detachScene();
        detachWindow();
        button.hoverProperty().removeListener(hoverL);
        button.pressedProperty().removeListener(pressedL);
        button.removeEventHandler(ActionEvent.ACTION, actionH);
        button.sceneProperty().removeListener(sceneL);
        button.visibleProperty().removeListener(visibleL);
        motion.preference.removeListener(motionL);
        if (menuOpen != null) {
            menuOpen.removeListener(menuL);
            menuOpen = null;
        }
        registry.setOnChange(null);
        registry.clearAll(); // outstanding tokens become inert: a late callback cannot affect a later session
        button.setGraphic(null);
    }

    // ------------------------------------------------------------------ scene / window wiring

    private void attachScene(Scene s) {
        attachedScene = s;
        s.addEventFilter(MouseEvent.MOUSE_MOVED, moveH);
        s.addEventFilter(MouseEvent.MOUSE_DRAGGED, moveH);
        s.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, exitH);
        sceneFilters += 3;
        s.windowProperty().addListener(windowL);
        if (s.getWindow() != null) {
            attachWindow(s.getWindow());
        }
    }

    private void detachScene() {
        if (attachedScene != null) {
            attachedScene.removeEventFilter(MouseEvent.MOUSE_MOVED, moveH);
            attachedScene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, moveH);
            attachedScene.removeEventFilter(MouseEvent.MOUSE_EXITED_TARGET, exitH);
            sceneFilters -= 3;
            attachedScene.windowProperty().removeListener(windowL);
            attachedScene = null;
        }
        detachWindow();
        engine.clearPointer();
        ptrX = Double.NaN;
        ptrY = Double.NaN;
    }

    private void attachWindow(Window w) {
        attachedWindow = w;
        w.focusedProperty().addListener(windowStateL);
        w.showingProperty().addListener(windowStateL);
        if (w instanceof Stage st) {
            st.iconifiedProperty().addListener(windowStateL);
        }
    }

    private void detachWindow() {
        if (attachedWindow != null) {
            attachedWindow.focusedProperty().removeListener(windowStateL);
            attachedWindow.showingProperty().removeListener(windowStateL);
            if (attachedWindow instanceof Stage st) {
                st.iconifiedProperty().removeListener(windowStateL);
            }
            attachedWindow = null;
        }
    }

    /** Test seam: lets a test decide "window focused and visible" without depending on the desktop's focus. */
    Boolean windowActiveOverride;

    void windowActiveForTest(Boolean active) {
        windowActiveOverride = active;
        evaluate();
    }

    private boolean windowActive() {
        if (windowActiveOverride != null) {
            return windowActiveOverride;
        }
        Window w = attachedWindow;
        if (w == null || !w.isShowing() || !w.isFocused()) {
            return false;
        }
        return !(w instanceof Stage st) || !st.isIconified();
    }

    /** Hot path: no allocation, no scene-graph work; stores the latest scene position and wakes the timer. */
    private void onMouse(MouseEvent e) {
        if (motion.preference.get() != MotionPreference.FULL || attachedScene == null) {
            return;
        }
        double x = e.getSceneX();
        double y = e.getSceneY();
        if (x < 0 || y < 0 || x > attachedScene.getWidth() || y > attachedScene.getHeight()) {
            ptrX = Double.NaN; // outside the scene: ignored, eyes go back to idle
            ptrY = Double.NaN;
            engine.clearPointer();
        } else {
            ptrX = x;
            ptrY = y;
        }
        wake();
    }

    // ------------------------------------------------------------------ timer

    private boolean timerOn;

    private boolean shouldRun() {
        if (disposed || attachedScene == null || !button.isVisible() || !windowActive()) {
            return false;
        }
        return engine.continuous() || engine.animating() || registry.timeSensitive() || !Double.isNaN(ptrX);
    }

    private void evaluate() {
        if (disposed) {
            return;
        }
        if (shouldRun()) {
            if (!timerOn && useTimer) {
                timerOn = true;
                lastFrameMs = Long.MIN_VALUE;
                timer.start();
            }
        } else {
            stopTimer();
        }
    }

    private void stopTimer() {
        if (timerOn) {
            timerOn = false;
            timer.stop();
        }
    }

    private void wake() {
        if (!disposed) {
            evaluate();
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean wakePending = new java.util.concurrent.atomic.AtomicBoolean();

    /** Registry callbacks may come from any thread; coalesced into at most one pending runLater. */
    private void wakeFromAnyThread() {
        if (javafx.application.Platform.isFxApplicationThread()) {
            wake();
        } else if (wakePending.compareAndSet(false, true)) {
            javafx.application.Platform.runLater(() -> {
                wakePending.set(false);
                wake();
            });
        }
    }

    private void onFrame() {
        long now = clockMs.getAsLong();
        boolean busy = engine.animating() || registry.timeSensitive() || !Double.isNaN(ptrX);
        long gap = engine.mode() == AvatarMotion.Mode.FULL ? (busy ? 0 : FULL_IDLE_FRAME_MS) : REDUCED_FRAME_MS;
        if (lastFrameMs != Long.MIN_VALUE && now - lastFrameMs < gap) {
            return;
        }
        lastFrameMs = now;
        frame(now);
        if (!shouldRun()) {
            stopTimer();
        }
    }

    // ------------------------------------------------------------------ frame

    /** One step of motion at {@code nowMs}; package-private so tests can drive time deterministically. */
    void frame(long nowMs) {
        if (disposed) {
            return;
        }
        OperationRegistry.Presentation p = registry.presentation();
        if (engine.mode() == AvatarMotion.Mode.FULL && !Double.isNaN(ptrX) && attachedScene != null) {
            Point2D c = button.localToScene(button.getWidth() / 2, button.getHeight() / 2);
            double dx = ptrX - c.getX();
            double dy = ptrY - c.getY();
            double d = Math.hypot(dx, dy);
            if (d < 1e-3) {
                engine.setPointer(0, 0);
            } else {
                double k = Math.min(1, 1.2 * d / (d + 90));
                engine.setPointer(dx / d * k, dy / d * k);
            }
        }
        AvatarMotion.Pose pose = engine.tick(nowMs, p);
        apply(pose);
        if (p != shownPresentation) {
            shownPresentation = p;
            updateLabel();
        }
    }

    private void apply(AvatarMotion.Pose pose) {
        applied = pose;
        eyes.setTranslateX(pose.eyeX());
        eyes.setTranslateY(pose.eyeY() + ERROR_EYE_DROP * pose.errorMix()); // squinted eyes drop below the "!" in the error look
        eyeL.setScaleX(pose.eyeScaleX());
        eyeR.setScaleX(pose.eyeScaleX());
        eyeL.setScaleY(pose.eyeScaleY());
        eyeR.setScaleY(pose.eyeScaleY());
        dynamic.setTranslateX(pose.leanX());
        dynamic.setTranslateY(pose.leanY());
        dynamic.setScaleX(pose.bodyScaleX());
        dynamic.setScaleY(pose.bodyScaleY());
        double bangOpacity = Math.max(pose.bang(), pose.errorMix());
        bang.setOpacity(bangOpacity);
        boolean showRings = pose.ringAlpha() > 0.01;
        if (rings.isVisible() != showRings) {
            rings.setVisible(showRings);
        }
        if (showRings) {
            rings.setOpacity(Math.min(1, pose.ringAlpha()));
            for (int i = 0; i < ringShapes.length; i++) {
                double dir = i % 2 == 0 ? 1 : -1;
                ringShapes[i].setRotate(i * 180.0 / ringShapes.length + dir * pose.ringAngle());
                double t = i % 2 == 0 ? pose.ringTilt() : 1 - pose.ringTilt();
                ringShapes[i].setScaleY(0.75 + 0.25 * t);
            }
        }
    }

    private void updateLabel() {
        String base = userName.isEmpty() ? "Account menu" : "Account menu, " + userName;
        String state = switch (shownPresentation) {
            case LOADING -> ", loading";
            case ERROR -> ", an operation did not finish";
            case NONE -> "";
        };
        button.setAccessibleText(base + state);
    }
}
