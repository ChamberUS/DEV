package panel.localservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** O cliente da autoridade: o painel não decide nada, o token só vive em memória, e o produto normal não o referencia. */
class AuthorityClientTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    void theTokenOnlyLivesInMemoryAndNeverInToString() {
        AuthorityClient c = new AuthorityClient(Path.of("/nonexistent-home"));
        c.adoptToken(TOKEN);
        assertTrue(c.hasSession());
        assertFalse(c.toString().contains(TOKEN));
        assertFalse(c.sessionStatus().toString().contains(TOKEN));
        c.adoptToken("not-a-token");
        assertFalse(c.hasSession(), "a malformed token is never adopted");
        c.adoptToken(TOKEN);
        c.close();
        assertFalse(c.hasSession(), "close forgets the token");
        assertEquals("AUTH_REQUIRED", c.sessionStatus().code(), "no token: nothing is even sent");
    }

    @Test
    void withoutAServiceEveryCallFailsClosedWithAFixedCode() {
        AuthorityClient c = new AuthorityClient(Path.of("/nonexistent-home"));
        var r = c.login("x", "password-123456".toCharArray());
        assertFalse(r.ok());
        assertEquals("not_started", r.code());
        assertFalse(c.hasSession());
    }

    @Test
    void theClientNeverHasAnyWayToSendRoleAdminMfaOrUserId() throws IOException {
        String src = Files.readString(Path.of("src/main/java/panel/localservice/AuthorityClient.java"));
        for (String forbidden : new String[] {"\"role\"", "\"userId\"", "\"admin\"", "\"mfa\"", "\"adminElevated\"", "\"expectedRole\"", "auth.setRole", "auth.execute", "auth.debugLogin", "auth.impersonate"}) {
            assertFalse(src.contains(forbidden), "the client has no way to send " + forbidden);
        }
    }
}
