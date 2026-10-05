package panel.tradeview;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Assumptions;
import panel.design.ByxTheme;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/** Desk dentro do shell V2 real (rail 68, top bar 56, dock 38) numa cena de tamanho exato; relógio controlável. */
final class DeskHarness {
    /** Relógio mutável: o Desk nunca lê o relógio do sistema. */
    static final class TestClock extends Clock {
        Instant now = DeskFixtures.NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MotionService motion = new MotionService();
    final TestClock clock = new TestClock();
    final ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
    final ByxShell shell = new ByxShell(router, motion, new LegacyHost());
    final TradingDesk desk;
    Scene scene;
    TraderSnapshot current;

    DeskHarness(TraderSnapshot first) {
        current = first;
        desk = new TradingDesk(motion, () -> current, clock);
        shell.v2Content().getChildren().add(desk);
        shell.showV2(true);
        router.request("t-desk");
    }

    static void start() {
        FxStart.start();
    }

    /** Roda na thread FX. */
    static DeskHarness open(int w, int h, TraderSnapshot first, MotionPreference mode) {
        DeskHarness d = new DeskHarness(first);
        d.motion.preference.set(mode);
        d.resize(w, h);
        d.desk.onShow();
        d.show(first);
        return d;
    }

    static DeskHarness open(int w, int h, TraderSnapshot first) {
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

    void show(TraderSnapshot t) {
        current = t;
        desk.onSnapshot(null);
        layout();
    }

    Bounds rect(Node n) {
        return n.localToScene(n.getLayoutBounds());
    }

    WritableImage shot() {
        layout();
        return scene.snapshot(null);
    }

    void close() {
        desk.onHide();
        shell.dispose();
    }

    static <T> T fx(Supplier<T> s) throws Exception {
        FxStart.start();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(s.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                l.countDown();
            }
        });
        if (!l.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread timeout");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return out.get();
    }

    static void fx(Runnable r) throws Exception {
        fx(() -> {
            r.run();
            return null;
        });
    }

    /** Toolkit JavaFX uma vez; sem display o teste é abortado (como FxSupport). */
    private static final class FxStart {
        private static boolean started;

        static synchronized void start() {
            if (started) {
                return;
            }
            try {
                CountDownLatch l = new CountDownLatch(1);
                Platform.startup(l::countDown);
                Assumptions.assumeTrue(l.await(10, TimeUnit.SECONDS), "JavaFX toolkit unavailable");
            } catch (IllegalStateException e) {
                // já iniciado
            } catch (Throwable e) {
                Assumptions.abort("JavaFX toolkit unavailable: " + e);
            }
            Platform.setImplicitExit(false);
            started = true;
        }
    }
}
