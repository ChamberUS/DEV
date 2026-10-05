package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Contrato responsivo (handoff §3 / BYX_DESIGN_TOKENS.json#breakpoints): a informação muda, nada escala. */
class DeskModeTest {
    @Test
    void compactStandardExpandedMatchTheHandoffContract() {
        DeskMode c = DeskMode.COMPACT;
        assertEquals(330, c.marketColumn);
        assertEquals(0, c.contextColumn);
        assertEquals(3, c.bookLevels);
        assertEquals(3, c.tradeRows);
        assertEquals(214, c.blotterHeight);
        assertEquals(7, c.blotterColumns);
        assertTrue(c.tabbedContext());

        DeskMode s = DeskMode.STANDARD;
        assertEquals(350, s.marketColumn);
        assertEquals(6, s.bookLevels);
        assertEquals(4, s.tradeRows);
        assertEquals(240, s.blotterHeight);
        assertEquals(8, s.blotterColumns);

        DeskMode e = DeskMode.EXPANDED;
        assertEquals(330, e.marketColumn);
        assertEquals(350, e.contextColumn);
        assertEquals(10, e.bookLevels);
        assertEquals(9, e.tradeRows);
        assertEquals(300, e.blotterHeight);
        assertEquals(10, e.blotterColumns);
        assertTrue(e.twoColumns());
    }

    @Test
    void thresholdsFollowTheContentWidthOfTheReferenceWindows() {
        // janela − rail 68: 1280→1212, 1440→1372, 1600→1532, 1920→1852
        assertEquals(DeskMode.COMPACT, DeskMode.of(1212));
        assertEquals(DeskMode.COMPACT, DeskMode.of(1372));
        assertEquals(DeskMode.STANDARD, DeskMode.of(1532));
        assertEquals(DeskMode.EXPANDED, DeskMode.of(1852));
        assertEquals(DeskMode.COMPACT, DeskMode.of(0), "before the first layout the desk is compact, never expanded");
    }
}
