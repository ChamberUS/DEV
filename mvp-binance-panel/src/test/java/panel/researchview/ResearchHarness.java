package panel.researchview;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import panel.design.ByxTheme;
import panel.model.Snapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/** Research Overview dentro do shell V2 real, com uma fonte controlável e os pedidos de navegação registrados. */
final class ResearchHarness {
    static final class Stub implements ResearchOverview.Source {
        Snapshot snapshot = ResearchFixtures.empty();
        boolean labelsRunning;
        int failed;

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public boolean labelsRunning() {
            return labelsRunning;
        }

        @Override
        public int failedJobs() {
            return failed;
        }
    }

    final MotionService motion = new MotionService();
    final Stub source = new Stub();
    final List<String> navigations = new ArrayList<>();
    final ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
    final ByxShell shell = new ByxShell(router, motion, new LegacyHost());
    final ResearchOverview overview = new ResearchOverview(motion, source, navigations::add);
    Scene scene;

    ResearchHarness() {
        shell.v2Content().getChildren().add(overview.node());
        shell.showV2(true);
        router.request("overview");
    }

    static ResearchHarness open(int w, int h, Snapshot first, MotionPreference mode) {
        ResearchHarness r = new ResearchHarness();
        r.motion.preference.set(mode);
        r.resize(w, h);
        r.show(first);
        return r;
    }

    static ResearchHarness open(int w, int h, Snapshot first) {
        return open(w, h, first, MotionPreference.FULL);
    }

    void resize(int w, int h) {
        if (scene != null) {
            scene.setRoot(new javafx.scene.layout.Pane());
        }
        scene = new Scene(shell, w, h);
        ByxTheme.apply(scene);
        layout();
    }

    void layout() {
        for (int i = 0; i < 3; i++) {
            shell.applyCss();
            shell.layout();
        }
    }

    void show(Snapshot s) {
        source.snapshot = s;
        overview.onSnapshot(null);
        layout();
    }

    Bounds rect(Node n) {
        return n.localToScene(n.getLayoutBounds());
    }

    void close() {
        shell.dispose();
    }
}
