package panel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import panel.nav.Navigator;

/** Fundação de navegação: um pedido assíncrono nunca pode sobrescrever uma navegação mais nova. */
class NavigatorTest {
    @Test void latestRequestWins() {
        Navigator n = new Navigator();
        Navigator.Ticket first = n.begin("hypotheses");
        Navigator.Ticket second = n.begin("overview");
        assertFalse(n.isCurrent(first));
        assertTrue(n.isCurrent(second));
        assertEquals("overview", n.consumePending().target());
    }

    @Test void displayingAnotherViewCancelsThePendingRequest() {
        Navigator n = new Navigator();
        n.begin("hypotheses");
        n.displayed("t-desk");
        assertNull(n.consumePending(), "a verificação assíncrona não pode abrir Research depois que o usuário saiu");
        assertEquals("t-desk", n.current());
    }

    @Test void cancelAndResetClearEverything() {
        Navigator n = new Navigator();
        n.begin("jobs");
        n.cancelPending();
        assertNull(n.pending());
        n.displayed("t-byx");
        n.reset();
        assertNull(n.current());
        assertNull(n.pending());
    }

    @Test void consumeIsOneShot() {
        Navigator n = new Navigator();
        n.begin("logs");
        assertNotNull(n.consumePending());
        assertNull(n.consumePending());
    }
}
