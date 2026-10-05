package panel.tradeview;

import java.time.Instant;
import java.util.Random;
import panel.model.DataSource;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Candle;
import panel.model.TraderSnapshot.Level;

/**
 * Fixtures ISOLADAS de QA e testes. Nunca entram no código de produção: o app real só mostra o que o provider
 * real entrega (nenhum feed, nenhuma conta). Cada fixture é um {@link TraderSnapshot} completo.
 */
final class DeskFixtures {
    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private DeskFixtures() {
    }

    /** O que o provider REAL entrega hoje (espelha ResearchModeTradingProvider). */
    static TraderSnapshot noFeed() {
        TraderSnapshot t = new TraderSnapshot();
        t.backendOnline = true;
        t.feed = "NOT_CONFIGURED";
        t.symbol = "ETHUSDT";
        t.market = "USD-M-FUTURES";
        t.strategy = "None approved";
        t.strategyStatus = "IN RESEARCH · 0 / 8 hypotheses completed";
        return t;
    }

    static TraderSnapshot waiting() {
        TraderSnapshot t = noFeed();
        t.feed = "WAITING";
        return t;
    }

    /** Feed conectado com book, trades e candles; sem conta. seed muda os valores. */
    static TraderSnapshot live(long seed) {
        TraderSnapshot t = noFeed();
        t.feed = "CONNECTED";
        t.feedUpdatedAt = NOW;
        Random r = new Random(seed);
        double p = 3100 + r.nextDouble() * 40;
        double c = p;
        for (int i = 0; i < 80; i++) {
            double o = c;
            c += (r.nextDouble() - 0.5) * 6;
            t.candles.add(new Candle(o, Math.max(o, c) + r.nextDouble() * 2, Math.min(o, c) - r.nextDouble() * 2, c));
        }
        t.price = c;
        t.change24hPct = 1.84;
        t.high24h = c + 31.2;
        t.low24h = c - 44.9;
        t.volume24h = 182_400.0;
        for (int i = 0; i < 12; i++) {
            t.asks.add(new Level(c + 0.05 * (i + 1), 0.2 + r.nextDouble() * 3));
            t.bids.add(new Level(c - 0.05 * i, 0.2 + r.nextDouble() * 3));
        }
        for (int i = 0; i < 12; i++) {
            t.tradeRows.add(new String[] {"12:00:" + String.format(java.util.Locale.US, "%02d", 59 - i), "ETHUSDT", i % 3 == 0 ? "SELL" : "BUY",
                    String.format(java.util.Locale.US, "%.3f", 0.01 + r.nextDouble()), String.format(java.util.Locale.US, "%,.2f", c + (r.nextDouble() - 0.5)), "0.02"});
        }
        return t;
    }

    static TraderSnapshot liveWithAccount(long seed) {
        TraderSnapshot t = live(seed);
        t.account = "QA-FIXTURE";
        t.balance = 10_000.0;
        t.equity = 10_212.4;
        t.dailyPnl = 85.2;
        t.exposure = 1_250.0;
        t.drawdown = -1.3;
        t.positions = 1;
        t.orders = 1;
        t.positionRows.add(new String[] {"ETHUSDT", "LONG", "0.400", "3,101.20", "3,120.55", "+7.74", "250.00", "5x", "2,498.10", "11:42:08"});
        t.orderRows.add(new String[] {"11:58:11", "ETHUSDT", "LIMIT", "BUY", "0.100", "3,090.00", "NEW"});
        t.signalRows.add(new String[] {"11:59:00", "ETHUSDT", "NEUTRAL", "Microstructure Alpha v1", "0.12"});
        t.activityRows.add(new String[] {"11:59:02", "INFO", "Monitoring ETHUSDT"});
        return t;
    }

    static TraderSnapshot stale(long seed) {
        TraderSnapshot t = liveWithAccount(seed);
        t.feed = "STALE";
        t.feedUpdatedAt = NOW.minusSeconds(95);
        return t;
    }

    static TraderSnapshot degraded(long seed) {
        TraderSnapshot t = live(seed);
        t.feed = "DEGRADED";
        return t;
    }

    static TraderSnapshot disconnected(long seed) {
        TraderSnapshot t = live(seed); // o snapshot ainda carrega o último valor; a UI não pode mostrá-lo como vivo
        t.feed = "OFFLINE";
        return t;
    }

    static TraderSnapshot error() {
        TraderSnapshot t = noFeed();
        t.feed = "ERROR";
        return t;
    }

    static TraderSnapshot mock() {
        TraderSnapshot t = live(7);
        t.source = DataSource.MOCK;
        t.feed = "MOCK";
        return t;
    }
}
