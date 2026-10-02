package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import panel.adapter.MockTradingProvider;
import panel.adapter.ResearchModeTradingProvider;
import panel.model.DataSource;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;

class TradingProviderTest {
    @Test
    void realModeIsResearchOnlyWithNoFakeData() {
        TraderSnapshot t = new ResearchModeTradingProvider().load(new Snapshot());
        assertEquals(DataSource.REAL, t.source);
        assertEquals("DISABLED", t.trading);
        assertEquals("RESEARCH", t.mode);
        assertEquals(0, t.positions);
        assertEquals(0, t.orders);
        assertNull(t.account);
        assertNull(t.equity);
        assertNull(t.price);
        assertTrue(t.candles.isEmpty());
    }

    @Test
    void mockDataIsAlwaysFlagged() {
        assertEquals(DataSource.MOCK, new MockTradingProvider().load(new Snapshot()).source);
    }
}
