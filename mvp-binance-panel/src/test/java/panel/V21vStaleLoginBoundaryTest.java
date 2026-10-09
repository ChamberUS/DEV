package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.auth.SessionManager;
import panel.authview.LoginController;
import panel.security.Role;

/** V2.1V security/lifecycle gate. Entirely synthetic: no IPC, keys, accounts or files outside this test. */
class V21vStaleLoginBoundaryTest {
    @Test void aDiscardedLoginCannotRevokeTheSessionOpenedByANewerLogin() throws Exception {
        FakeAuthority authority = new FakeAuthority(Clock.systemUTC());
        authority.add("old-qa", "old@example.invalid", "+5511999991234", "synthetic-pass-1", Role.USER, false);
        authority.add("new-qa", "new@example.invalid", "+5511999994321", "synthetic-pass-2", Role.USER, false);
        SessionManager sessions = new SessionManager();
        AuthService auth = new AuthService(authority, sessions, Clock.systemUTC());
        Queue<Runnable> worker = new ArrayDeque<>(), ui = new ArrayDeque<>();
        AtomicInteger navigations = new AtomicInteger();
        LoginController old = FxSupport.fx(() -> new LoginController(auth::beginLogin, worker::add, ui::add,
                ignored -> fail("discarded login navigated")));
        FxSupport.fx(() -> { old.submit("old-qa", "synthetic-pass-1".toCharArray()); return null; });
        worker.remove().run(); // old Service reply is ready, but its FX callback has not run yet
        Runnable staleReply = ui.remove();
        FxSupport.fx(() -> { old.dispose(); return null; });
        LoginController fresh = FxSupport.fx(() -> new LoginController(auth::beginLogin, worker::add, ui::add,
                ignored -> navigations.incrementAndGet()));
        FxSupport.fx(() -> { fresh.submit("new-qa", "synthetic-pass-2".toCharArray()); return null; });
        worker.remove().run();
        FxSupport.fx(() -> { ui.remove().run(); return null; });
        assertEquals("new-qa", sessions.user().orElseThrow().user().username());
        assertEquals(1, navigations.get());
        FxSupport.fx(() -> { staleReply.run(); return null; });
        assertTrue(sessions.user().isPresent(), "stale cleanup revoked the NEWER login; cleanup must target its own session");
        assertEquals("new-qa", sessions.user().orElseThrow().user().username());
        FxSupport.fx(() -> { fresh.dispose(); return null; });
        auth.close();
    }
}
