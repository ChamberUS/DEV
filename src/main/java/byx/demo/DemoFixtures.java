package byx.demo;
import panel.model.*;import java.time.*;
/** Generated, deterministic fictional values; never reads user data, captures or live feeds. */
public final class DemoFixtures {
 public static final Clock CLOCK=Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"),ZoneOffset.UTC);
 public static TraderSnapshot market(String symbol){if(!java.util.Set.of("ETHUSDT","BTCUSDT").contains(symbol))throw new IllegalArgumentException();TraderSnapshot s=new TraderSnapshot();s.source=DataSource.MOCK;s.mode="DEMO";s.trading="DISABLED";s.account="DEMO_USER";s.symbol=symbol;s.market="DADOS SIMULADOS";s.feed="MOCK";s.feedUpdatedAt=CLOCK.instant();s.backendOnline=false;s.bookState="MOCK";double base=symbol.equals("ETHUSDT")?2500:60000;s.price=base;s.change24hPct=1.25;s.high24h=base*1.03;s.low24h=base*.97;s.volume24h=1250000d;s.balance=10000d;s.equity=12500d;s.dailyPnl=125d;s.exposure=20d;s.drawdown=1d;s.positions=2;s.orders=0;s.botState="DEMO / SEM OPERAÇÕES REAIS";
 for(int i=0;i<120;i++){double o=base+(i-60)*base*.0002+Math.sin(i*.4)*base*.002;double c=o+Math.cos(i*.3)*base*.001;s.candles.add(new TraderSnapshot.Candle(o,Math.max(o,c)+base*.001,Math.min(o,c)-base*.001,c,CLOCK.millis()-(120-i)*60000L));}
 for(int i=1;i<=15;i++){s.asks.add(new TraderSnapshot.Level(base+i*.5,1+i*.1));s.bids.add(new TraderSnapshot.Level(base-i*.5,1+i*.1));s.marketTrades.add(new TraderSnapshot.MarketTrade(CLOCK.instant().minusSeconds(i),base+i*.1,.2+i*.05,i%2==0));}
 s.positionRows.add(new String[]{"ETHUSDT DEMO","LONG","0.5","2500","SIMULADO"});s.positionRows.add(new String[]{"BTCUSDT DEMO","LONG","0.1","60000","SIMULADO"});s.tradeRows.add(new String[]{"2026-01-15","ETHUSDT DEMO","SIMULADO","0.5","2500"});return s;}
}