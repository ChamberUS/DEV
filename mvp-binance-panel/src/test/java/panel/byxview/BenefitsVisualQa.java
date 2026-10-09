package panel.byxview;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import panel.design.ByxTheme;
import panel.model.ByxSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;
import panel.shell.avatar.Operations;

/** Visual QA of Benefits states in the REAL shell at the target window sizes (not run by surefire). Usage: ... panel.byxview.BenefitsVisualQa OUTDIR */
public final class BenefitsVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static Path out;

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
        System.out.println("BENEFITS_VISUAL_QA_OK " + out);
    }

    private static void run() throws Exception {
        int[] s = {1440, 900};
        shot("unauthorized", s, d -> d.reason = "SERVER_AUTHORIZATION_REQUIRED");
        shot("no-wallet", s, d -> { });
        shot("wallet-unavailable", s, d -> d.walletFailure = new IllegalStateException("localnet not configured"));
        shot("offline", s, d -> {
            d.wallets = java.util.List.of(BenefitsScreenTest.wallet());
            d.balance = BigInteger.valueOf(2_000_000);
            d.network = ByxSnapshot.unknown("LIVE_NODE", "LOCALNET", "OFFLINE", "down");
        });
        shot("balance-unknown", s, d -> {
            d.wallets = java.util.List.of(BenefitsScreenTest.wallet());
            d.balance = null;
        });
        for (int[] size : new int[][] {{1920, 1080}, {1440, 900}, {1100, 700}, {900, 640}}) {
            shot("known", size, d -> {
                d.wallets = java.util.List.of(BenefitsScreenTest.wallet());
                d.balance = BigInteger.valueOf(1_250_000_000L);
            });
        }
        shot("unauthorized", new int[] {1100, 700}, d -> d.reason = "SERVER_AUTHORIZATION_REQUIRED");
    }

    private static void shot(String name, int[] size, java.util.function.Consumer<BenefitsScreenTest.Data> setup) throws Exception {
        BenefitsScreenTest.Data d = new BenefitsScreenTest.Data();
        setup.accept(d);
        MotionService motion = new MotionService();
        motion.preference.set(MotionPreference.OFF);
        ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
        ByxShell shell = new ByxShell(router, motion, new LegacyHost());
        shell.setAvailable(id -> true);
        BenefitsScreen screen = new BenefitsScreen(Clock.fixed(NOW, ZoneOffset.UTC), d, Runnable::run, Runnable::run, () -> Operations.NONE, motion);
        shell.v2Content().getChildren().add(screen.node());
        shell.showV2(true);
        shell.topBar().setUser("Alex Demo");
        Scene scene = new Scene(shell, size[0], size[1]);
        ByxTheme.apply(scene);
        router.request("t-benefits");
        screen.onShow();
        shell.applyCss();
        shell.layout();
        shell.applyCss();
        shell.layout();
        var image = scene.snapshot(null);
        panel.HomeVisualQaAccess.write(image, out.resolve(String.format("benefits-%s-%dx%d.png", name, size[0], size[1])));
        screen.dispose();
        shell.dispose();
    }
}
