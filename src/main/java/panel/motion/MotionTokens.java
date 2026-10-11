package panel.motion;

import javafx.animation.Interpolator;
import javafx.util.Duration;

/** Durações e curvas únicas do app. Nenhuma tela deve inventar tempos próprios. */
public final class MotionTokens {
    public static final Duration MICRO = Duration.millis(120);
    public static final Duration FAST = Duration.millis(200);
    public static final Duration STANDARD = Duration.millis(200);
    public static final Duration EMPHASIS = Duration.millis(500);
    public static final Duration SLOW = Duration.millis(500);

    public static final Duration CONTROL = Duration.millis(200);
    public static final Duration TOOLTIP = Duration.millis(120);
    public static final Duration CARD_ENTRY = Duration.millis(500);
    public static final Duration LOGIN_ENTRY = Duration.seconds(1);
    public static final Duration LIVE = Duration.millis(2600);
    public static final Duration PIPELINE = Duration.millis(2400);
    public static final Duration SHIMMER = Duration.millis(2200);
    public static final Interpolator CSS_EASE = Interpolator.SPLINE(.25, .1, .25, 1);
    public static final Interpolator CSS_EASE_IN_OUT = Interpolator.SPLINE(.42, 0, .58, 1);
    public static final Interpolator EASE_OUT = CSS_EASE;
    public static final Interpolator EASE_IN = CSS_EASE;
    public static final Interpolator EASE_IN_OUT = CSS_EASE_IN_OUT;

    /** Teto de duração em REDUCED. */
    public static final Duration REDUCED_MAX = Duration.millis(120);

    private MotionTokens() {
    }
}
