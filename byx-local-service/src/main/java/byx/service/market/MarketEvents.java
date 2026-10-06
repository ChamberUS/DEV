package byx.service.market;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Validação estrita das mensagens da Binance. Tudo que não bate com o contrato (símbolo, tipo de mercado, evento, forma, número)
 * vira {@link MarketException} com código fixo; nenhum pedaço da mensagem recusada é copiado para erro ou log.
 */
final class MarketEvents {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).maxNumberLength(48).build()).build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    /** st = tipo de símbolo no payload atual: 1 = USDⓈ-M, 2 = COIN-M. */
    static final int ST_USD_M = 1;
    static final int MAX_LEVELS_PER_EVENT = 2000;

    private MarketEvents() {
    }

    record Level(BigDecimal price, BigDecimal qty) {
    }

    record Depth(long firstId, long lastId, long prevId, List<Level> bids, List<Level> asks) {
    }

    record DepthSnapshot(long lastUpdateId, List<Level> bids, List<Level> asks) {
    }

    record AggTrade(long id, double price, double qty, long timeMs, boolean buyerIsMaker) {
    }

    record Mark(double mark, double index, double fundingRate, long nextFundingMs) {
    }

    record Ticker(double changePct, double high, double low, double volumeBase, double volumeQuote, double last) {
    }

    record Kline(long openMs, double open, double high, double low, double close, double volume, boolean closed) {
    }

    /** Mensagem do stream combinado: nome do stream + payload. */
    record Envelope(String stream, JsonNode data) {
    }

    static Envelope envelope(String text) throws MarketException {
        JsonNode root = parse(text);
        JsonNode stream = root.get("stream");
        JsonNode data = root.get("data");
        if (!root.isObject() || stream == null || !stream.isTextual() || data == null || !data.isObject()) {
            throw new MarketException("malformed");
        }
        return new Envelope(stream.asText(), data);
    }

    static JsonNode parse(String text) throws MarketException {
        try {
            JsonNode n = JSON.readTree(text);
            if (n == null || !n.isObject() && !n.isArray()) {
                throw new MarketException("malformed");
            }
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MarketException("malformed");
        }
    }

    /** Símbolo e tipo de mercado do payload: o símbolo é obrigatório e é ETHUSDT; st e ps, quando presentes, precisam ser USDⓈ-M / ETHUSDT. */
    static void requireMarket(JsonNode d) throws MarketException {
        JsonNode s = d.get("s");
        if (s == null || !s.isTextual() || !Allowlist.SYMBOL.equals(s.asText())) {
            throw new MarketException("wrong_symbol");
        }
        JsonNode st = d.get("st");
        if (st != null && (!st.isInt() || st.asInt() != ST_USD_M)) {
            throw new MarketException("wrong_market_type");
        }
        JsonNode ps = d.get("ps");
        if (ps != null && (!ps.isTextual() || !Allowlist.SYMBOL.equals(ps.asText()))) {
            throw new MarketException("wrong_market_type");
        }
    }

    static String event(JsonNode d) throws MarketException {
        JsonNode e = d.get("e");
        if (e == null || !e.isTextual()) {
            throw new MarketException("malformed");
        }
        return e.asText();
    }

    static Depth depth(JsonNode d) throws MarketException {
        if (!"depthUpdate".equals(event(d))) {
            throw new MarketException("unexpected_event");
        }
        requireMarket(d);
        long u = longField(d, "u");
        long first = longField(d, "U");
        long pu = longField(d, "pu");
        if (first > u || pu < 0) {
            throw new MarketException("malformed");
        }
        return new Depth(first, u, pu, levels(d.get("b")), levels(d.get("a")));
    }

    static DepthSnapshot depthSnapshot(byte[] body) throws MarketException {
        JsonNode d;
        try {
            d = JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new MarketException("malformed");
        }
        if (d == null || !d.isObject()) {
            throw new MarketException("malformed");
        }
        return new DepthSnapshot(longField(d, "lastUpdateId"), levels(d.get("bids")), levels(d.get("asks")));
    }

    static List<Level> levels(JsonNode arr) throws MarketException {
        if (arr == null || !arr.isArray() || arr.size() > MAX_LEVELS_PER_EVENT) {
            throw new MarketException("malformed");
        }
        List<Level> out = new ArrayList<>(arr.size());
        for (JsonNode l : arr) {
            if (!l.isArray() || l.size() != 2 || !l.get(0).isTextual() || !l.get(1).isTextual()) {
                throw new MarketException("malformed");
            }
            BigDecimal p = decimal(l.get(0).asText());
            BigDecimal q = decimal(l.get(1).asText());
            if (p.signum() <= 0 || q.signum() < 0) {
                throw new MarketException("malformed");
            }
            out.add(new Level(p, q));
        }
        return out;
    }

    static AggTrade aggTrade(JsonNode d) throws MarketException {
        if (!"aggTrade".equals(event(d))) {
            throw new MarketException("unexpected_event");
        }
        requireMarket(d);
        double p = positive(d, "p");
        double q = positive(d, "q");
        JsonNode m = d.get("m");
        if (m == null || !m.isBoolean()) {
            throw new MarketException("malformed");
        }
        return new AggTrade(longField(d, "a"), p, q, longField(d, "T"), m.asBoolean());
    }

    static Mark markPrice(JsonNode d) throws MarketException {
        if (!"markPriceUpdate".equals(event(d))) {
            throw new MarketException("unexpected_event");
        }
        requireMarket(d);
        return new Mark(positive(d, "p"), positive(d, "i"), number(d, "r"), longField(d, "T"));
    }

    static Ticker ticker(JsonNode d) throws MarketException {
        if (!"24hrTicker".equals(event(d))) {
            throw new MarketException("unexpected_event");
        }
        requireMarket(d);
        return new Ticker(number(d, "P"), positive(d, "h"), positive(d, "l"), number(d, "v"), number(d, "q"), positive(d, "c"));
    }

    static Kline kline(JsonNode d) throws MarketException {
        if (!"kline".equals(event(d))) {
            throw new MarketException("unexpected_event");
        }
        requireMarket(d);
        JsonNode k = d.get("k");
        if (k == null || !k.isObject() || !"1m".equals(text(k, "i")) || !Allowlist.SYMBOL.equals(text(k, "s"))) {
            throw new MarketException("malformed");
        }
        JsonNode x = k.get("x");
        if (x == null || !x.isBoolean()) {
            throw new MarketException("malformed");
        }
        return candle(longField(k, "t"), positive(k, "o"), positive(k, "h"), positive(k, "l"), positive(k, "c"), number(k, "v"), x.asBoolean());
    }

    /** Resposta REST de klines: linhas [openTime, o, h, l, c, v, closeTime, ...]. */
    static List<Kline> klines(byte[] body, long nowMs) throws MarketException {
        JsonNode a;
        try {
            a = JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new MarketException("malformed");
        }
        if (a == null || !a.isArray() || a.size() == 0 || a.size() > Allowlist.KLINE_LIMIT) {
            throw new MarketException("malformed");
        }
        List<Kline> out = new ArrayList<>(a.size());
        long prev = -1;
        for (JsonNode r : a) {
            if (!r.isArray() || r.size() < 7 || !r.get(0).isIntegralNumber() || !r.get(6).isIntegralNumber()) {
                throw new MarketException("malformed");
            }
            long t = r.get(0).asLong();
            if (t <= prev || (prev >= 0 && t - prev != 60_000)) {
                throw new MarketException("malformed"); // candles fora de ordem ou com buraco: nunca "remendar"
            }
            prev = t;
            out.add(candle(t, price(r, 1), price(r, 2), price(r, 3), price(r, 4), price(r, 5), r.get(6).asLong() < nowMs));
        }
        return out;
    }

    private static Kline candle(long t, double o, double h, double l, double c, double v, boolean closed) throws MarketException {
        if (t <= 0 || h < l || h < Math.max(o, c) || l > Math.min(o, c) || v < 0) {
            throw new MarketException("malformed");
        }
        return new Kline(t, o, h, l, c, v, closed);
    }

    private static double price(JsonNode row, int i) throws MarketException {
        JsonNode n = row.get(i);
        if (n == null || !n.isTextual()) {
            throw new MarketException("malformed");
        }
        return finite(decimal(n.asText()).doubleValue());
    }

    private static BigDecimal decimal(String s) throws MarketException {
        if (s.length() > 32) {
            throw new MarketException("malformed");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new MarketException("malformed");
        }
    }

    private static double finite(double v) throws MarketException {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new MarketException("malformed");
        }
        return v;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static long longField(JsonNode d, String f) throws MarketException {
        JsonNode v = d.get(f);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0) {
            throw new MarketException("malformed");
        }
        return v.asLong();
    }

    private static double number(JsonNode d, String f) throws MarketException {
        JsonNode v = d.get(f);
        if (v == null || !v.isTextual()) {
            throw new MarketException("malformed");
        }
        return finite(decimal(v.asText()).doubleValue());
    }

    private static double positive(JsonNode d, String f) throws MarketException {
        double v = number(d, f);
        if (v <= 0) {
            throw new MarketException("malformed");
        }
        return v;
    }
}
