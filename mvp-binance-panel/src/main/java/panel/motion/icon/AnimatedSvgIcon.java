package panel.motion.icon;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.util.Duration;
import panel.motion.MotionService;
import panel.motion.MotionTokens;

/** Fallback nativo: SVG com uma animação curta por tipo. Também serve de reserva quando o Lottie falha. */
public class AnimatedSvgIcon extends SvgIcon {
    private final Kind kind;
    private final MotionService motion;
    private Animation loopAnim;

    public AnimatedSvgIcon(String svg, double size, String tone, Kind kind, MotionService motion) {
        super(svg, size, tone);
        this.kind = kind;
        this.motion = motion;
    }

    @Override
    public void play() {
        if (!motion.iconsAnimated() || kind == Kind.NONE) {
            showStatic();
            return;
        }
        switch (kind) {
            case SPIN -> motion.spinOnce(box);
            case POP -> motion.pop(box);
            case SHAKE -> motion.shake(box, 3);
            case RING -> ring();
            case DRAW -> draw();
            default -> { }
        }
    }

    @Override
    public void loop() {
        if (!motion.iconsAnimated() || !motion.full()) {
            showStatic();
            return;
        }
        stop();
        loopAnim = motion.loop(box, () -> new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(box.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(700), new KeyValue(box.opacityProperty(), 0.45, MotionTokens.EASE_IN_OUT)),
                new KeyFrame(Duration.millis(1400), new KeyValue(box.opacityProperty(), 1, MotionTokens.EASE_IN_OUT))));
    }

    @Override
    public void stop() {
        if (loopAnim != null) {
            motion.removeLoop(loopAnim);
            loopAnim = null;
        }
        showStatic();
    }

    @Override
    public void showStatic() {
        motion.reset(box);
        path.setStrokeDashOffset(0);
        path.getStrokeDashArray().clear();
    }

    private void ring() {
        if (!motion.full()) {
            motion.pop(box);
            return;
        }
        Timeline t = new Timeline();
        double[] a = {-14, 12, -8, 6, 0};
        for (int i = 0; i < a.length; i++) {
            t.getKeyFrames().add(new KeyFrame(Duration.millis(50 * (i + 1)), new KeyValue(box.rotateProperty(), a[i])));
        }
        motion.play(box, t);
    }

    private void draw() {
        if (!motion.full()) {
            return;
        }
        path.getStrokeDashArray().setAll(60.0, 60.0);
        path.setStrokeDashOffset(60);
        motion.play(box, new Timeline(new KeyFrame(motion.scale(Duration.millis(380)),
                new KeyValue(path.strokeDashOffsetProperty(), 0, MotionTokens.EASE_OUT))));
    }

    @Override
    public String renderer() {
        return "svg-animated";
    }
}
