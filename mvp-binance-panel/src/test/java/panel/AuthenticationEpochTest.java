package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.auth.AuthService;
import panel.auth.AdminAccessService;
import panel.auth.SessionManager;
import panel.auth.TrustedDeviceService;
import panel.auth.TwoFactorResult;
import panel.authview.LoginController;
import panel.localservice.AuthorityClient;
import panel.security.AccessDeniedException;
import panel.security.Role;

/** Required V2.1V ownership matrix. Real AuthService/controller, synthetic authority, no sleeps or real IPC. */
class AuthenticationEpochTest {
    private static final String PASSWORD = "synthetic-password-1";
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled operation not reached/released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void stale(Future<?> task) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
        assertInstanceOf(AuthService.StaleAuthenticationException.class, error.getCause());
    }
    private static final class Fixture implements AutoCloseable {
        final FakeAuthority authority = new FakeAuthority(Clock.systemUTC());
        final SessionManager sessions = new SessionManager();
        final AuthService auth = new AuthService(authority, sessions, Clock.systemUTC());
        final TrustedDeviceService devices = new TrustedDeviceService(auth, Clock.systemUTC());
        final AdminAccessService access = new AdminAccessService(sessions, authority, auth, devices, Clock.systemUTC());
        final Queue<Runnable> workers = new ArrayDeque<>(), replies = new ArrayDeque<>();
        final List<String> navigation = new ArrayList<>();
        Fixture() {
            authority.add("qa-a", "a@example.invalid", "+5511999991234", PASSWORD, Role.ADMIN, false);
            authority.add("qa-b", "b@example.invalid", "+5511999994321", PASSWORD, Role.ADMIN, false);
        }
        LoginController controller() throws Exception {
            return FxSupport.fx(() -> new LoginController(auth::beginLogin, workers::add, replies::add,
                    user -> navigation.add(user.username())));
        }
        void submit(LoginController controller, String user, String password) throws Exception {
            FxSupport.fx(() -> { controller.submit(user, password.toCharArray()); return null; });
        }
        void reply(Runnable callback) throws Exception { FxSupport.fx(() -> { callback.run(); return null; }); }
        void assertB() {
            assertEquals("qa-b", sessions.user().orElseThrow().user().username());
            assertTrue(auth.captureSession().isCurrent());
            assertTrue(authority.hasSession(), "the synthetic Service session also remains active");
            assertEquals("qa-b", authority.sessionStatus().result().path("username").asText());
        }
        @Override public void close() { access.close(); auth.close(); }
    }

    @Test void discardedWalletGatewayCannotCallNewerSessionAuthority() {
        try (var f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var old = new panel.wallet.WalletGateway(f.auth.captureSession());
            f.auth.login("qa-b", PASSWORD.toCharArray());
            var failure = assertThrows(panel.wallet.WalletGateway.Unavailable.class, () -> old.create("synthetic-only"));
            assertEquals("STALE_AUTH_OPERATION", failure.code);
            assertEquals("qa-b", f.sessions.user().orElseThrow().user().username());
            assertThrows(panel.wallet.WalletGateway.Unavailable.class, old::read);
            assertTrue(f.authority.hasSession());
        }
    }
    @Test void aSuccessArrivingAfterBStartsCannotPublishAOrPreventBFromAuthenticating() throws Exception {
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Fixture f = new Fixture()) {
            f.authority.beforeLogin = id -> { if (id.equals("qa-a")) { reached.countDown(); await(release); } };
            var a = FxSupport.fx(f.auth::beginLogin);
            Future<?> first = workers.submit(() -> a.authenticate("qa-a", PASSWORD.toCharArray()));
            await(reached);
            // A holds transport, yet the FX ownership change and another FX pulse are non-blocking.
            var b = FxSupport.fx(f.auth::beginLogin);
            Future<?> second = workers.submit(() -> b.authenticate("qa-b", PASSWORD.toCharArray()));
            FxSupport.fx(() -> { assertTrue(b.isCurrent()); return null; });
            release.countDown();
            stale(first);
            second.get(5, TimeUnit.SECONDS);
            assertFalse(a.deliver(() -> fail("A published after B's generation began")));
            f.assertB();
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void aServiceFailureArrivingAfterBStartsIsStaleAndCannotInvalidateB() throws Exception {
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Fixture f = new Fixture()) {
            f.authority.beforeLogin = id -> { if (id.equals("qa-a")) { reached.countDown(); await(release); } };
            var a = FxSupport.fx(f.auth::beginLogin);
            Future<?> old = workers.submit(() -> a.authenticate("qa-a", "wrong-password".toCharArray()));
            await(reached);
            var b = FxSupport.fx(f.auth::beginLogin);
            Future<?> fresh = workers.submit(() -> b.authenticate("qa-b", PASSWORD.toCharArray()));
            release.countDown(); stale(old); fresh.get(5, TimeUnit.SECONDS);
            assertFalse(a.deliver(() -> fail("old failure was published")));
            f.assertB();
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void aGenuineCurrentSessionFailureMayShowItsFallbackOnlyUntilAnotherGenerationBegins() {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var failed = f.auth.captureSession();
            f.authority.unavailable = true;
            assertTrue(f.auth.refreshUser(failed).isEmpty());
            assertTrue(f.sessions.user().isEmpty(), "genuine current failure remains fail-closed");
            assertTrue(failed.isLatestOutcome());
            f.authority.unavailable = false;
            f.auth.login("qa-b", PASSWORD.toCharArray());
            assertFalse(failed.isLatestOutcome(), "a late error cannot navigate away from B");
            failed.invalidate(); f.assertB();
        }
    }

    @Test void anOldFailureCallbackArrivingAfterBSuccessCannotClearBOrReplaceItsScreen() throws Exception {
        try (Fixture f = new Fixture()) {
            var a = f.controller(); f.submit(a, "qa-a", "wrong-password");
            f.workers.remove().run();
            Runnable failure = f.replies.remove();
            var b = f.controller(); f.submit(b, "qa-b", PASSWORD);
            f.workers.remove().run(); f.reply(f.replies.remove());
            f.reply(failure);
            f.assertB();
            assertEquals(List.of("qa-b"), f.navigation);
            assertEquals(LoginController.State.SUCCESS, b.state());
            assertEquals(LoginController.State.LOADING, a.state(), "stale failure does not alter even its discarded presentation");
        }
    }

    @Test void cancellingAAfterBIsActiveAndDeliveringAsOldSuccessCannotLogoutOrNavigateB() throws Exception {
        try (Fixture f = new Fixture()) {
            var a = f.controller(); f.submit(a, "qa-a", PASSWORD); f.workers.remove().run();
            Runnable success = f.replies.remove();
            var b = f.controller(); f.submit(b, "qa-b", PASSWORD); f.workers.remove().run(); f.reply(f.replies.remove());
            FxSupport.fx(() -> { a.dispose(); a.dispose(); return null; });
            f.reply(success);
            f.assertB();
            assertEquals(List.of("qa-b"), f.navigation);
        }
    }

    @Test void cleanupCapturedForACannotConsumeTheTokenBelongingToB() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            Runnable oldLogout = FxSupport.fx(f.auth::prepareLogout);
            f.auth.login("qa-b", PASSWORD.toCharArray());
            oldLogout.run(); oldLogout.run();
            f.assertB();
        }
    }

    @Test void onlyTheLatestOfFortyRapidQueuedAttemptsCanReachTheServiceOrNavigate() throws Exception {
        try (Fixture f = new Fixture()) {
            List<LoginController> controllers = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                var controller = f.controller(); controllers.add(controller);
                f.submit(controller, i == 39 ? "qa-b" : "qa-a", PASSWORD);
            }
            while (!f.workers.isEmpty()) f.workers.remove().run();
            while (!f.replies.isEmpty()) f.reply(f.replies.remove());
            f.assertB();
            assertEquals(List.of("qa-b"), f.navigation);
            assertEquals(1, f.authority.calls.stream().filter("login"::equals).count());
            for (int i = 0; i < 39; i++) {
                var discarded = controllers.get(i);
                FxSupport.fx(() -> { discarded.dispose(); return null; });
            }
            f.assertB();
        }
    }

    @Test void lateLogoutCompletionCannotNavigateAwayFromBOrMutateItsScreen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var logout = FxSupport.fx(f.auth::prepareLogout);
            logout.run();
            f.auth.login("qa-b", PASSWORD.toCharArray());
            f.reply(() -> assertFalse(logout.deliver(() -> f.navigation.add("login"))));
            assertTrue(f.navigation.isEmpty());
            f.assertB();
            logout.run();
            f.assertB();
        }
    }

    @Test void logoutCompletionIsDiscardedAsSoonAsBStartsEvenBeforeItsWorkerRuns() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var logout = FxSupport.fx(f.auth::prepareLogout);
            var b = FxSupport.fx(f.auth::beginLogin);
            logout.run();
            f.reply(() -> assertFalse(logout.deliver(() -> fail("old logout navigation executed"))));
            assertTrue(b.isCurrent());
            b.authenticate("qa-b", PASSWORD.toCharArray());
            f.assertB();
        }
    }

    @Test void logoutOfTheActiveSessionStillRevokesTheServiceSessionAndClearsPresentation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-b", PASSWORD.toCharArray());
            AtomicBoolean cleanupOnFx = new AtomicBoolean();
            f.authority.beforeLogout = () -> cleanupOnFx.set(javafx.application.Platform.isFxApplicationThread());
            var logout = FxSupport.fx(f.auth::prepareLogout);
            assertTrue(f.sessions.user().isEmpty());
            logout.run();
            assertFalse(f.authority.hasSession());
            assertTrue(f.authority.calls.contains("logout"));
            assertFalse(cleanupOnFx.get());
            f.reply(() -> assertTrue(logout.deliver(() -> f.navigation.add("login"))));
            f.reply(() -> assertFalse(logout.deliver(() -> fail("logout completion replayed"))));
            assertEquals(List.of("login"), f.navigation);
        }
    }

    @Test void aCurrentFailedLoginRemainsInvalidAndHasNoAuthenticatedResourcesOrSuccessNavigation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var b = f.controller(); f.submit(b, "qa-b", "wrong-password");
            f.workers.remove().run(); f.reply(f.replies.remove());
            assertEquals(LoginController.State.INVALID, b.state());
            assertTrue(f.sessions.user().isEmpty());
            assertTrue(f.sessions.admin().isEmpty());
            assertFalse(f.authority.hasSession());
            assertTrue(f.navigation.isEmpty());
        }
    }

    @Test void disposingWithAQueuedAuthenticationZerosItsPasswordAndMakesTheRequestANoOp() throws Exception {
        try (Fixture f = new Fixture()) {
            var controller = f.controller(); char[] password = PASSWORD.toCharArray();
            FxSupport.fx(() -> { controller.submit("qa-a", password); controller.dispose(); return null; });
            assertArrayEquals(new char[password.length], password);
            f.workers.remove().run(); f.reply(f.replies.remove());
            assertFalse(f.authority.calls.contains("login"));
            assertTrue(f.sessions.user().isEmpty());
            assertTrue(f.navigation.isEmpty());
        }
    }

    @Test void anOldTwoFactorReplyAndTrustedDeviceOperationsCannotElevateInvalidateOrEnrollForB() throws Exception {
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var oldScope = f.auth.captureSession();
            var flow = f.access.startTwoFactor();
            f.authority.beforeBegin = () -> { reached.countDown(); await(release); };
            Future<?> old = workers.submit(flow::sendEmailCode);
            await(reached);
            var b = FxSupport.fx(f.auth::beginLogin);
            Future<?> fresh = workers.submit(() -> b.authenticate("qa-b", PASSWORD.toCharArray()));
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RuntimeException.class, failure.getCause());
            fresh.get(5, TimeUnit.SECONDS);
            f.assertB();
            int calls = f.authority.calls.size();
            assertEquals(TwoFactorResult.EXPIRED, flow.verifyEmail("000000"));
            assertFalse(f.access.tryTrustedDevice(oldScope));
            assertThrows(AccessDeniedException.class, () -> f.devices.trustCurrent(oldScope));
            assertThrows(AccessDeniedException.class, () -> f.devices.revoke(oldScope, "old-device"));
            oldScope.invalidate();
            assertEquals(calls, f.authority.calls.size(), "stale MFA/device/cleanup operations make no Service request for B");
            f.assertB(); assertTrue(f.sessions.admin().isEmpty());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void anOldStatusPublicationOrFailureCannotOverwriteOrInvalidateB() throws Exception {
        try (Fixture f = new Fixture()) {
            f.auth.login("qa-a", PASSWORD.toCharArray());
            var old = f.auth.captureSession();
            f.auth.login("qa-b", PASSWORD.toCharArray());
            assertFalse(old.present(() -> { f.sessions.logout(); fail("old cache publication executed"); }));
            old.invalidate();
            assertFalse(old.call(g -> { fail("old status queried B"); return null; }).ok());
            f.assertB();
        }
    }

    @Test void replayingACompletionCannotNavigateTwiceAndDisposingAfterHandoffKeepsTheLiveSession() throws Exception {
        try (Fixture f = new Fixture()) {
            var controller = f.controller(); f.submit(controller, "qa-b", PASSWORD); f.workers.remove().run();
            Runnable reply = f.replies.remove(); f.reply(reply); f.reply(reply);
            FxSupport.fx(() -> { controller.dispose(); return null; });
            assertEquals(List.of("qa-b"), f.navigation); f.assertB();
        }
    }

    @Test void restartingThePanelClientDoesNotRestoreOrAdoptAnyOldSession(@TempDir java.nio.file.Path home) {
        try (AuthorityClient client = new AuthorityClient(home)) {
            assertFalse(client.hasSession(), "new process/client starts without an opaque token; no persistence/restoration exists");
            SessionManager sessions = new SessionManager();
            AuthService auth = new AuthService(client, sessions, Clock.systemUTC());
            try { assertTrue(sessions.user().isEmpty()); assertFalse(auth.captureSession().isCurrent()); }
            finally { auth.close(); }
        }
    }
}
