package panel.design;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import panel.motion.MotionService;
import panel.motion.MotionSpec;

/**
 * Ponto de estado (dock 9 px, chip 8 px). O pulso (statePulse) existe só em FULL e só enquanto o estado é
 * CONNECTING ou RECONNECTING: criado com o estado, parado com ele e ao sair da cena. Nunca acumula.
 */
public class ByxStatusDot extends Circle {
    private static final String[] STATE_CLASSES = {"state-operational", "state-connecting", "state-reconnecting",
            "state-degraded", "state-unavailable", "state-unavailable-expected", "state-unknown"};

    private final MotionService motion;
    private StatusState state = StatusState.UNKNOWN;
    private boolean expected;
    private Timeline pulse;
    // fraco: MotionService vive o app inteiro e não pode reter pontos descartados
    private final javafx.beans.value.ChangeListener<Object> modeListener = (o, a, b) -> syncPulse();

    public ByxStatusDot(double diameter, MotionService motion) {
        super(diameter / 2);
        this.motion = motion;
        getStyleClass().addAll("byx-status-dot", StatusState.UNKNOWN.styleClass(false));
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                stopPulse();
            } else {
                syncPulse();
            }
        });
        if (motion != null) {
            motion.preference.addListener(new javafx.beans.value.WeakChangeListener<>(modeListener));
        }
    }

    public void setState(StatusState s, boolean expectedUnavailable) {
        state = s;
        expected = expectedUnavailable;
        getStyleClass().removeAll(STATE_CLASSES);
        getStyleClass().add(s.styleClass(expectedUnavailable));
        syncPulse();
    }

    public StatusState state() {
        return state;
    }

    public boolean expected() {
        return expected;
    }

    public boolean pulsing() {
        return pulse != null && pulse.getStatus() == Animation.Status.RUNNING;
    }

    /** Teste/diagnóstico: o pulso atual (no máximo um). */
    Timeline pulse() {
        return pulse;
    }

    private void syncPulse() {
        MotionSpec.Resolved r = motion == null ? null : motion.token("statePulse");
        boolean want = state.pulses() && getScene() != null && r != null && r.runs();
        if (!want) {
            stopPulse();
            return;
        }
        if (pulse != null) {
            return;
        }
        Duration d = r.duration();
        pulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(opacityProperty(), 1)),
                new KeyFrame(d.multiply(0.5), new KeyValue(opacityProperty(), 0.35)),
                new KeyFrame(d, new KeyValue(opacityProperty(), 1)));
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.stop();
            pulse = null;
        }
        setOpacity(1);
    }
}
