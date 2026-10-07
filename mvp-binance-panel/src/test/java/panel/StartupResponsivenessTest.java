package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.authview.AuthLayout;
import panel.authview.AuthScreens;
import panel.design.ByxTheme;
import panel.model.ScientificCapture;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.service.ScientificCaptureService;
import panel.user.User;

/**
 * Critério de responsividade (não de tempo total): durante o startup e durante lentidão/queda dos serviços de fundo, um pulso na thread FX
 * continua sendo processado dentro de um intervalo limitado. Mede o MAIOR atraso entre o pulso agendado e o executado.
 */
class StartupResponsivenessTest {
    /** Maior atraso (ms) de um pulso FX enquanto {@code work} roda numa thread de fundo por {@code ms}. */
    private static long maxFxGapDuring(long ms, Runnable work) throws Exception {
        FxSupport.start();
        AtomicLong max = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        Thread beat = new Thread(() -> {
            while (!stop.get()) {
                long due = System.nanoTime();
                CountDownLatch l = new CountDownLatch(1);
                Platform.runLater(l::countDown);
                try {
                    l.await(10, TimeUnit.SECONDS);
                    max.accumulateAndGet((System.nanoTime() - due) / 1_000_000, Math::max);
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "test-heartbeat");
        beat.setDaemon(true);
        beat.start();
        work.run();
        Thread.sleep(ms);
        stop.set(true);
        beat.join(2000);
        return max.get();
    }

    @Test
    void slowAuthServiceNeverBlocksTheLoginWindow() throws Exception {
        Object[] box = new Object[2];
        FxSupport.fx(() -> {
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.FULL);
            AuthScreens screens = new AuthScreens(motion, new AuthScreens.Services() {
                @Override public User login(String id, char[] pw) { throw new AuthService.LoginException(AuthService.Failure.INVALID_CREDENTIALS, null); }
                @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { }
                @Override public void changeOwnPassword(long id, char[] c, char[] n) { }
                @Override public void endSession() { }
            }, r -> { }, u -> { }, () -> { }, m -> { }, r -> { }, null);
            Scene s = new Scene((javafx.scene.Parent) screens.node(), 1440, 900);
            ByxTheme.apply(s);
            Stage stage = new Stage();
            stage.setScene(s);
            stage.show();
            screens.layout().brand().setFocusOverride(Boolean.TRUE);
            screens.show(AuthScreens.LOGIN, null, null);
            screens.setServiceReadiness(AuthScreens.ServiceReadiness.STARTING, () -> { });
            box[0] = screens;
            box[1] = stage;
        });
        AuthScreens screens = (AuthScreens) box[0];
        long gap = maxFxGapDuring(1500, () -> {
            // "serviço lento": 1,2 s de fundo até ficar pronto; a janela segue viva e honesta o tempo todo
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                    return;
                }
                Platform.runLater(() -> screens.setServiceReadiness(AuthScreens.ServiceReadiness.READY, () -> { }));
            });
            t.setDaemon(true);
            t.start();
        });
        AuthScreens.ServiceReadiness after = FxSupport.fx(() -> screens.serviceReadiness());
        FxSupport.fx(() -> { screens.dispose(); ((Stage) box[1]).close(); return null; });
        assertEquals(AuthScreens.ServiceReadiness.READY, after);
        assertTrue(gap < 750, "FX pulse delayed " + gap + " ms while the service was slow: the window must stay responsive");
    }

    @Test
    void hangingCaptureResolverDoesNotTouchTheFxThread() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        ScientificCaptureService s = new ScientificCaptureService(now -> {
            while (never.getCount() > 0) { // travado de verdade
                try {
                    never.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // preso
                }
            }
            return ScientificCapture.unknown("x");
        }, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofMillis(200));
        long[] readCost = new long[1];
        long gap = maxFxGapDuring(1200, () -> {
            s.start();
            long t0 = System.nanoTime();
            s.current(); // leitura do último valor: nunca espera o resolvedor
            readCost[0] = (System.nanoTime() - t0) / 1_000_000;
        });
        never.countDown();
        s.close();
        assertTrue(readCost[0] < 50, "current() must be a plain volatile read, was " + readCost[0] + " ms");
        assertTrue(gap < 750, "FX pulse delayed " + gap + " ms by a hanging capture resolver");
    }

    @Test
    void loginWithMotionOffOrFullKeepsTheFxThreadResponsive() throws Exception {
        for (MotionPreference p : new MotionPreference[] {MotionPreference.FULL, MotionPreference.OFF}) {
            Object[] box = new Object[2];
            FxSupport.fx(() -> {
                MotionService motion = new MotionService();
                motion.preference.set(p);
                AuthLayout layout = new AuthLayout(motion, null, () -> { });
                layout.show("Sign in", List.of(new Label("Sign in")));
                Stage stage = new Stage();
                Scene s = new Scene(layout, 1440, 900);
                ByxTheme.apply(s);
                stage.setScene(s);
                stage.show();
                layout.brand().setFocusOverride(Boolean.TRUE);
                box[0] = layout;
                box[1] = stage;
            });
            long gap = maxFxGapDuring(2000, () -> { });
            FxSupport.fx(() -> { ((AuthLayout) box[0]).dispose(); ((Stage) box[1]).close(); return null; });
            assertTrue(gap < 600, p + ": FX pulse delayed " + gap + " ms on an idle login");
        }
    }
}
