package panel;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import panel.design.ByxTheme;
import panel.homeview.HomeMarkets;
import panel.homeview.HomeScreen;
import panel.model.ByxSnapshot;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/**
 * Visual QA of the Home with fixture states, inside the REAL shell, at the target window sizes (not run by surefire).
 * Usage: java -cp ... panel.HomeVisualQa OUTDIR
 */
public final class HomeVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static Path out;

    private static final class Data implements HomeScreen.Data {
        TraderSnapshot t = new TraderSnapshot();
        HomeMarkets.Catalog catalog = HomeMarkets.productionCatalog();
        boolean research;

        Data() {
            t.symbol = "ETHUSDT";
            t.market = "USD-M Futures";
            t.feed = "LIVE";
            t.feedUpdatedAt = NOW.minusSeconds(2);
            t.price = 3412.55;
            t.change24hPct = 1.84;
            t.volume24h = 1_284_550_212.0;
        }

        @Override public String displayName() { return "Alex Demo"; }
        @Override public TraderSnapshot trader() { return t; }
        @Override public ByxSnapshot network() { return ByxSnapshot.unknown("LIVE_NODE", "LOCALNET", "ONLINE", null); }
        @Override public boolean researchVisible() { return research; }
        @Override public boolean walletVerified() { return false; }
        @Override public HomeMarkets.Catalog catalog() { return catalog; }
        @Override public void retryCatalog() { }
        @Override public void replayWelcome() { }
    }

    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        Files.createDirectories(out);
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.startup(() -> {
            try {
                run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }
        System.out.println("HOME_VISUAL_QA_OK " + out);
    }

    private static void run() throws Exception {
        int[][] sizes = {{1920, 1080}, {1440, 900}, {1100, 700}, {900, 640}};
        for (int[] s : sizes) {
            shot("ready", s, d -> { });
        }
        shot("ready-tall-research", new int[] {1440, 1250}, d -> d.research = true);
        int[] s = {1440, 900};
        shot("stale", s, d -> {
            d.t.feed = "RECONNECTING";
            d.t.feedUpdatedAt = NOW.minusSeconds(134);
        });
        shot("offline-no-data", s, d -> {
            d.t.feed = "DISCONNECTED";
            d.t.price = null;
            d.t.change24hPct = null;
            d.t.volume24h = null;
            d.t.feedUpdatedAt = null;
        });
        shot("partial-data", s, d -> {
            d.t.change24hPct = null;
            d.t.volume24h = null;
        });
        shot("error-catalog", s, d -> d.catalog = new HomeMarkets.Catalog.Failed("timeout"));
        shot("empty-catalog", s, d -> d.catalog = new HomeMarkets.Catalog.Loaded(List.of()));
        shot("loading", s, d -> d.catalog = new HomeMarkets.Catalog.Loading());
        shot("spot-and-perp", s, d -> d.catalog = new HomeMarkets.Catalog.Loaded(List.of(
                new HomeMarkets.Instrument("ETHUSDT", HomeMarkets.Kind.PERPETUAL, "Binance USD-M Futures", "USDT"),
                new HomeMarkets.Instrument("ETHUSDT", HomeMarkets.Kind.SPOT, "Binance Spot", "USDT"))));
        shot("admin-research-card", s, d -> d.research = true);
        shot("unsupported-search", s, d -> { }, "BTC spot");
    }

    private static void shot(String name, int[] size, java.util.function.Consumer<Data> setup) throws Exception {
        shot(name, size, setup, "");
    }

    private static void shot(String name, int[] size, java.util.function.Consumer<Data> setup, String query) throws Exception {
        Data d = new Data();
        setup.accept(d);
        MotionService motion = new MotionService();
        motion.preference.set(MotionPreference.OFF);
        ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
        ByxShell shell = new ByxShell(router, motion, new LegacyHost());
        shell.setAvailable(id -> true);
        HomeScreen home = new HomeScreen(motion, Clock.fixed(NOW, ZoneOffset.UTC), d, id -> { });
        shell.v2Content().getChildren().add(home.node());
        shell.showV2(true);
        shell.topBar().setUser("Alex Demo");
        Scene scene = new Scene(shell, size[0], size[1]);
        ByxTheme.apply(scene);
        router.request("t-home");
        if (!query.isEmpty()) {
            home.searchField().setText(query);
        }
        home.onShow();
        shell.applyCss();
        shell.layout();
        shell.applyCss();
        shell.layout();
        var image = scene.snapshot(null);
        write(image, out.resolve(String.format("home-%s-%dx%d.png", name, size[0], size[1])));
        home.dispose();
        shell.dispose();
    }

    static void write(javafx.scene.image.Image image, Path file) throws Exception {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
    }
}
