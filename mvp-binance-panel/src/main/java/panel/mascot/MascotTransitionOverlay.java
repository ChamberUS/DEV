package panel.mascot;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Transição CURTA de mudança de workspace (Trading ⇄ Research ⇄ BYX): o mascote "aparece" por ~450 ms sobre o conteúdo, sem capturar mouse e SEM atrasar a navegação (a tela já está pronta quando isto
 * toca). Só em FULL; criado e carregado sob demanda (nada no startup). Um disparo em andamento ignora novos (sem acúmulo).
 */
public final class MascotTransitionOverlay extends StackPane {
    private final MotionService motion;
    private MascotView view;
    private SequentialTransition running;

    public MascotTransitionOverlay(MotionService motion) {
        this.motion = motion;
        setMouseTransparent(true);
        setPickOnBounds(false);
        setAlignment(Pos.CENTER);
        setVisible(false);
        setManaged(false);
        setOpacity(0);
    }

    public void trigger() {
        if (!motion.full() || running != null) {
            return;
        }
        if (view == null) {
            view = new MascotView(motion, 96);
            getChildren().add(view);
        }
        setManaged(true);
        setVisible(true);
        setOpacity(0);
        view.setState(MascotState.IDLE);
        view.setStaticState(MascotState.IDLE);
        view.play(MascotState.TRANSITION);
        FadeTransition in = new FadeTransition(Duration.millis(90), this);
        in.setToValue(1);
        PauseTransition stay = new PauseTransition(Duration.millis(380));
        FadeTransition out = new FadeTransition(Duration.millis(160), this);
        out.setToValue(0);
        SequentialTransition seq = new SequentialTransition(in, stay, out);
        seq.setOnFinished(e -> finish());
        running = seq;
        seq.play();
    }

    private void finish() {
        running = null;
        setVisible(false);
        setManaged(false);
        if (view != null) {
            view.stop();
        }
    }

    public boolean busy() {
        return running != null;
    }

    public void dispose() {
        if (running != null) {
            running.stop();
            running = null;
        }
        if (view != null) {
            view.dispose();
            getChildren().remove(view);
            view = null;
        }
    }
}
