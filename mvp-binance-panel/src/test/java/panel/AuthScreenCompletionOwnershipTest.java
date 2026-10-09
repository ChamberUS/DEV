package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.auth.SessionManager;
import panel.auth.SessionOperation;
import panel.authview.AuthScreens;
import panel.design.ByxField;
import panel.design.ByxTheme;
import panel.motion.MotionService;
import panel.security.Role;
import panel.user.UserService;

/** Real mandatory-password screen: its identity and screen generation stay unchanged while B replaces A. */
class AuthScreenCompletionOwnershipTest {
    private static final String PASSWORD = "synthetic-password-1";
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @Test void oldSignOutCompletionCannotRequestLoginAfterBAuthenticates() throws Exception { verify(true, false); }
    @Test void oldPasswordSuccessCannotContinueAsB() throws Exception { verify(false, false); }
    @Test void oldPasswordFailureCannotChangeBsScreen() throws Exception { verify(false, true); }

    private void verify(boolean logout, boolean failPassword) throws Exception {
        FakeAuthority gateway = new FakeAuthority(Clock.systemUTC());
        gateway.add("qa-a", "a@example.invalid", "+5511999991234", PASSWORD, Role.USER, true);
        gateway.add("qa-b", "b@example.invalid", "+5511999994321", PASSWORD, Role.USER, false);
        SessionManager sessions = new SessionManager();
        AuthService auth = new AuthService(gateway, sessions, Clock.systemUTC());
        UserService users = new UserService(gateway, auth, sessions);
        var a = auth.login("qa-a", PASSWORD.toCharArray());
        CountDownLatch completed = new CountDownLatch(1), release = new CountDownLatch(1), delivered = new CountDownLatch(1);
        List<String> callbacks = new ArrayList<>();
        AuthScreens screens = FxSupport.fx(() -> {
            var result = new AuthScreens(new MotionService(), new AuthScreens.Services() {
                @Override public panel.auth.AuthenticationRequest beginLogin() { return auth.beginLogin(); }
                @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { fail("setup called"); }
                private SessionOperation block(SessionOperation operation) {
                    return new SessionOperation() {
                        @Override public void run() {
                            try { operation.run(); }
                            finally { completed.countDown(); await(release); }
                        }
                        @Override public boolean deliver(Runnable callback) {
                            try { return operation.deliver(callback); }
                            finally { delivered.countDown(); }
                        }
                    };
                }
                @Override public SessionOperation prepareLogout() { return block(auth.prepareLogout()); }
                @Override public SessionOperation preparePasswordChange(long id, char[] current, char[] next) {
                    var scope = auth.captureSession();
                    return block(new SessionOperation() {
                        @Override public void run() {
                            if (failPassword) throw new IllegalArgumentException("synthetic failure");
                            users.changeOwnPassword(scope, id, current, next);
                        }
                        @Override public boolean deliver(Runnable callback) { return scope.present(callback); }
                    });
                }
            }, callbacks::add, u -> callbacks.add("login success"), () -> callbacks.add("password success"),
                    callbacks::add, callbacks::add, null);
            Scene scene = new Scene((javafx.scene.Parent) result.node(), 1100, 700);
            ByxTheme.apply(scene);
            result.show(AuthScreens.CHANGE_PASSWORD, null, a);
            result.node().applyCss();
            if (!logout) {
                for (var node : result.node().lookupAll(".byx-field")) {
                    ByxField field = (ByxField) node;
                    field.input().setText(field.labelText().equals("Temporary password") ? PASSWORD : "replacement-password-2");
                }
            }
            result.node().lookupAll(".byx-btn").stream().filter(n -> n instanceof Button)
                    .map(n -> (Button) n).filter(b -> b.getText().equals(logout ? "Sign out" : "Change password"))
                    .findFirst().orElseThrow().fire();
            return result;
        });
        try {
            await(completed);
            auth.login("qa-b", PASSWORD.toCharArray());
            release.countDown();
            await(delivered);
            FxSupport.fx(() -> {
                assertTrue(callbacks.isEmpty(), "stale completion ran a real AuthScreens navigation callback");
                assertEquals(AuthScreens.CHANGE_PASSWORD, screens.route());
                assertEquals("qa-b", sessions.user().orElseThrow().user().username());
                assertTrue(auth.captureSession().isCurrent());
                assertTrue(gateway.hasSession());
                if (!logout) {
                    Button change = screens.node().lookupAll(".byx-btn").stream().filter(n -> n instanceof Button)
                            .map(n -> (Button) n).filter(b -> b.getText().equals("Change password")).findFirst().orElseThrow();
                    assertTrue(change.isDisabled(), "old completion changed controls on the preserved screen");
                }
                return null;
            });
        } finally {
            release.countDown();
            FxSupport.fx(() -> { screens.dispose(); return null; });
            auth.close();
        }
    }
}
