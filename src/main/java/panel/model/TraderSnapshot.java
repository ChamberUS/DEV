package panel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Visão operacional para o Trader Workspace. Campos nulos = N/A. Não contém detalhes de pesquisa. */
public class TraderSnapshot {
    public record Level(double price, double size) {
    }

    /** openTimeMs = abertura real do candle (epoch ms); 0 = desconhecido (dados que não trazem horário). */
    public record Candle(double open, double high, double low, double close, long openTimeMs) {
        public Candle(double open, double high, double low, double close) {
            this(open, high, low, close, 0L);
        }
    }

    /** Negociação pública do mercado (aggTrade). buy = o lado agressor comprou. Não é fill de conta. */
    public record MarketTrade(java.time.Instant time, double price, double size, boolean buy) {
    }

    public DataSource source = DataSource.REAL;
    public boolean loading;

    public String mode = "RESEARCH";
    public String trading = "DISABLED";
    public String account;
    public String botState = "RESEARCH / MONITORING";
    public String strategy;
    public String strategyStatus;
    public String signal;

    public boolean backendOnline;
    public String recorder;
    public String symbol;
    public String market;
    public String feed;
    /** Instante da última atualização do feed; null = não informado (nenhum provider real o preenche hoje). */
    public java.time.Instant feedUpdatedAt;
    public Double price;
    public Double change24hPct;
    public Double high24h;
    public Double low24h;
    /** Volume da janela móvel de 24 h (não o dia UTC). Do feed público: em USDT (quote). */
    public Double volume24h;
    /** Mark price (distinto do último preço negociado). */
    public Double markPrice;
    public Double indexPrice;
    /** Estado do book local do serviço (SYNCING / LIVE / RESYNCING); null = não informado. */
    public String bookState;
    /** Negociações públicas recentes, mais novas primeiro. Separadas dos fills da conta (tradeRows). */
    public List<MarketTrade> marketTrades = new ArrayList<>();
    public List<Level> asks = new ArrayList<>();
    public List<Level> bids = new ArrayList<>();
    public List<Candle> candles = new ArrayList<>();

    public Double balance;
    public Double equity;
    public Double dailyPnl;
    public Double exposure;
    public Double drawdown;
    public int positions;
    public int orders;
    public List<Double> equityCurve = new ArrayList<>();
    public final Map<String, String> performance = new LinkedHashMap<>();

    public List<String[]> positionRows = new ArrayList<>();
    public List<String[]> orderRows = new ArrayList<>();
    public List<String[]> tradeRows = new ArrayList<>();
    public List<String[]> signalRows = new ArrayList<>();
    public List<String[]> activityRows = new ArrayList<>();
    public List<String[]> strategyRows = new ArrayList<>();
}
