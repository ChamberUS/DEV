package panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.authview.LoginController;
import panel.authview.LoginController.State;
import panel.user.User;

/** Estados do login vêm do serviço real; tentativa descartada nunca muda estado, navega ou deixa sessão aberta. */
class LoginControllerTest {
    /** Executor manual: o teste decide quando o trabalho e o retorno à thread FX acontecem. */
    private static final class Manual implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    private final Manual worker = new Manual();
    private final Manual fx = new Manual();
    private final List<User> loggedIn = new ArrayList<>();
    private final AtomicInteger staleSessionsEnded = new AtomicInteger();
    private char[] lastPassword;

    private LoginController controller(LoginController.Authenticator auth) {
        return new LoginController((id, pw) -> {
            lastPassword = pw;
            return auth.login(id, pw);
        }, worker, fx, loggedIn::add, staleSessionsEnded::incrementAndGet);
    }

    private static User user() {
        java.time.Instant now = java.time.Instant.now();
        return new User(7, "qa", "qa@example.invalid", "x", panel.security.Role.ADMIN, panel.user.UserStatus.ACTIVE, null, true, true,
                false, now, now, now);
    }

    private void settle() {
        worker.runAll();
        fx.runAll();
    }

    @Test
    void failuresMapToTheirStatesAndZeroThePassword() {
        Object[][] cases = {
                {AuthService.Failure.INVALID_CREDENTIALS, State.INVALID},
                {AuthService.Failure.ACCOUNT_DISABLED, State.DISABLED},
                {AuthService.Failure.RATE_LIMITED, State.RATE_LIMITED}};
        for (Object[] c : cases) {
            LoginController l = controller((id, pw) -> {
                throw new AuthService.LoginException((AuthService.Failure) c[0], Duration.ofSeconds(42));
            });
            l.submit("qa", "secret-pass-1".toCharArray());
            assertEquals(State.LOADING, l.state());
            settle();
            assertEquals(c[1], l.state(), c[0].toString());
            assertArrayEquals(new char[13], lastPassword, "password zeroed after the attempt");
        }
    }

    @Test
    void unexpectedStoreFailureIsUnavailable() {
        LoginController l = controller((id, pw) -> {
            throw new IllegalStateException("database locked");
        });
        l.submit("qa", "x".toCharArray());
        settle();
        assertEquals(State.UNAVAILABLE, l.state());
    }

    @Test
    void rateLimitCarriesTheRealRetryAfter() {
        LoginController l = controller((id, pw) -> {
            throw new AuthService.LoginException(AuthService.Failure.RATE_LIMITED, Duration.ofSeconds(42));
        });
        l.submit("qa", "x".toCharArray());
        settle();
        assertEquals(Duration.ofSeconds(42), l.retryAfter());
        l.submit("qa", "y".toCharArray()); // bloqueado: ignorado
        assertTrue(worker.queue.isEmpty(), "no request while rate limited");
        l.reset(); // Esc
        assertEquals(State.RATE_LIMITED, l.state(), "Esc never bypasses the lockout");
        l.lockoutEnded();
        assertEquals(State.DEFAULT, l.state());
    }

    @Test
    void doubleSubmitWhileLoadingSendsOneRequest() {
        AtomicInteger calls = new AtomicInteger();
        LoginController l = controller((id, pw) -> {
            calls.incrementAndGet();
            return user();
        });
        l.submit("qa", "a".toCharArray());
        l.submit("qa", "b".toCharArray());
        settle();
        assertEquals(1, calls.get());
        assertEquals(State.SUCCESS, l.state());
        assertEquals(1, loggedIn.size(), "success hands over to the router once");
    }

    @Test
    void staleResultNeverNavigatesAndEndsAnySessionItOpened() {
        LoginController l = controller((id, pw) -> user());
        l.submit("qa", "a".toCharArray());
        worker.runAll();   // o serviço abriu a sessão...
        l.dispose();       // ...mas a tela foi trocada antes do retorno
        fx.runAll();
        assertTrue(loggedIn.isEmpty(), "stale success must not navigate");
        assertEquals(1, staleSessionsEnded.get(), "stale session is ended, not left open");
        assertEquals(State.LOADING, l.state(), "disposed controller keeps its last state");
    }

    @Test
    void resetDoesNotInterruptLoadingOrSuccess() {
        LoginController l = controller((id, pw) -> user());
        l.submit("qa", "a".toCharArray());
        l.reset();
        assertEquals(State.LOADING, l.state());
        settle();
        l.reset();
        assertEquals(State.SUCCESS, l.state());
    }
}
