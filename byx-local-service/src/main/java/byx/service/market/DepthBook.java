package byx.service.market;

import byx.service.market.MarketEvents.Depth;
import byx.service.market.MarketEvents.DepthSnapshot;
import byx.service.market.MarketEvents.Level;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Book local do USDⓈ-M Futures, seguindo o procedimento oficial "How to manage a local order book correctly":
 *  1. abrir o stream de depth e guardar os eventos (buffer);
 *  2. buscar o snapshot REST (lastUpdateId = L);
 *  3. descartar eventos com u < L;
 *  4. o primeiro evento aplicado precisa ter U <= L e u >= L;
 *  5. daí em diante cada evento precisa ter pu == u do anterior; qualquer quebra invalida o book e exige novo snapshot.
 * Quantidade é absoluta por nível; 0 remove o nível (remover nível inexistente é normal). Nunca interpola evento perdido, nunca
 * inventa id de sequência e só entrega {@link #top} quando o estado é LIVE.
 */
final class DepthBook {
    enum State { NO_SNAPSHOT, SYNCING, LIVE }

    enum Verdict { OK, NEED_RESYNC, NEED_NEWER_SNAPSHOT }

    static final int MAX_BUFFERED_EVENTS = 1_500;
    static final int MAX_LEVELS_PER_SIDE = 1_500;

    private final TreeMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>();
    private final ArrayDeque<Depth> buffer = new ArrayDeque<>();
    private State state = State.NO_SNAPSHOT;
    private long snapshotId;
    private long lastU = -1;
    private MarketReason why = MarketReason.BOOK_START;
    /** Diagnóstico numérico da última invalidação por lógica de sequência (só U/u/pu e contagens; nunca texto da rede). */
    private String detail = "";
    private long generation;

    State state() {
        return state;
    }

    /** Código legado da última invalidação (contratos existentes). */
    String reason() {
        return why.code;
    }

    MarketReason reasonEnum() {
        return why;
    }

    String detail() {
        return detail;
    }

    /** Geração do book: sobe a cada invalidação (opaca; só para correlacionar eventos). */
    long generation() {
        return generation;
    }

    long lastUpdateId() {
        return lastU;
    }

    /** Invalida tudo e volta a guardar eventos até o próximo snapshot. */
    void invalidate(String legacyCode) {
        invalidate(MarketReason.fromCode(legacyCode));
    }

    void invalidate(MarketReason reason) {
        bids.clear();
        asks.clear();
        buffer.clear();
        state = State.NO_SNAPSHOT;
        lastU = -1;
        why = reason;
        detail = "";
        generation++;
    }

    /** Evento do stream. NO_SNAPSHOT: só guarda (com teto). SYNCING/LIVE: aplica com a regra de continuidade. */
    Verdict onEvent(Depth e) {
        switch (state) {
            case NO_SNAPSHOT -> {
                if (buffer.size() >= MAX_BUFFERED_EVENTS) {
                    return resync(MarketReason.DEPTH_BUFFER_OVERFLOW, "buffered=" + buffer.size());
                }
                buffer.addLast(e);
                return Verdict.OK;
            }
            case SYNCING -> {
                return first(e);
            }
            default -> {
                return next(e);
            }
        }
    }

    /** Snapshot REST. Alinha com o buffer; pode pedir snapshot mais novo (o stream já passou do snapshot). */
    Verdict onSnapshot(DepthSnapshot s) {
        if (state != State.NO_SNAPSHOT) {
            return Verdict.OK; // snapshot atrasado de uma tentativa anterior
        }
        bids.clear();
        asks.clear();
        for (Level l : s.bids()) {
            put(bids, l);
        }
        for (Level l : s.asks()) {
            put(asks, l);
        }
        if (crossed()) {
            return resync(MarketReason.DEPTH_CROSSED_SNAPSHOT, "");
        }
        snapshotId = s.lastUpdateId();
        state = State.SYNCING;
        lastU = -1;
        while (!buffer.isEmpty()) {
            Depth e = buffer.pollFirst();
            Verdict v = state == State.SYNCING ? first(e) : next(e);
            if (v != Verdict.OK) {
                return v;
            }
        }
        return Verdict.OK;
    }

    private Verdict first(Depth e) {
        if (e.lastId() < snapshotId) {
            return Verdict.OK; // evento anterior ao snapshot: descartado
        }
        if (e.firstId() > snapshotId) {
            // o stream já passou do snapshot sem cobri-lo: sequência não alinhável, precisa de snapshot mais novo
            return resyncNewer(MarketReason.SNAPSHOT_BEHIND_STREAM, "U=" + e.firstId() + " u=" + e.lastId() + " snap=" + snapshotId);
        }
        apply(e); // U <= L <= u
        state = State.LIVE;
        return crossed() ? resync(MarketReason.DEPTH_CROSSED_BOOK, "") : Verdict.OK;
    }

    private Verdict next(Depth e) {
        if (e.prevId() != lastU) {
            return resync(MarketReason.DEPTH_SEQUENCE_GAP, "U=" + e.firstId() + " u=" + e.lastId() + " pu=" + e.prevId() + " expected_pu=" + lastU);
        }
        apply(e);
        return crossed() ? resync(MarketReason.DEPTH_CROSSED_BOOK, "") : Verdict.OK;
    }

    private void apply(Depth e) {
        for (Level l : e.bids()) {
            put(bids, l);
        }
        for (Level l : e.asks()) {
            put(asks, l);
        }
        trim(bids);
        trim(asks);
        lastU = e.lastId();
    }

    private static void put(TreeMap<BigDecimal, BigDecimal> side, Level l) {
        if (l.qty().signum() == 0) {
            side.remove(l.price());
        } else {
            side.put(l.price(), l.qty());
        }
    }

    private static void trim(TreeMap<BigDecimal, BigDecimal> side) {
        while (side.size() > MAX_LEVELS_PER_SIDE) {
            side.pollLastEntry(); // o nível mais distante do topo
        }
    }

    private boolean crossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey().compareTo(asks.firstKey()) >= 0;
    }

    private Verdict resync(MarketReason reason, String numbers) {
        invalidate(reason);
        detail = numbers;
        return Verdict.NEED_RESYNC;
    }

    private Verdict resyncNewer(MarketReason reason, String numbers) {
        invalidate(reason);
        detail = numbers;
        return Verdict.NEED_NEWER_SNAPSHOT;
    }

    /** Melhores n níveis por lado ([preço, quantidade]); vazio se o book não está LIVE. */
    List<double[]> topBids(int n) {
        return state == State.LIVE ? top(bids, n) : List.of();
    }

    List<double[]> topAsks(int n) {
        return state == State.LIVE ? top(asks, n) : List.of();
    }

    private static List<double[]> top(TreeMap<BigDecimal, BigDecimal> side, int n) {
        List<double[]> out = new java.util.ArrayList<>(n);
        for (var en : side.entrySet()) {
            if (out.size() >= n) {
                break;
            }
            out.add(new double[] {en.getKey().doubleValue(), en.getValue().doubleValue()});
        }
        return out;
    }

    int sizeBids() {
        return bids.size();
    }

    int sizeAsks() {
        return asks.size();
    }
}
