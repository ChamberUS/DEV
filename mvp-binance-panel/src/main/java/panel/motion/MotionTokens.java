package panel.motion;

import javafx.animation.Interpolator;
import javafx.util.Duration;

/** Durações e curvas únicas do app. Nenhuma tela deve inventar tempos próprios. */
public final class MotionTokens {
    public static final Duration MICRO = Duration.millis(110);
    public static final Duration FAST = Duration.millis(160);
    public static final Duration STANDARD = Duration.millis(220);
    public static final Duration EMPHASIS = Duration.millis(320);
    public static final Duration SLOW = Duration.millis(500);

    public static final Interpolator EASE_OUT = Interpolator.SPLINE(0, 0, 0.2, 1);
    public static final Interpolator EASE_IN = Interpolator.SPLINE(0.4, 0, 1, 1);
    public static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.4, 0, 0.2, 1);

    /** Teto de duração em REDUCED. */
    public static final Duration REDUCED_MAX = Duration.millis(120);

    private MotionTokens() {
    }
}
