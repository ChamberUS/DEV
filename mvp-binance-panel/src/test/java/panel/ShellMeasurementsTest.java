package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/**
 * Shell real contra a tabela de medidas do handoff (P2.4, "Shell 1440x900", x/y/largura/altura do DOM).
 * Larguras guiadas por texto têm tolerância de 4 px: o protótipo foi medido sem as fontes empacotadas.
 */
class ShellMeasurementsTest {
    private static final Map<String, double[]> P24 = new LinkedHashMap<>();

    static {
        P24.put("switcher", new double[] {88, 8, 244, 40});
        P24.put("search", new double[] {856, 10, 320, 36});
        P24.put("bell", new double[] {1192, 9, 38, 38});
        P24.put("admin", new double[] {1246, 15, 122, 26});
        P24.put("avatar", new double[] {1384, 10, 36, 36});
        P24.put("rail", new double[] {0, 0, 68, 900});   // §4: Rail
        P24.put("dock", new double[] {68, 862, 1372, 38}); // §4: Status dock
    }

    private static Map<String, Bounds> measure(int w, int h) throws Exception {
        return FxSupport.fx(() -> {
            ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
            ByxShell shell = new ByxShell(router, new MotionService(), new LegacyHost());
            Scene scene = new Scene(shell, w, h);
            ByxTheme.apply(scene);
            router.request("t-desk");
            shell.setCrumb(id -> "Desk / ETHUSDT");
            shell.topBar().setAdminSession(true);
            shell.topBar().setUser("Alex Demo");
            for (int i = 0; i < 2; i++) {
                shell.applyCss();
                shell.layout();
            }
            Map<String, Node> nodes = Map.of("switcher", shell.switcher(), "search", shell.topBar().search(),
                    "bell", shell.topBar().notifications(), "admin", shell.topBar().lookup(".byx-admin-badge"),
                    "avatar", shell.topBar().avatar(), "rail", shell.rail(), "dock", shell.dock());
            Map<String, Bounds> out = new LinkedHashMap<>();
            nodes.forEach((k, n) -> out.put(k, n.localToScene(n.getLayoutBounds())));
            shell.dispose();
            return out;
        });
    }

    @Test
    void shellMatchesP24At1440() throws Exception {
        Map<String, Bounds> got = measure(1440, 900);
        P24.forEach((k, ref) -> {
            Bounds b = got.get(k);
            double[] v = {b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()};
            String[] axis = {"x", "y", "w", "h"};
            for (int i = 0; i < 4; i++) {
                double tol = k.equals("switcher") && i == 2 ? 4 : 1; // largura do seletor depende do texto
                assertEquals(ref[i], v[i], tol, k + " " + axis[i]);
            }
        });
    }

    /**
     * STANDARD e EXPANDED (referência trading-desk-1600/1920 medida no navegador): nada escala. Alturas fixas,
     * seletor ancorado em x=88, busca/sino/selo/avatar ancorados à direita com os mesmos deslocamentos de 1440.
     */
    @Test
    void shellAddsSpaceInsteadOfScaling() throws Exception {
        for (int[] s : new int[][] {{1600, 1000}, {1920, 1080}}) {
            Map<String, Bounds> got = measure(s[0], s[1]);
            int w = s[0];
            String at = w + "x" + s[1];
            assertEquals(88, got.get("switcher").getMinX(), 0.5, at + " switcher x");
            assertEquals(40, got.get("switcher").getHeight(), 0.5, at + " switcher h");
            assertEquals(w - 584, got.get("search").getMinX(), 1, at + " search anchored right");
            assertEquals(320, got.get("search").getWidth(), 0.5, at + " search width");
            assertEquals(w - 248, got.get("bell").getMinX(), 1, at + " bell anchored right");
            assertEquals(w - 194, got.get("admin").getMinX(), 1, at + " admin anchored right");
            assertEquals(w - 56, got.get("avatar").getMinX(), 0.5, at + " avatar anchored right");
            assertEquals(68, got.get("rail").getWidth(), 0.5, at + " rail");
            assertEquals(s[1] - 38, got.get("dock").getMinY(), 0.5, at + " dock y");
            assertEquals(38, got.get("dock").getHeight(), 0.5, at + " dock h");
            assertEquals(w - 68, got.get("dock").getWidth(), 0.5, at + " dock w");
        }
    }
}
