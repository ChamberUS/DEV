package panel.motion;

import java.util.Collection;
import javafx.scene.Node;

/**
 * The reference has card entry, but no page crossfade or workspace slide.
 * P3.3: the heavy card entry plays once per View instance, on its first display. Returning to an
 * existing View, data refresh, status updates, resize and background to foreground never replay it.
 * The "already entered" mark lives on the View's own node, so a disposed View that is recreated as a
 * new instance gets its first entry again; {@link #forget()} ends every instance of the session.
 */
public class ViewTransitionService {
    private static final String ENTERED = "byx.view.entered";

    private final MotionService motion;
    private Node current;
    private int entries;
    /** Session of View instances; marks from an earlier session are stale. */
    private long generation;

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
        if (changed && !hasEntered(target)) {
            motion.reset(target);
            entries++;
            target.getProperties().put(ENTERED, generation);
            if (target instanceof javafx.scene.Parent parent) parent.applyCss();
            motion.reference.enterCards(target);
        }
        motion.refreshLoops();
    }

    /** True once this View instance played its first entry in the current session. */
    public boolean hasEntered(Node view) {
        Object mark = view.getProperties().get(ENTERED);
        return mark instanceof Long g && g == generation;
    }

    /** The View instance is disposed: a new instance (or this node reused as new) enters again. */
    public void dispose(Node view) {
        view.getProperties().remove(ENTERED);
        if (current == view) current = null;
    }

    /** End of session (logout/login): every View instance of the session is disposed. */
    public void forget() { current = null; generation++; }

    /** Quantas vezes a entrada de cards foi disparada (reexibir a mesma view não conta). */
    public int entries() { return entries; }
}
