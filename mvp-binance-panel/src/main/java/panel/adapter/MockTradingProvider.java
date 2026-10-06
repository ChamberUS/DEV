package panel.adapter;

import java.util.Random;
import panel.model.DataSource;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Candle;
import panel.model.TraderSnapshot.Level;

/** Valores FICTÍCIOS para desenvolver o visual. A UI sinaliza DATA SOURCE: MOCK em todo lugar. */
public class MockTradingProvider implements TradingProvider {
    @Override
    public TraderSnapshot load(Snapshot research) {
        TraderSnapshot t = new TraderSnapshot();
        t.source = DataSource.MOCK;
        t.symbol = "BTCUSDT";
        t.market = "PERP";
        Random r = new Random(7);
        double p = 60_000;
        for (int i = 0; i < 90; i++) {
            double o = p;
            p += (r.nextDouble() - 0.48) * 120;
            t.candles.add(new Candle(o, Math.max(o, p) + r.nextDouble() * 50, Math.min(o, p) - r.nextDouble() * 50, p));
        }
        t.feed = "MOCK";
        t.price = p;
        t.change24hPct = 1.84;
        t.high24h = p + 900;
        t.low24h = p - 1100;
        t.volume24h = 182_400.0;
        for (int i = 0; i < 12; i++) {
            t.asks.add(new Level(p + 0.1 * (i + 1), 0.2 + r.nextDouble() * 3));
            t.bids.add(new Level(p - 0.1 * i, 0.2 + r.nextDouble() * 3));
        }
        t.account = "MOCK-ACCOUNT";
        t.balance = 10_000.0;
        t.equity = 10_212.4;
        t.dailyPnl = 85.2;
        t.exposure = 0.0;
        t.drawdown = -1.3;
        t.strategy = "Microstructure Alpha v1 (mock)";
        t.strategyStatus = "MOCK";
        t.signal = "NEUTRAL";
        double e = 10_000;
        for (int i = 0; i < 60; i++) {
            e += (r.nextDouble() - 0.45) * 40;
            t.equityCurve.add(e);
        }
        t.performance.put("Total return", "+2.12%");
        t.performance.put("Sharpe", "1.4");
        t.performance.put("Win rate", "54%");
        t.performance.put("Trades", "312");
        t.performance.put("Max drawdown", "-3.1%");
        t.positionRows.add(new String[] {"BTCUSDT PERP", "LONG", "0.010", String.format("%,.2f", p - 40), String.format("%,.2f", p), "+0.40"});
        t.positions = 1;
        t.orderRows.add(new String[] {"14:02:11", "BTCUSDT", "LIMIT", "BUY", "0.010", String.format("%,.2f", p - 15), "NEW"});
        t.orders = 1;
        t.marketTrades.add(new TraderSnapshot.MarketTrade(java.time.Instant.now(), p - 40, 0.010, true)); // fictício, como todo o modo Mock
        t.tradeRows.add(new String[] {"14:01:58", "BTCUSDT", "BUY", "0.010", String.format("%,.2f", p - 40), "0.16"});
        t.signalRows.add(new String[] {"14:02:00", "BTCUSDT", "NEUTRAL", "Microstructure Alpha v1", "0.12"});
        t.activityRows.add(new String[] {"14:02:11", "INFO", "Mock: placed limit order"});
        t.strategyRows.add(new String[] {"Microstructure Alpha", "v1", "MOCK", "Mock"});
        return t;
    }
}
