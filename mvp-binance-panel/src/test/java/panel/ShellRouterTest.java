package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import panel.nav.Navigator;
import panel.shell.ShellRouter;
import panel.shell.ShellRouter.Decision;

/** Contrato de navegação (P3.1) no roteador do shell: último pedido vence, callback velho não faz nada. */
class ShellRouterTest {
    /** Gate de teste: "r-*" exige verificação assíncrona; "x-*" é negado. */
    private static final class FakeGate implements ShellRouter.Gate {
        final Map<String, Navigator.Ticket> waiting = new HashMap<>();

        @Override
        public Decision evaluate(String target, Navigator.Ticket ticket) {
            if (target.startsWith("x-")) {
                return Decision.DENY;
            }
            if (target.startsWith("r-")) {
                waiting.put(target, ticket);
                return Decision.PENDING;
            }
            return Decision.ALLOW;
        }
    }

    private final FakeGate gate = new FakeGate();
    private final List<String> displayed = new ArrayList<>();
    private final List<String> routeChanges = new ArrayList<>();
    private final ShellRouter router = new ShellRouter(new Navigator(), gate, displayed::add);

    {
        router.routeProperty().addListener((o, a, b) -> routeChanges.add(b));
    }

    @Test
    void rapidAbcEndsInC() {
        router.request("t-desk");
        router.request("t-markets");
        router.request("t-byx");
        assertEquals("t-byx", router.route());
        assertEquals(List.of("t-desk", "t-markets", "t-byx"), displayed);
    }

    @Test
    void rapidAbaNeverPassesThroughBAgain() {
        router.request("t-desk");
        router.request("r-overview"); // B pendente (verificação)
        router.request("t-desk");     // A de novo antes de B concluir
        assertFalse(router.complete(gate.waiting.get("r-overview"), "r-overview"), "late B must not apply");
        assertEquals("t-desk", router.route());
        assertFalse(displayed.contains("r-overview"));
    }

    @Test
    void latestPendingRequestWins() {
        router.request("t-desk");
        router.request("r-overview");
        router.request("r-capture");
        assertFalse(router.complete(gate.waiting.get("r-overview"), "r-overview"));
        assertTrue(router.complete(gate.waiting.get("r-capture"), "r-capture"));
        assertEquals("r-capture", router.route());
    }

    @Test
    void cancelledCallbackDoesNothing() {
        router.request("t-desk");
        router.request("r-overview");
        Navigator.Ticket t = gate.waiting.get("r-overview");
        router.cancelPending(); // usuário cancelou a verificação
        assertFalse(router.complete(t, "r-overview"));
        assertEquals("t-desk", router.route());
        assertNull(router.pending());
    }

    @Test
    void deniedRequestKeepsRouteAndClearsPending() {
        router.request("t-desk");
        router.request("x-admin");
        assertEquals("t-desk", router.route());
        assertNull(router.pending());
        assertEquals(List.of("t-desk"), displayed);
    }

    @Test
    void twentyRoundTripsChangeRouteExactlyOncePerHop() {
        router.request("t-desk");
        for (int i = 0; i < 20; i++) {
            router.request("t-byx");
            router.request("t-desk");
        }
        assertEquals("t-desk", router.route());
        assertEquals(41, routeChanges.size());
    }

    @Test
    void reRequestingCurrentRouteIsNotARouteChange() {
        router.request("t-desk");
        router.request("t-desk");
        assertEquals(List.of("t-desk"), routeChanges);
        assertEquals(2, displayed.size(), "the view is re-applied idempotently");
    }

    @Test
    void reRequestingCurrentRouteCancelsAPendingOne() {
        router.request("t-desk");
        router.request("r-overview");
        router.request("t-desk");
        assertNull(router.pending());
    }

    @Test
    void resetClearsRouteAndPending() {
        router.request("t-desk");
        router.request("r-overview");
        router.reset();
        assertNull(router.route());
        assertNull(router.pending());
        assertFalse(router.complete(gate.waiting.get("r-overview"), "r-overview"));
    }
}
