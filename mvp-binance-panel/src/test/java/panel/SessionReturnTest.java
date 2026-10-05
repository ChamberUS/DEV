package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;
import panel.authview.SessionReturn;

/** P3.11 resolveReturn: rota da expiração, nunca de animação; gated, removida ou de outro usuário → padrão. */
class SessionReturnTest {
    private static final Set<String> VIEWS = Set.of("t-desk", "t-byx", "t-wallet", "overview");

    private static String resolve(String route, long expiredUser, long newUser) {
        return SessionReturn.capture(route, expiredUser).resolve(newUser, VIEWS::contains, id -> !id.startsWith("t-"));
    }

    @Test
    void validRouteOfTheSameUserReturnsThere() {
        assertEquals("t-wallet", resolve("t-wallet", 7, 7));
    }

    @Test
    void gatedRouteReturnsToTheDefaultWorkspace() {
        assertEquals("t-desk", resolve("overview", 7, 7), "Research needs admin verification again");
    }

    @Test
    void removedRouteReturnsToTheDefaultWorkspace() {
        assertEquals("t-desk", resolve("t-gone", 7, 7));
    }

    @Test
    void anotherUserNeverInheritsTheRoute() {
        assertEquals("t-desk", resolve("t-wallet", 7, 8));
    }

    @Test
    void noRouteAtExpiryReturnsToTheDefault() {
        assertEquals("t-desk", resolve(null, 7, 7));
    }
}
