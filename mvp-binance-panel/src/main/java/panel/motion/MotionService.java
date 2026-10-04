package panel.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Ponto único de animação. Respeita FULL/REDUCED/OFF, cancela a animação anterior do mesmo nó
 * (sem acúmulo em hover repetido) e pausa loops quando a janela some.
 */
public class MotionService {
    private static final String KEY = "motion.anim";

    public final ReferenceMotion reference = new ReferenceMotion(this);

    public final ObjectProperty<MotionPreference> preference = new SimpleObjectProperty<>(MotionPreference.FULL);
    public final BooleanProperty animatedIcons = new SimpleBooleanProperty(true);

    private final List<Animation> loops = new ArrayList<>();
    private final java.util.Map<Animation, Node> owners = new java.util.IdentityHashMap<>();
    private final java.util.Map<Animation, javafx.beans.value.ChangeListener<javafx.scene.Scene>> loopListeners = new java.util.IdentityHashMap<>();
    private boolean active = true;

    public MotionService() {
        preference.addListener((o, a, b) -> {
            if (b != MotionPreference.FULL) {
                stopLoops();
            }
        });
    }

    public boolean full() {
        return preference.get() == MotionPreference.FULL;
    }

    public boolean off() {
        return preference.get() == MotionPreference.OFF;
    }

    public boolean iconsAnimated() {
        return animatedIcons.get() && !off();
    }

    public Duration scale(Duration d) {
        return switch (preference.get()) {
            case FULL -> d;
            case REDUCED -> d.greaterThan(MotionTokens.REDUCED_MAX) ? MotionTokens.REDUCED_MAX : d;
            case OFF -> Duration.ZERO;
        };
    }

    /** Toca a animação como "a" do nó: a anterior é interrompida. */
    public <T extends Animation> T play(Node owner, T a) {
        Object prev = owner.getProperties().get(KEY);
        if (prev instanceof Animation p) {
            p.stop();
        }
        owner.getProperties().put(KEY, a);
        a.setOnFinished(e -> owner.getProperties().remove(KEY, a));
        a.play();
        return a;
    }

    public void cancel(Node n) {
        if (n.getProperties().remove(KEY) instanceof Animation p) {
            p.stop();
        }
    }

    public void reset(Node n) {
        cancel(n);
        n.setOpacity(1);
        n.setTranslateX(0);
        n.setTranslateY(0);
        n.setScaleX(1);
        n.setScaleY(1);
    }

    public void fadeIn(Node n, Duration d) {
        fadeSlideIn(n, 0, 0, d);
    }

    /** Entrada: fade + deslocamento (só em FULL). */
    public void fadeSlideIn(Node n, double dx, double dy, Duration d) {
        if (off()) {
            reset(n);
            return;
        }
        boolean move = full();
        n.setOpacity(0);
        n.setTranslateX(move ? dx : 0);
        n.setTranslateY(move ? dy : 0);
        Duration t = scale(d);
        Timeline tl = new Timeline(new KeyFrame(t,
                new KeyValue(n.opacityProperty(), 1, MotionTokens.EASE_OUT),
                new KeyValue(n.translateXProperty(), 0, MotionTokens.EASE_OUT),
                new KeyValue(n.translateYProperty(), 0, MotionTokens.EASE_OUT)));
        play(n, tl);
    }

    public void fadeOut(Node n, Duration d, Runnable after) {
        if (off()) {
            reset(n);
            after.run();
            return;
        }
        FadeTransition f = new FadeTransition(scale(d), n);
        f.setToValue(0);
        f.setInterpolator(MotionTokens.EASE_IN);
        f.setOnFinished(e -> {
            n.getProperties().remove(KEY, f);
            after.run();
        });
        Object prev = n.getProperties().get(KEY);
        if (prev instanceof Animation p) {
            p.stop();
        }
        n.getProperties().put(KEY, f);
        f.play();
    }

    /** Popup/menu: fade + escala discreta a partir do pivô (em OFF/REDUCED só fade). */
    public void popIn(Node n, double fromScale, Duration d) {
        if (off()) {
            reset(n);
            return;
        }
        n.setOpacity(0);
        double s = full() ? fromScale : 1;
        n.setScaleX(s);
        n.setScaleY(s);
        play(n, new Timeline(new KeyFrame(scale(d),
                new KeyValue(n.opacityProperty(), 1, MotionTokens.EASE_OUT),
                new KeyValue(n.scaleXProperty(), 1, MotionTokens.EASE_OUT),
                new KeyValue(n.scaleYProperty(), 1, MotionTokens.EASE_OUT))));
    }

    /** Hover: desloca 1-2 px (somente FULL). */
    public void shiftTo(Node n, double x, double y, Duration d) {
        if (!full()) {
            n.setTranslateX(0);
            n.setTranslateY(0);
            return;
        }
        play(n, new Timeline(new KeyFrame(d,
                new KeyValue(n.translateXProperty(), x, MotionTokens.EASE_OUT),
                new KeyValue(n.translateYProperty(), y, MotionTokens.EASE_OUT))));
    }

    public void fadeTo(Node n, double opacity, Duration d) {
        if (off()) {
            n.setOpacity(opacity);
            return;
        }
        play(n, new Timeline(new KeyFrame(scale(d), new KeyValue(n.opacityProperty(), opacity, MotionTokens.EASE_OUT))));
    }

    public void slideTo(Node n, double x, Duration d) {
        if (off()) {
            n.setTranslateX(x);
            return;
        }
        play(n, new Timeline(new KeyFrame(scale(d), new KeyValue(n.translateXProperty(), x, MotionTokens.EASE_IN_OUT))));
    }

    public void slideYTo(Node n, double y, Duration d) {
        if (off()) {
            n.setTranslateY(y);
            return;
        }
        play(n, new Timeline(new KeyFrame(scale(d), new KeyValue(n.translateYProperty(), y, MotionTokens.EASE_IN_OUT))));
    }

    /** Uma volta; ignora se já estiver girando. */
    public void spinOnce(Node n) {
        if (off() || n.getProperties().get(KEY) instanceof Animation a && a.getStatus() == Animation.Status.RUNNING) {
            return;
        }
        n.setRotate(0);
        RotateTransition r = new RotateTransition(scale(Duration.millis(600)), n);
        r.setByAngle(360);
        r.setInterpolator(MotionTokens.EASE_IN_OUT);
        r.setOnFinished(e -> n.setRotate(0));
        play(n, r);
    }

    /** Tremor horizontal pequeno (erro de formulário). Só em FULL. */
    public void shake(Node n, double amplitude) {
        if (!full()) {
            return;
        }
        Timeline t = new Timeline();
        double[] xs = {-amplitude, amplitude, -amplitude / 2, amplitude / 2, 0};
        for (int i = 0; i < xs.length; i++) {
            t.getKeyFrames().add(new KeyFrame(Duration.millis(40 * (i + 1)), new KeyValue(n.translateXProperty(), xs[i], Interpolator.LINEAR)));
        }
        play(n, t);
    }

    /** Destaque curto de valor (muda de cor/brilho e volta). Sem loop. */
    public void flash(Node n, Color color) {
        if (off()) {
            return;
        }
        DropShadow glow = new DropShadow(10, color);
        glow.setSpread(0.35);
        n.setEffect(glow);
        Timeline t = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 10)),
                new KeyFrame(scale(Duration.millis(380)), new KeyValue(glow.radiusProperty(), 0, MotionTokens.EASE_OUT), new KeyValue(glow.colorProperty(), Color.TRANSPARENT)));
        t.setOnFinished(e -> n.setEffect(null));
        play(n, t);
    }

    public void pop(Node n) {
        if (off() || !full() && !reducedPopAllowed()) {
            return;
        }
        ScaleTransition s = new ScaleTransition(scale(MotionTokens.EMPHASIS), n);
        s.setFromX(0.7);
        s.setFromY(0.7);
        s.setToX(1);
        s.setToY(1);
        s.setInterpolator(MotionTokens.EASE_OUT);
        play(n, new ParallelTransition(s));
    }

    private boolean reducedPopAllowed() {
        return false;
    }

    public void translate(Node n, double x, double y, Duration d) {
        TranslateTransition t = new TranslateTransition(scale(d), n);
        t.setToX(x);
        t.setToY(y);
        t.setInterpolator(MotionTokens.EASE_IN_OUT);
        play(n, t);
    }

    // ---- loops contínuos (limitados e pausáveis) --------------------------------

    static boolean treeVisible(Node n) {
        if (n.getScene() == null) {
            return false;
        }
        for (Node p = n; p != null; p = p.getParent()) {
            if (!p.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Loop decorativo/estado ativo. Só existe em FULL, só roda enquanto o nó está visível e é removido ao sair da cena. */
    public Animation loop(Node owner, Supplier<Animation> factory) {
        if (!full()) {
            return null;
        }
        Animation a = factory.get();
        a.setCycleCount(Animation.INDEFINITE);
        synchronized (loops) {
            loops.add(a);
            owners.put(a, owner);
        }
        javafx.beans.value.ChangeListener<javafx.scene.Scene> listener = new javafx.beans.value.ChangeListener<>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.Scene> o,
                    javafx.scene.Scene was, javafx.scene.Scene now) {
                if (now == null) {
                    removeLoop(a);
                    owner.sceneProperty().removeListener(this);
                } else refreshLoops();
            }
        };
        loopListeners.put(a, listener);
        owner.sceneProperty().addListener(listener);
        refreshLoops();
        return a;
    }

    /** Roda só os loops cujo dono está visível na árvore; pausa os demais (páginas ocultas, janela minimizada). */
    public void refreshLoops() {
        synchronized (loops) {
            for (Animation a : loops) {
                Node owner = owners.get(a);
                boolean run = active && owner != null && treeVisible(owner);
                if (run && a.getStatus() != Animation.Status.RUNNING) {
                    a.play();
                } else if (!run && a.getStatus() == Animation.Status.RUNNING) {
                    a.pause();
                }
            }
        }
    }

    public void removeLoop(Animation animation) {
        animation.stop();
        synchronized (loops) {
            Node owner = owners.remove(animation);
            var listener = loopListeners.remove(animation);
            if (owner != null && listener != null) owner.sceneProperty().removeListener(listener);
            loops.remove(animation);
        }
    }

    public void stopLoops() {
        synchronized (loops) {
            new ArrayList<>(loops).forEach(this::removeLoop);
        }
    }

    /** Janela oculta/minimizada: pausa; visível: retoma. */
    public void setActive(boolean on) {
        active = on;
        refreshLoops();
    }

    /** Loops realmente rodando (visíveis). */
    public int runningLoops() {
        synchronized (loops) {
            return (int) loops.stream().filter(a -> a.getStatus() == Animation.Status.RUNNING).count();
        }
    }

    public int loopCount() {
        synchronized (loops) {
            return loops.size();
        }
    }
}
