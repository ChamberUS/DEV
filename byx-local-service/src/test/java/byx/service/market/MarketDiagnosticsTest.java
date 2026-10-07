package byx.service.market;

import static byx.service.market.FakeMarket.aggTrade;
import static byx.service.market.FakeMarket.await;
import static byx.service.market.FakeMarket.depth;
import static byx.service.market.FakeMarket.driveToLive;
import static byx.service.market.FakeMarket.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.market.MarketView.BookState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V2.1J: diagnóstico do feed público. Toda rejeição vira (classe de stream, razão tipada) sem copiar conteúdo; parsing/esquema nunca se confunde com lógica de sequência do
 * book; o frame de uma conexão antiga nunca é aceito pela geração nova; o book só é publicado quando consistente. Fixtures sintéticas; nenhuma rede.
 */
class MarketDiagnosticsTest {
    private static final String CANARY = "CANARY-PAYLOAD-SHOULD-NEVER-BE-LOGGED";
    private FakeMarket.Ws ws;
    private FakeMarket.Http http;
    private MarketFeed feed;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() {
        Log.redirect(logs::add);
        ws = new FakeMarket.Ws();
        http = new FakeMarket.Http();
    }

    @AfterEach
    void down() {
        if (feed != null) {
            feed.close();
        }
        Log.redirect(null);
    }

    private void live() {
        feed = new MarketFeed(ws, http, FakeMarket.fast());
        feed.acquire();
        driveToLive(feed, ws);
    }

    private List<String> lines(String event) {
        synchronized (logs) {
            return logs.stream().filter(l -> l.contains(" byx-local-service " + event + " ")).toList();
        }
    }

    private static MarketReason reasonOf(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(MarketException.class, call).reason;
    }

    private static com.fasterxml.jackson.databind.JsonNode data(String stream, String body) throws MarketException {
        return MarketEvents.envelope(envelope(stream, body)).data();
    }

    // ---- taxonomia no parser (fixtures sintéticas) ---------------------------------------------------------------------------------------

    @Test
    void parserRejectionsMapToTheClosedTaxonomyAndKeepTheLegacyCode() {
        assertEquals(MarketReason.JSON_INVALID, reasonOf(() -> MarketEvents.envelope("{\"stream\":")));
        assertEquals(MarketReason.JSON_INVALID, reasonOf(() -> MarketEvents.envelope("not json at all")));
        assertEquals(MarketReason.SCHEMA_INVALID, reasonOf(() -> MarketEvents.envelope("{\"stream\":\"x\"}")), "missing data");
        assertEquals(MarketReason.SCHEMA_INVALID, reasonOf(() -> MarketEvents.envelope("[1,2]")));
        assertEquals(MarketReason.MISSING_FIELD, reasonOf(() -> MarketEvents.aggTrade(data("x", "{\"s\":\"ETHUSDT\"}"))), "no event type");
        assertEquals(MarketReason.TYPE_MISMATCH, reasonOf(() -> MarketEvents.aggTrade(data("x", "{\"e\":5}"))));
        assertEquals(MarketReason.UNKNOWN_EVENT_TYPE, reasonOf(() -> MarketEvents.aggTrade(data("x", "{\"e\":\"trade\",\"s\":\"ETHUSDT\"}"))), "wrong event type");
        assertEquals(MarketReason.WRONG_SYMBOL, reasonOf(() -> MarketEvents.aggTrade(data("x", "{\"e\":\"aggTrade\",\"s\":\"BTCUSDT\"}"))));
        assertEquals(MarketReason.WRONG_MARKET_TYPE, reasonOf(() -> MarketEvents.aggTrade(data("x", "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"st\":2}"))));
        String base = "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"T\":1,\"m\":false,";
        assertEquals(MarketReason.TYPE_MISMATCH, reasonOf(() -> MarketEvents.aggTrade(data("x", base + "\"p\":2700.5,\"q\":\"1\"}"))), "bad numeric type (number, not string)");
        assertEquals(MarketReason.NUMBER_INVALID, reasonOf(() -> MarketEvents.aggTrade(data("x", base + "\"p\":\"abc\",\"q\":\"1\"}"))));
        assertEquals(MarketReason.NON_FINITE_NUMBER, reasonOf(() -> MarketEvents.aggTrade(data("x", base + "\"p\":\"1e999\",\"q\":\"1\"}"))));
        assertEquals(MarketReason.VALUE_OUT_OF_RANGE, reasonOf(() -> MarketEvents.aggTrade(data("x", base + "\"p\":\"-1\",\"q\":\"1\"}"))));
        assertEquals(MarketReason.MISSING_FIELD, reasonOf(() -> MarketEvents.aggTrade(data("x", base + "\"p\":\"1\"}"))), "q missing");
        String dep = "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",";
        assertEquals(MarketReason.MISSING_FIELD, reasonOf(() -> MarketEvents.depth(data("x", dep + "\"U\":1,\"u\":2,\"b\":[],\"a\":[]}"))), "pu missing");
        assertEquals(MarketReason.INVARIANT_VIOLATED, reasonOf(() -> MarketEvents.depth(data("x", dep + "\"U\":9,\"u\":2,\"pu\":1,\"b\":[],\"a\":[]}"))), "U > u");
        assertEquals(MarketReason.TYPE_MISMATCH, reasonOf(() -> MarketEvents.depth(data("x", dep + "\"U\":1,\"u\":2,\"pu\":0,\"b\":\"no\",\"a\":[]}"))));
        assertEquals(MarketReason.TYPE_MISMATCH, reasonOf(() -> MarketEvents.depth(data("x", dep + "\"U\":1,\"u\":2,\"pu\":0,\"b\":[[1,2]],\"a\":[]}"))));
        assertEquals(MarketReason.VALUE_OUT_OF_RANGE, reasonOf(() -> MarketEvents.depth(data("x", dep + "\"U\":1,\"u\":2,\"pu\":0,\"b\":[[\"0\",\"1\"]],\"a\":[]}"))));
        assertEquals(MarketReason.MISSING_FIELD, reasonOf(() -> MarketEvents.kline(data("x", "{\"e\":\"kline\",\"s\":\"ETHUSDT\"}"))), "k missing");
        assertEquals(MarketReason.SCHEMA_INVALID, reasonOf(() -> MarketEvents.kline(data("x", "{\"e\":\"kline\",\"s\":\"ETHUSDT\",\"k\":{\"i\":\"5m\",\"s\":\"ETHUSDT\"}}"))), "wrong interval");
        assertEquals(MarketReason.JSON_INVALID, reasonOf(() -> MarketEvents.depthSnapshot("{broken".getBytes())));
        assertEquals(MarketReason.SCHEMA_INVALID, reasonOf(() -> MarketEvents.klines("[]".getBytes(), 0)));
        // o código legado grosso continua o mesmo para os contratos existentes
        assertEquals("malformed", assertThrows(MarketException.class, () -> MarketEvents.envelope("x")).code);
        assertEquals("wrong_symbol", assertThrows(MarketException.class, () -> MarketEvents.aggTrade(data("x", "{\"e\":\"aggTrade\",\"s\":\"BTCUSDT\"}"))).code);
        assertEquals("unexpected_event", assertThrows(MarketException.class, () -> MarketEvents.aggTrade(data("x", "{\"e\":\"x\",\"s\":\"ETHUSDT\"}"))).code);
    }

    @Test
    void everyReasonHasADistinctNameAndOnlyFixedLegacyCodes() {
        var names = new java.util.HashSet<String>();
        for (MarketReason r : MarketReason.values()) {
            assertTrue(names.add(r.name()), r.name());
            assertTrue(r.code.matches("[a-z_]{1,32}"), "fixed short code: " + r.code);
        }
        assertEquals(MarketStream.DEPTH, MarketStream.of("ethusdt@depth@100ms"));
        assertEquals(MarketStream.KLINE, MarketStream.of("ethusdt@kline_1m"));
        assertEquals(MarketStream.UNKNOWN_MARKET, MarketStream.of("btcusdt@aggTrade"), "an unknown stream name is never copied");
        assertEquals(MarketStream.UNKNOWN_MARKET, MarketStream.of(null));
    }

    // ---- regra de sequência (Binance USDⓈ-M, procedimento oficial) NÃO relaxada --------------------------------------------------------

    private static MarketEvents.Depth ev(long first, long last, long prev, String bid, String ask) {
        return new MarketEvents.Depth(first, last, prev, bid == null ? List.of() : List.of(new MarketEvents.Level(new java.math.BigDecimal(bid), java.math.BigDecimal.ONE)),
                ask == null ? List.of() : List.of(new MarketEvents.Level(new java.math.BigDecimal(ask), java.math.BigDecimal.ONE)));
    }

    private static MarketEvents.DepthSnapshot snap(long id) {
        return new MarketEvents.DepthSnapshot(id, List.of(new MarketEvents.Level(new java.math.BigDecimal("100"), java.math.BigDecimal.ONE)),
                List.of(new MarketEvents.Level(new java.math.BigDecimal("101"), java.math.BigDecimal.ONE)));
    }

    @Test
    void depthSequenceRulesAreTheOfficialOnesAndFailuresCarryTypedReasonsWithNumbers() {
        // descarta u < L; primeiro evento precisa de U <= L <= u; depois pu == u anterior
        DepthBook b = new DepthBook();
        b.onEvent(ev(1, 99, 0, null, null)); // u < L: descartado
        b.onEvent(ev(100, 105, 99, "100", null)); // U <= L <= u
        assertEquals(DepthBook.Verdict.OK, b.onSnapshot(snap(100)));
        assertEquals(DepthBook.State.LIVE, b.state());
        assertEquals(DepthBook.Verdict.NEED_RESYNC, b.onEvent(ev(120, 130, 115, null, null)));
        assertEquals(MarketReason.DEPTH_SEQUENCE_GAP, b.reasonEnum());
        assertEquals("sequence_gap", b.reason());
        assertEquals("U=120 u=130 pu=115 expected_pu=105", b.detail(), "only ids, nothing from the payload");
        assertEquals(DepthBook.State.NO_SNAPSHOT, b.state());
        // o stream já passou do snapshot: NÃO alinha (U > L), pede snapshot mais novo; regra "spot" (U == L+1) NÃO é aceita nos Futures
        DepthBook c = new DepthBook();
        c.onEvent(ev(101, 110, 100, null, null));
        assertEquals(DepthBook.Verdict.NEED_NEWER_SNAPSHOT, c.onSnapshot(snap(100)));
        assertEquals(MarketReason.SNAPSHOT_BEHIND_STREAM, c.reasonEnum());
        assertEquals("U=101 u=110 snap=100", c.detail());
        // book cruzado
        DepthBook d = new DepthBook();
        d.onSnapshot(snap(100));
        d.onEvent(ev(100, 101, 99, "102", null));
        assertEquals(MarketReason.DEPTH_CROSSED_BOOK, d.reasonEnum());
        assertEquals(DepthBook.State.NO_SNAPSHOT, d.state());
        assertTrue(d.topBids(5).isEmpty() && d.topAsks(5).isEmpty(), "a crossed/invalidated book is never exposed");
        // snapshot cruzado
        DepthBook e = new DepthBook();
        assertEquals(DepthBook.Verdict.NEED_RESYNC, e.onSnapshot(new MarketEvents.DepthSnapshot(5, List.of(new MarketEvents.Level(new java.math.BigDecimal("102"), java.math.BigDecimal.ONE)),
                List.of(new MarketEvents.Level(new java.math.BigDecimal("101"), java.math.BigDecimal.ONE)))));
        assertEquals(MarketReason.DEPTH_CROSSED_SNAPSHOT, e.reasonEnum());
        // overflow do buffer
        DepthBook f = new DepthBook();
        for (int i = 0; i < DepthBook.MAX_BUFFERED_EVENTS; i++) f.onEvent(ev(i, i, i == 0 ? 0 : i - 1, null, null));
        assertEquals(DepthBook.Verdict.NEED_RESYNC, f.onEvent(ev(9_999, 9_999, 9_998, null, null)));
        assertEquals(MarketReason.DEPTH_BUFFER_OVERFLOW, f.reasonEnum());
        // geração sobe a cada invalidação
        long g0 = f.generation();
        f.invalidate(MarketReason.BOOK_RECONNECT);
        assertEquals(g0 + 1, f.generation());
    }

    // ---- logs do feed: rejeição de parsing ≠ lógica de sequência -----------------------------------------------------------------------

    @Test
    void marketConnectionRejectionsLogStreamClassAndReasonWithoutAnyPayload() {
        live();
        ws.push(Allowlist.WS_MARKET, "{\"stream\":\"" + CANARY);
        ws.push(Allowlist.WS_MARKET, envelope("ethusdt@kline_1m", "{\"e\":\"kline\",\"s\":\"ETHUSDT\",\"x\":\"" + CANARY + "\"}"));
        ws.push(Allowlist.WS_MARKET, envelope("ethusdt@aggTrade", "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":2,\"T\":1,\"m\":false,\"p\":2700.5,\"q\":\"1\",\"c\":\"" + CANARY + "\"}"));
        ws.push(Allowlist.WS_MARKET, envelope("btcusdt@aggTrade", "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"c\":\"" + CANARY + "\"}"));
        await(() -> lines("market_rejected").size() >= 4, 3_000, "four rejections");
        List<String> r = lines("market_rejected");
        assertTrue(r.stream().anyMatch(l -> l.contains("reason=JSON_INVALID stream=UNKNOWN_MARKET conn=market")), r.toString());
        assertTrue(r.stream().anyMatch(l -> l.contains("reason=MISSING_FIELD stream=KLINE conn=market")), r.toString());
        assertTrue(r.stream().anyMatch(l -> l.contains("reason=TYPE_MISMATCH stream=AGG_TRADE conn=market")), r.toString());
        assertTrue(r.stream().anyMatch(l -> l.contains("reason=UNEXPECTED_STREAM stream=UNKNOWN_MARKET conn=market")), "an unknown stream name is classified, never copied: " + r);
        assertTrue(r.stream().allMatch(l -> l.contains("code=") && l.contains(" gen=") && l.contains(" bytes=")), r.toString());
        assertTrue(r.stream().noneMatch(l -> l.contains(" bookgen=")), "the market connection has no book");
        assertTrue(logs.stream().noneMatch(l -> l.contains(CANARY) || l.contains("btcusdt")), "no payload or foreign stream text in any log line");
        await(() -> feed.view().rejected() == 4, 3_000, "rejected counter published");
        assertTrue(lines("market_book_resync").isEmpty(), "market-stream rejections never touch the book");
    }

    @Test
    void aMalformedDepthFrameIsARejectionPlusARejectedFrameResyncNeverASequenceGap() {
        live();
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(300, "2701.00", "2701.01"));
        ws.push(Allowlist.WS_PUBLIC, envelope("ethusdt@depth@100ms", "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"U\":106,\"u\":110,\"b\":[],\"a\":[],\"x\":\"" + CANARY + "\"}")); // pu ausente
        await(() -> !lines("market_book_resync").isEmpty(), 3_000, "resync event");
        String rejected = lines("market_rejected").get(0);
        assertTrue(rejected.contains("reason=MISSING_FIELD stream=DEPTH conn=public") && rejected.contains(" bookgen="), rejected);
        String resync = lines("market_book_resync").get(0);
        assertTrue(resync.contains("code=rejected_event reason=REJECTED_FRAME stream=DEPTH conn=public"), resync);
        assertFalse(resync.contains("SEQUENCE_GAP"), "a parsing failure is not logged as a sequence gap");
        assertTrue(logs.stream().noneMatch(l -> l.contains(CANARY)));
    }

    @Test
    void aSequenceGapIsAResyncEventWithOnlyIdsAndNeverARejection() {
        live();
        long rejectedBefore = lines("market_rejected").size();
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(400, "2701.00", "2701.01"));
        ws.push(Allowlist.WS_PUBLIC, depth(106, 110, 105, "[]", "[]"));
        ws.push(Allowlist.WS_PUBLIC, depth(120, 130, 115, "[]", "[]")); // pu 115 != 110
        await(() -> !lines("market_book_resync").isEmpty(), 3_000, "resync event");
        String resync = lines("market_book_resync").get(0);
        assertTrue(resync.contains("code=sequence_gap reason=DEPTH_SEQUENCE_GAP stream=DEPTH conn=public"), resync);
        assertTrue(resync.contains("U=120 u=130 pu=115 expected_pu=110"), resync);
        assertEquals(rejectedBefore, lines("market_rejected").size(), "a sequence gap is not a parse rejection");
        assertEquals(0, feed.view().rejected());
    }

    @Test
    void snapshotBehindTheStreamIsClassifiedAsSuchWithTheIdsInvolved() {
        feed = new MarketFeed(ws, http, FakeMarket.fast());
        CountDownLatch pushed = new CountDownLatch(1);
        http.depth = () -> {
            try { pushed.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return FakeMarket.ok(FakeMarket.depthSnapshot(100, "2700.00", "2700.01"));
        };
        feed.acquire();
        await(() -> ws.current.containsKey(Allowlist.WS_PUBLIC), 2_000, "public connection");
        ws.push(Allowlist.WS_PUBLIC, depth(150, 160, 149, "[]", "[]")); // o stream já passou do snapshot (L = 100)
        FakeMarket.await(() -> true, 50, "settle");
        pushed.countDown();
        await(() -> lines("market_book_resync").stream().anyMatch(l -> l.contains("reason=SNAPSHOT_BEHIND_STREAM")), 3_000, "snapshot behind stream");
        assertTrue(lines("market_book_resync").stream().anyMatch(l -> l.contains("U=150 u=160 snap=100")), lines("market_book_resync").toString());
    }

    @Test
    void aCrossedBookIsNeverPublishedAndIsLoggedAsABookStateEvent() {
        live();
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(500, "2701.00", "2701.01"));
        ws.push(Allowlist.WS_PUBLIC, depth(106, 110, 105, "[[\"2800.00\",\"1\"]]", "[]")); // bid acima do melhor ask
        await(() -> lines("market_book_resync").stream().anyMatch(l -> l.contains("reason=DEPTH_CROSSED_BOOK")), 3_000, "crossed book");
        await(() -> feed.view().bids().isEmpty() && feed.view().asks().isEmpty() && feed.view().book() != BookState.LIVE, 3_000, "view republished without the crossed book");
        assertTrue(lines("market_rejected").isEmpty());
    }

    @Test
    void unplannedConnectionLossIsLoggedWithConnectionGenerationAndCloseCodeOnly() {
        live();
        ws.current.get(Allowlist.WS_MARKET).onClosed(1006);
        await(() -> !lines("market_connection_lost").isEmpty(), 3_000, "connection lost");
        String l = lines("market_connection_lost").get(0);
        assertTrue(l.contains("code=closed reason=CONNECTION_CLOSED conn=market gen=1 close=1006 status=-1 open_s="), l);
    }

    // ---- geração de conexão: frame de uma conexão antiga nunca é aceito pela nova -----------------------------------------------------

    @Test
    void aFrameFromAnOldConnectionGenerationIsIgnoredAfterReconnect() {
        live();
        WsTransport.Listener old = ws.current.get(Allowlist.WS_PUBLIC);
        old.onClosed(1006);
        await(() -> ws.count(Allowlist.WS_PUBLIC) == 2, 3_000, "reconnect (new generation)");
        assertTrue(ws.current.get(Allowlist.WS_PUBLIC) != old);
        long rejected = feed.view().rejected();
        int logsBefore = logs.size();
        old.onText("{\"stream\":\"" + CANARY); // lixo da conexão antiga
        old.onText(depth(900, 910, 899, "[[\"1\",\"1\"]]", "[]")); // evento válido da conexão antiga: gap de sequência se fosse aceito
        FakeMarket.await(() -> true, 50, "settle");
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertEquals(rejected, feed.view().rejected(), "stale frames are dropped before parsing");
        assertTrue(logs.subList(logsBefore, logs.size()).stream().noneMatch(l -> l.contains("market_rejected") || l.contains("market_book_resync") || l.contains(CANARY)),
                "no rejection, no resync, no payload from a stale generation");
    }

    // ---- LIVE só com book consistente ---------------------------------------------------------------------------------------------------

    @Test
    void liveBookIsPublishedOnlyWhenConsistentAndRecoversAfterEachFailureClass() {
        live();
        assertEquals(BookState.LIVE, feed.view().book());
        assertFalse(feed.view().bids().isEmpty());
        http.depth = () -> FakeMarket.ok(FakeMarket.depthSnapshot(600, "2710.00", "2710.01"));
        ws.push(Allowlist.WS_PUBLIC, depth(120, 130, 115, "[]", "[]")); // gap
        await(() -> feed.view().book() == BookState.RESYNCING, 3_000, "resyncing");
        assertTrue(feed.view().bids().isEmpty() && feed.view().asks().isEmpty());
        ws.push(Allowlist.WS_PUBLIC, depth(590, 610, 589, "[[\"2710.00\",\"7\"]]", "[]")); // cobre o novo snapshot (L = 600)
        await(() -> feed.view().book() == BookState.LIVE, 3_000, "LIVE again");
        assertEquals(2710.0, feed.view().bids().get(0)[0], "the new, aligned book");
        assertTrue(feed.view().bids().get(0)[0] < feed.view().asks().get(0)[0], "never crossed");
    }

    // ---- guardas de código ---------------------------------------------------------------------------------------------------------------

    private static List<java.nio.file.Path> marketSources() throws Exception {
        try (var w = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/byx/service/market"))) {
            return w.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noExceptionIsConstructedWithoutBeingThrownAndNoLogLineCarriesPayloadText() throws Exception {
        var orphan = java.util.regex.Pattern.compile("(?m)^\\s*new MarketException\\(");
        var logCall = java.util.regex.Pattern.compile("Log\\.event\\([^;]*;", java.util.regex.Pattern.DOTALL);
        for (var f : marketSources()) {
            String text = java.nio.file.Files.readString(f);
            assertFalse(orphan.matcher(text).find(), "a MarketException built but not thrown silently disables a validation: " + f.getFileName());
            var m = logCall.matcher(text);
            while (m.find()) {
                String call = m.group();
                // a rede só entra em log por tamanho (bytes) ou por enum/identificador local; nunca o texto da mensagem, do corpo ou do stream
                assertFalse(call.matches("(?s).*\\b(text|message|env\\.stream\\(\\)|resp\\.body\\(\\)(?!\\.length)|getMessage\\(\\)).*") && !call.contains("getBytes(java.nio.charset.StandardCharsets.UTF_8).length"),
                        "payload-bearing log call in " + f.getFileName() + ": " + call);
            }
        }
    }
}

