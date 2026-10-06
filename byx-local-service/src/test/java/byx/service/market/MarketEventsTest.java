package byx.service.market;

import static byx.service.market.FakeMarket.aggTrade;
import static byx.service.market.FakeMarket.depth;
import static byx.service.market.FakeMarket.envelope;
import static byx.service.market.FakeMarket.kline;
import static byx.service.market.FakeMarket.markPrice;
import static byx.service.market.FakeMarket.ticker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarketEventsTest {
    private static String code(ThrowingRunnable r) {
        return assertThrows(MarketException.class, r::run).code;
    }

    interface ThrowingRunnable {
        void run() throws MarketException;
    }

    private static MarketEvents.Envelope env(String text) throws MarketException {
        return MarketEvents.envelope(text);
    }

    @Test
    void realShapedPayloadsParse() throws Exception {
        var d = MarketEvents.depth(env(depth(5, 9, 4, "[[\"2700.00\",\"1.5\"]]", "[[\"2700.01\",\"0\"]]")).data());
        assertEquals(9, d.lastId());
        assertEquals(4, d.prevId());
        assertEquals(1, d.bids().size());
        var t = MarketEvents.aggTrade(env(aggTrade(7, "2699.41", "0.157", false)).data());
        assertEquals(7, t.id());
        assertFalse(t.buyerIsMaker());
        assertEquals(2699.41, MarketEvents.markPrice(env(markPrice("2699.41", "2700.91")).data()).mark());
        assertEquals(-0.114, MarketEvents.ticker(env(ticker("-0.114", "2734", "2676", "1", "2")).data()).changePct());
        assertEquals(1791265920000L, MarketEvents.kline(env(kline(1791265920000L, "2699.4", "2699.41", "2699.4", "2699.4", false)).data()).openMs());
    }

    @Test
    void wrongSymbolIsRejectedEverywhere() {
        String bad = envelope("x", "{\"e\":\"aggTrade\",\"s\":\"BTCUSDT\",\"a\":1,\"p\":\"1\",\"q\":\"1\",\"T\":1,\"m\":true}");
        assertEquals("wrong_symbol", code(() -> MarketEvents.aggTrade(env(bad).data())));
        String badDepth = envelope("x", "{\"e\":\"depthUpdate\",\"s\":\"BTCUSDT\",\"U\":1,\"u\":2,\"pu\":0,\"b\":[],\"a\":[]}");
        assertEquals("wrong_symbol", code(() -> MarketEvents.depth(env(badDepth).data())));
        String missing = envelope("x", "{\"e\":\"depthUpdate\",\"U\":1,\"u\":2,\"pu\":0,\"b\":[],\"a\":[]}");
        assertEquals("wrong_symbol", code(() -> MarketEvents.depth(env(missing).data())));
    }

    @Test
    void wrongMarketTypeIsRejected() {
        // st = 2 é COIN-M; ps de outro par também não é o nosso mercado
        String cm = envelope("x", "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"st\":2,\"U\":1,\"u\":2,\"pu\":0,\"b\":[],\"a\":[]}");
        assertEquals("wrong_market_type", code(() -> MarketEvents.depth(env(cm).data())));
        String ps = envelope("x", "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"ps\":\"ETHUSD\",\"U\":1,\"u\":2,\"pu\":0,\"b\":[],\"a\":[]}");
        assertEquals("wrong_market_type", code(() -> MarketEvents.depth(env(ps).data())));
        String stText = envelope("x", "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"st\":\"1\",\"a\":1,\"p\":\"1\",\"q\":\"1\",\"T\":1,\"m\":true}");
        assertEquals("wrong_market_type", code(() -> MarketEvents.aggTrade(env(stText).data())));
    }

    @Test
    void malformedAndHostileJsonIsRejected() {
        for (String s : List.of("", "not json", "{", "[]", "null", "42", "{\"stream\":\"a\"}", "{\"stream\":1,\"data\":{}}", "{\"stream\":\"a\",\"data\":[]}",
                "{\"stream\":\"a\",\"data\":{}} trailing", "{\"stream\":\"a\",\"stream\":\"b\",\"data\":{}}",
                "{\"stream\":\"a\",\"data\":" + "[".repeat(50) + "]".repeat(50) + "}",
                "{\"stream\":\"" + "x".repeat(5000) + "\",\"data\":{}}")) {
            assertEquals("malformed", code(() -> env(s)), s.length() > 60 ? s.substring(0, 60) : s);
        }
    }

    @Test
    void invalidNumbersAndShapesAreRejected() {
        for (String body : List.of(
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":\"-1\",\"q\":\"1\",\"T\":1,\"m\":true}",
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":\"NaN\",\"q\":\"1\",\"T\":1,\"m\":true}",
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":\"1e400\",\"q\":\"1\",\"T\":1,\"m\":true}",
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":1,\"q\":\"1\",\"T\":1,\"m\":true}",
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":\"1\",\"q\":\"1\",\"T\":1}",
                "{\"e\":\"aggTrade\",\"s\":\"ETHUSDT\",\"a\":-5,\"p\":\"1\",\"q\":\"1\",\"T\":1,\"m\":true}")) {
            assertEquals("malformed", code(() -> MarketEvents.aggTrade(env(envelope("x", body)).data())), body);
        }
        for (String levels : List.of("[[\"1\"]]", "[[\"1\",\"2\",\"3\"]]", "[[1,2]]", "[[\"-1\",\"2\"]]", "[[\"1\",\"-2\"]]", "\"x\"", "[[\"abc\",\"1\"]]")) {
            String d = "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"U\":1,\"u\":2,\"pu\":0,\"b\":" + levels + ",\"a\":[]}";
            assertEquals("malformed", code(() -> MarketEvents.depth(env(envelope("x", d)).data())), levels);
        }
        String inverted = "{\"e\":\"depthUpdate\",\"s\":\"ETHUSDT\",\"U\":9,\"u\":2,\"pu\":0,\"b\":[],\"a\":[]}";
        assertEquals("malformed", code(() -> MarketEvents.depth(env(envelope("x", inverted)).data())));
    }

    @Test
    void unexpectedEventNameIsRejected() {
        String d = envelope("x", "{\"e\":\"forceOrder\",\"s\":\"ETHUSDT\",\"a\":1,\"p\":\"1\",\"q\":\"1\",\"T\":1,\"m\":true}");
        assertEquals("unexpected_event", code(() -> MarketEvents.aggTrade(env(d).data())));
    }

    @Test
    void restKlinesMustBeContiguousAndSane() throws Exception {
        long now = FakeMarket.currentMinute() + 30_000;
        assertEquals(120, MarketEvents.klines(FakeMarket.klineRows(120).getBytes(), now).size());
        assertTrue(MarketEvents.klines(FakeMarket.klineRows(3).getBytes(), now).get(2).closed() == false, "the in-progress candle is not closed");
        String gap = "[[60000,\"1\",\"2\",\"1\",\"2\",\"1\",119999],[240000,\"1\",\"2\",\"1\",\"2\",\"1\",299999]]";
        assertEquals("malformed", code(() -> MarketEvents.klines(gap.getBytes(), now)));
        String hl = "[[60000,\"1\",\"1\",\"2\",\"2\",\"1\",119999]]";
        assertEquals("malformed", code(() -> MarketEvents.klines(hl.getBytes(), now)));
        assertEquals("malformed", code(() -> MarketEvents.klines("[]".getBytes(), now)));
        assertEquals("malformed", code(() -> MarketEvents.klines("{}".getBytes(), now)));
        assertEquals("malformed", code(() -> MarketEvents.depthSnapshot("{\"lastUpdateId\":\"1\",\"bids\":[],\"asks\":[]}".getBytes())));
    }
}
