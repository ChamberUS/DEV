package panel.motion;

import java.util.Collection;
import javafx.scene.Node;

/** Troca de páginas com crossfade + deslocamento curto. Não recria views nem dados. */
public class ViewTransitionService {
    private final MotionService motion;
    private Node current;

    public ViewTransitionService(MotionService motion) {
        this.motion = motion;
    }

    public void show(Collection<Node> all, Node target, boolean workspaceSwitch) {
        Node prev = current;
        current = target;
        for (Node n : all) {
            if (n != target && n != prev) {
                hide(n);
            }
        }
        if (prev == target) {
            target.setVisible(true);
            motion.refreshLoops();
            return;
        }
        target.setVisible(true);
        if (prev != null && !motion.off() && target instanceof javafx.scene.Parent p) {
            // aplica o CSS antes de animar: senão o 1º frame pesado consome a transição inteira
            target.setOpacity(0);
            p.applyCss();
        }
        if (prev == null || motion.off()) {
            if (prev != null) {
                hide(prev);
            }
            motion.reset(target);
            motion.refreshLoops();
            return;
        }
        double dy = workspaceSwitch ? 12 : 10;
        motion.fadeSlideIn(target, 0, dy, workspaceSwitch ? MotionTokens.EMPHASIS : MotionTokens.STANDARD);
        motion.fadeOut(prev, workspaceSwitch ? MotionTokens.FAST : MotionTokens.MICRO, () -> {
            if (prev != current) {
                hide(prev);
            }
            motion.refreshLoops();
        });
        motion.refreshLoops();
    }

    public void forget() {
        current = null;
    }

    private void hide(Node n) {
        motion.cancel(n);
        n.setVisible(false);
        n.setOpacity(1);
        n.setTranslateX(0);
        n.setTranslateY(0);
    }
}
