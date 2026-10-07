package panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import panel.auth.AuthService.LoginException;

/** L5: o texto digitado no login nunca vai para o log de auditoria, a tentativa continua registrada e o histórico antigo não é reescrito. */
class LoginAuditSafetyTest {
    private static final char[] PW = "correct-horse-1".toCharArray();
    private static final String CANARY = "CANARY-hunter2-Pass!";

    // O histórico LEGADO (mascaramento na leitura, nunca reescrito, somente leitura imutável) é provado em
    // panel.runtime.LegacyIsolationProductTest e panel.security.LegacyPanelDbTest: o produto não grava mais nesse banco.

    @Test
    void theLoginErrorNeverEchoesTheIdentifier() {
        AuthFixture f = AuthFixture.ready();
        f.seedAdmin();
        LoginException e = assertThrows(LoginException.class, () -> f.auth.login(CANARY, "x".toCharArray()));
        assertFalse((e.getMessage() + e.failure + e.retryAfter).contains("CANARY"));
    }
}
