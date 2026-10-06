package byx.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.market.DepthBook.State;
import byx.service.market.DepthBook.Verdict;
import byx.service.market.MarketEvents.Depth;
import byx.service.market.MarketEvents.DepthSnapshot;
import byx.service.market.MarketEvents.Level;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O procedimento oficial do local order book, passo a passo, e a recusa de qualquer continuidade quebrada. */
class DepthBookTest {
    private static Level l(String p, String q) {
        return new Level(new BigDecimal(p), new BigDecimal(q));
    }

    private static Depth ev(long first, long last, long prev, List<Level> bids, List<Level> asks) {
        return new Depth(first, last, prev, bids, asks);
    }

    private static DepthSnapshot snap(long id) {
        return new DepthSnapshot(id, List.of(l("100", "5"), l("99", "4")), List.of(l("101", "3"), l("102", "2")));
    }

    @Test
    void buffersThenAlignsOnSnapshotDroppingOldEvents() {
        DepthBook b = new DepthBook();
        assertEquals(Verdict.OK, b.onEvent(ev(90, 99, 80, List.of(l("100", "9")), List.of()))); // u < L → descartado
        assertEquals(Verdict.OK, b.onEvent(ev(95, 105, 99, List.of(l("100", "6")), List.of(l("101", "0")))));
        assertEquals(State.NO_SNAPSHOT, b.state());
        assertTrue(b.topBids(5).isEmpty(), "nothing is exposed before the book is aligned");
        assertEquals(Verdict.OK, b.onSnapshot(snap(100)));
        assertEquals(State.LIVE, b.state());
        assertEquals(6.0, b.topBids(1).get(0)[1], "the dropped event did not apply, the aligning one did");
        assertEquals(102.0, b.topAsks(1).get(0)[0], "qty 0 removes the level");
        assertEquals(105, b.lastUpdateId());
    }

    @Test
    void firstEventAfterSnapshotMustCoverLastUpdateId() {
        DepthBook b = new DepthBook();
        assertEquals(Verdict.OK, b.onSnapshot(snap(100)));
        assertEquals(State.SYNCING, b.state());
        assertEquals(Verdict.OK, b.onEvent(ev(50, 99, 40, List.of(), List.of()))); // u < L: ignorado
        assertEquals(State.SYNCING, b.state());
        assertEquals(Verdict.OK, b.onEvent(ev(100, 100, 99, List.of(l("100", "7")), List.of()))); // U <= L <= u
        assertEquals(State.LIVE, b.state());
    }

    @Test
    void streamAheadOfSnapshotNeedsANewerSnapshot() {
        DepthBook b = new DepthBook();
        b.onEvent(ev(150, 160, 149, List.of(), List.of())); // U > L
        assertEquals(Verdict.NEED_NEWER_SNAPSHOT, b.onSnapshot(snap(100)));
        assertEquals(State.NO_SNAPSHOT, b.state());
        assertTrue(b.topBids(5).isEmpty() && b.topAsks(5).isEmpty());
    }

    @Test
    void sequenceGapInvalidatesAndNeverShowsTheBookAsLive() {
        DepthBook b = new DepthBook();
        b.onSnapshot(snap(100));
        assertEquals(Verdict.OK, b.onEvent(ev(98, 110, 90, List.of(), List.of())));
        assertEquals(Verdict.OK, b.onEvent(ev(111, 120, 110, List.of(l("100", "1")), List.of())));
        assertEquals(State.LIVE, b.state());
        Verdict v = b.onEvent(ev(125, 130, 121, List.of(l("100", "99")), List.of())); // pu != u anterior: evento perdido
        assertEquals(Verdict.NEED_RESYNC, v);
        assertEquals(State.NO_SNAPSHOT, b.state());
        assertEquals("sequence_gap", b.reason());
        assertTrue(b.topBids(5).isEmpty() && b.topAsks(5).isEmpty(), "no stale or interpolated book after a gap");
        assertEquals(0, b.sizeBids());
        // depois do gap só um snapshot novo reconstrói; o evento do gap não foi aplicado
        b.onEvent(ev(131, 140, 130, List.of(), List.of()));
        assertEquals(Verdict.OK, b.onSnapshot(snap(135)));
        assertEquals(State.LIVE, b.state());
    }

    @Test
    void removingAMissingLevelIsNormalAndCrossedBookResyncs() {
        DepthBook b = new DepthBook();
        b.onSnapshot(snap(100));
        assertEquals(Verdict.OK, b.onEvent(ev(100, 101, 99, List.of(l("50", "0")), List.of(l("500", "0")))));
        assertEquals(Verdict.NEED_RESYNC, b.onEvent(ev(102, 103, 101, List.of(l("101.5", "1")), List.of())));
        assertEquals("crossed_book", b.reason());
        assertEquals(State.NO_SNAPSHOT, b.state());
    }

    @Test
    void bufferIsBounded() {
        DepthBook b = new DepthBook();
        Verdict last = Verdict.OK;
        for (int i = 0; i <= DepthBook.MAX_BUFFERED_EVENTS + 1; i++) {
            last = b.onEvent(ev(i, i, i - 1, List.of(), List.of()));
            if (last != Verdict.OK) {
                break;
            }
        }
        assertEquals(Verdict.NEED_RESYNC, last);
        assertEquals("buffer_overflow", b.reason());
    }

    @Test
    void sidesAreBoundedAndOrderedBestFirst() {
        DepthBook b = new DepthBook();
        List<Level> bids = new ArrayList<>();
        List<Level> asks = new ArrayList<>();
        for (int i = 0; i < 1_400; i++) {
            bids.add(l(String.valueOf(1000 - i), "1"));
            asks.add(l(String.valueOf(1001 + i), "1"));
        }
        b.onSnapshot(new DepthSnapshot(10, bids, asks));
        List<Level> more = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            more.add(l(String.valueOf(100 - i / 10.0 - 0.001 * i), "1"));
        }
        assertEquals(Verdict.OK, b.onEvent(ev(10, 11, 9, more, List.of())));
        assertTrue(b.sizeBids() <= DepthBook.MAX_LEVELS_PER_SIDE);
        assertEquals(1000.0, b.topBids(1).get(0)[0]);
        assertEquals(1001.0, b.topAsks(1).get(0)[0]);
        assertEquals(20, b.topBids(20).size());
    }
}
