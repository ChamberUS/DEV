package panel.v2;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Node;
import javafx.scene.Scene;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/**
 * Captura de tela de uma View V2 dentro do shell real (rail, topo, dock), numa cena fora da tela do tamanho exato.
 * Uso manual (QA visual); a saída vai para a pasta dada por {@code -Dbyx.qa.out}.
 */
public final class QaShots {
    public static final Path OUT = Path.of(System.getProperty("byx.qa.out", "target/qa"));

    private QaShots() {
    }

    public static void shoot(String route, Node view, MotionService motion, String name, int w, int h) throws Exception {
        Files.createDirectories(OUT);
        ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
        ByxShell shell = new ByxShell(router, motion, new LegacyHost());
        view.setVisible(true);
        shell.v2Content().getChildren().add(view);
        shell.showV2(true);
        router.request(route);
        Scene scene = new Scene(shell, w, h);
        ByxTheme.apply(scene);
        for (int i = 0; i < 4; i++) {
            shell.applyCss();
            shell.layout();
        }
        var image = scene.snapshot(null);
        javax.imageio.ImageIO.write(panel.ControlGalleryTestAccess.toAwt(image), "png", OUT.resolve(name + ".png").toFile());
        scene.setRoot(new javafx.scene.layout.Pane());
        shell.dispose();
    }

    public static MotionService motion(MotionPreference p) {
        MotionService m = new MotionService();
        m.preference.set(p);
        return m;
    }
}
