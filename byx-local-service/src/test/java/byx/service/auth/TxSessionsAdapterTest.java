package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.tx.TxPorts.TxSession;
import byx.service.tx.TxProduction;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O adaptador de sessão de transação usa a MESMA validação da autoridade: peer, validade, conta e logout valem para transações. */
class TxSessionsAdapterTest {
    private static final long PEER = 11L;

    @Test
    void resolvesOnlyValidSessionsBoundToTheirPeerAndDropsThemOnLogout() throws Exception {
        try (AuthFixture f = new AuthFixture()) {
            String token = AuthFixture.token(f.loginUser(PEER));
            var sessions = TxProduction.sessions(f.auth);
            Optional<TxSession> s = sessions.resolve(PEER, token);
            assertTrue(s.isPresent());
            assertEquals(f.userId, s.get().accountId());
            assertFalse(s.get().admin());
            assertFalse(s.get().recentMfa());
            assertFalse(s.get().elevated());
            assertEquals(64, s.get().sessionId().length(), "opaque hash, never the token");
            assertFalse(s.get().toString().contains(token));
            assertTrue(sessions.resolve(PEER + 1, token).isEmpty(), "another peer cannot use the token");
            assertTrue(sessions.resolve(PEER, "x".repeat(43)).isEmpty());
            f.auth.logout(PEER, token);
            assertTrue(sessions.resolve(PEER, token).isEmpty(), "logout ends transaction use of the session");
        }
    }

    @Test
    void mfaAndAdminStateComeFromTheAuthorityNotFromTheCaller() throws Exception {
        try (AuthFixture f = new AuthFixture()) {
            String token = AuthFixture.token(f.loginAdmin(PEER));
            var sessions = TxProduction.sessions(f.auth);
            TxSession before = sessions.resolve(PEER, token).orElseThrow();
            assertTrue(before.admin());
            assertFalse(before.recentMfa());
            f.mfa(PEER, token, f.adminId);
            assertTrue(sessions.resolve(PEER, token).orElseThrow().recentMfa());
            f.clock.advance(Duration.ofHours(2));
            assertTrue(sessions.resolve(PEER, token).isEmpty() || !sessions.resolve(PEER, token).get().recentMfa(), "MFA is temporary");
        }
    }

    @Test
    void disablingTheAccountRevokesTransactionAccessImmediately() throws Exception {
        try (AuthFixture f = new AuthFixture()) {
            String token = AuthFixture.token(f.loginUser(PEER));
            var sessions = TxProduction.sessions(f.auth);
            assertTrue(sessions.resolve(PEER, token).isPresent());
            f.admin.setEnabled(f.userId, false);
            assertTrue(sessions.resolve(PEER, token).isEmpty());
        }
    }
}
