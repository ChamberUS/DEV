package panel.motion;

import java.util.Collection;
import javafx.scene.Node;

/** The reference has card entry, but no page crossfade or workspace slide. */
public class ViewTransitionService {
    private final MotionService motion;
    private Node current;
    private int entries;

    public ViewTransitionService(MotionService motion) { this.motion = motion; }

    public void show(Collection<Node> all, Node target, boolean workspaceSwitch) {
        boolean changed = current != target;
        for (Node node : all) if (node != target) {
            motion.reference.settleTree(node);
            motion.reset(node);
            node.setVisible(false);
        }
        current = target;
        target.setVisible(true);
        if (changed) {
            motion.reset(target);
            entries++;
            if (target instanceof javafx.scene.Parent parent) parent.applyCss();
            motion.reference.enterCards(target);
        }
        motion.refreshLoops();
    }

    public void forget() { current = null; }

    /** Quantas vezes a entrada de cards foi disparada (reexibir a mesma view não conta). */
    public int entries() { return entries; }
}
