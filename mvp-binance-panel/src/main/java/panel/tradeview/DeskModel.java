package panel.tradeview;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import panel.design.StatusState;
import panel.model.DataSource;
import panel.model.TraderSnapshot;
import panel.model.TraderSnapshot.Level;
import panel.util.Fmt;

/**
 * Estado do Trading Desk derivado do {@link TraderSnapshot}. Sem JavaFX e sem relógio próprio: o instante atual
 * entra como argumento. Nada aqui inventa valor: campo ausente vira N/A (ou "—" quando a célula é de tempo),
 * com uma razão discreta. Sem feed não há preço, candle, nível de book nem trade.
 */
public final class DeskModel {
    /** Idade a partir da qual um feed "vivo" com horário conhecido passa a STALE. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(15);
    public static final String WAITING_TEXT = "Waiting for market data";

    private DeskModel() {
    }

    /** Estado do feed. NO_FEED = nada configurado (esperado nesta build); WAITING = conectando. */
    public enum Feed {
        NO_FEED(StatusState.UNAVAILABLE, true, WAITING_TEXT, "Not connected"),
        WAITING(StatusState.CONNECTING, false, WAITING_TEXT, "Connecting"),
        LIVE(StatusState.OPERATIONAL, false, "Live", "Connected"),
        MOCK(StatusState.DEGRADED, false, "Mock feed", "Mock (fictional)"),
        STALE(StatusState.DEGRADED, false, "STALE", "Stale"),
        DEGRADED(StatusState.DEGRADED, false, "Degraded", "Degraded"),
        RECONNECTING(StatusState.RECONNECTING, false, "Reconnecting", "Reconnecting"),
        DISCONNECTED(StatusState.UNAVAILABLE, false, "Disconnected", "Disconnected"),
        UNAVAILABLE(StatusState.UNAVAILABLE, false, "Unavailable", "Unavailable"),
        ERROR(StatusState.UNAVAILABLE, false, "Feed error", "Error");

        public final StatusState dot;
        /** Ponto neutro: nada deveria estar conectado nesta build (nunca após uma queda). */
        public final boolean expectedUnavailable;
        public final String label;
        public final String freshnessText;

        Feed(StatusState dot, boolean expected, String label, String freshnessText) {
            this.dot = dot;
            this.expectedUnavailable = expected;
            this.label = label;
            this.freshnessText = freshnessText;
        }

        /** Há valores de mercado para desenhar (último valor conhecido incluído). */
        public boolean showsMarketData() {
            return this == LIVE || this == MOCK || this == DEGRADED || this == STALE || this == RECONNECTING;
        }

        /** Valores mantidos mas que não podem parecer vivos. */
        public boolean looksStale() {
            return this == STALE || this == RECONNECTING;
        }

        /** O feed está aguardando dados (esqueleto), em oposição a não configurado/caído. */
        public boolean waiting() {
            return this == NO_FEED || this == WAITING;
        }
    }

    private static final Set<String> NOT_CONFIGURED = Set.of("NOT_CONFIGURED", "NOT CONFIGURED");
    private static final Set<String> WAITING_FEEDS = Set.of("WAITING", "CONNECTING", "AWAITING");
    private static final Set<String> LIVE_FEEDS = Set.of("CONNECTED", "LIVE", "OPEN", "OK", "ONLINE", "STREAMING");
    private static final Set<String> DOWN_FEEDS = Set.of("OFFLINE", "DISCONNECTED", "CLOSED");

    public static Feed feed(TraderSnapshot t, Instant now) {
        String f = t.feed == null ? null : t.feed.trim().toUpperCase(Locale.ROOT);
        if (f == null || f.isEmpty()) {
            return Feed.WAITING;
        }
        if (NOT_CONFIGURED.contains(f)) {
            return Feed.NO_FEED;
        }
        if (WAITING_FEEDS.contains(f)) {
            return Feed.WAITING;
        }
        if (f.equals("MOCK")) {
            return t.source == DataSource.MOCK ? Feed.MOCK : Feed.UNAVAILABLE; // MOCK fora do modo mock não é feed real
        }
        if (LIVE_FEEDS.contains(f)) {
            return t.feedUpdatedAt != null && now != null && Duration.between(t.feedUpdatedAt, now).compareTo(STALE_AFTER) > 0
                    ? Feed.STALE : Feed.LIVE;
        }
        return switch (f) {
            case "STALE" -> Feed.STALE;
            case "DEGRADED" -> Feed.DEGRADED;
            case "RECONNECTING" -> Feed.RECONNECTING;
            case "ERROR", "FAILED" -> Feed.ERROR;
            default -> DOWN_FEEDS.contains(f) ? Feed.DISCONNECTED : Feed.UNAVAILABLE; // desconhecido nunca é "vivo"
        };
    }

    // ------------------------------------------------------------------ metrics

    public enum CellState { READY, NA, STALE, UNAVAILABLE }

    /** Uma célula da faixa de métricas. value já formatado; reason curta e opcional (null = nenhuma). */
    public record Cell(String title, CellState state, String value, String reason) {
    }

    public static List<Cell> metrics(TraderSnapshot t, Feed feed, Instant now) {
        boolean stale = feed.looksStale();
        String noAccount = t.account == null ? "No account" : "Not reported";
        List<Cell> cells = new ArrayList<>(5);
        cells.add(account("Equity", t.equity == null ? null : Fmt.price(t.equity), noAccount, stale));
        cells.add(account("Daily PnL", t.dailyPnl == null ? null : Fmt.signed(t.dailyPnl, ""), noAccount, stale));
        cells.add(account("Exposure", t.exposure == null ? null : Fmt.price(t.exposure),
                t.account == null && t.positions == 0 ? "No positions" : "Not reported", stale));
        cells.add(account("Drawdown", t.drawdown == null ? null : Fmt.signed(t.drawdown, "%"), noAccount, stale));
        cells.add(dataAge(t, feed, now));
        return cells;
    }

    private static Cell account(String title, String value, String missingReason, boolean stale) {
        if (value == null) {
            return new Cell(title, CellState.NA, Fmt.NA, missingReason);
        }
        return stale ? new Cell(title, CellState.STALE, value, "Feed stale") : new Cell(title, CellState.READY, value, null);
    }

    static Cell dataAge(TraderSnapshot t, Feed feed, Instant now) {
        String title = "Data age";
        if (t.feedUpdatedAt != null && now != null && feed.showsMarketData()) {
            String age = age(Duration.between(t.feedUpdatedAt, now));
            return feed.looksStale() ? new Cell(title, CellState.STALE, age, "Last update " + Fmt.time(t.feedUpdatedAt))
                    : new Cell(title, CellState.READY, age, null);
        }
        if (feed.waiting()) {
            return new Cell(title, CellState.UNAVAILABLE, "—", "No feed");
        }
        if (feed.looksStale()) {
            return new Cell(title, CellState.STALE, "—", "Last update unknown");
        }
        if (feed.showsMarketData()) {
            return new Cell(title, CellState.UNAVAILABLE, "—", "Not reported");
        }
        return new Cell(title, CellState.UNAVAILABLE, "—", "No feed");
    }

    public static String age(Duration d) {
        long ms = Math.max(0, d.toMillis());
        if (ms < 1000) {
            return "<1 s";
        }
        if (ms < 10_000) {
            return String.format(Locale.US, "%.1f s", ms / 1000.0);
        }
        long s = ms / 1000;
        return s < 60 ? s + " s" : String.format(Locale.US, "%dm %02ds", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ header

    /** "24h" ao lado do preço: variação quando existe; N/A quando nenhum dado de 24h existe. */
    public static String change24h(TraderSnapshot t) {
        return t.change24hPct == null ? "24h N/A" : "24h " + Fmt.signed(t.change24hPct, "%");
    }

    public static boolean hasDayStats(TraderSnapshot t) {
        return t.high24h != null || t.low24h != null || t.volume24h != null;
    }

    public static String volume(Double v) {
        if (v == null) {
            return Fmt.NA;
        }
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.US, "%.2fB", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.US, "%.2fM", v / 1e6);
        }
        if (a >= 1e3) {
            return String.format(Locale.US, "%.1fK", v / 1e3);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    public static String venue(String market) {
        if (market == null || market.isBlank()) {
            return "Binance USD-M Futures";
        }
        String m = market.trim();
        java.util.regex.Matcher margin = java.util.regex.Pattern.compile("(?i)^(usd|coin)-m(?:[-_ ]+(.*))?$").matcher(m);
        String pretty;
        if (margin.matches()) { // USD-M / COIN-M são nomes de produto: mantêm hífen e caixa
            String rest = margin.group(2) == null ? "" : title(margin.group(2));
            pretty = (margin.group(1).toUpperCase(Locale.ROOT) + "-M " + rest).trim();
        } else {
            pretty = title(m);
        }
        return m.toUpperCase(Locale.ROOT).contains("FUTURES") ? "Binance " + pretty : pretty;
    }

    private static String title(String s) {
        return String.join(" ", Arrays.stream(s.trim().split("[-_ ]+")).map(w ->
                w.length() <= 4 && w.equals(w.toUpperCase(Locale.ROOT)) ? w
                        : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT)).toList());
    }

    public static boolean perpetual(String market) {
        if (market == null || market.isBlank()) {
            return true;
        }
        String m = market.toUpperCase(Locale.ROOT);
        return m.contains("FUTURES") || m.contains("PERP");
    }

    // ------------------------------------------------------------------ bot

    public enum Bot {
        MONITORING("MONITORING"), IDLE("IDLE"), UNAVAILABLE("UNAVAILABLE"), ERROR("ERROR");

        public final String label;

        Bot(String label) {
            this.label = label;
        }
    }

    /** Só os estados que o app declara. Execução OFF nunca vira "active trading". */
    public static Bot bot(TraderSnapshot t) {
        String s = t.botState == null ? "" : t.botState.toUpperCase(Locale.ROOT);
        if (s.contains("ERROR")) {
            return Bot.ERROR;
        }
        if (s.contains("MONITORING")) {
            return Bot.MONITORING;
        }
        if (s.contains("IDLE")) {
            return Bot.IDLE;
        }
        return Bot.UNAVAILABLE;
    }

    public static String botMode(TraderSnapshot t) {
        return "RESEARCH".equals(t.mode) ? "Research" : Fmt.text(t.mode);
    }

    public static boolean liveOff(TraderSnapshot t) {
        return t.trading == null || "DISABLED".equals(t.trading);
    }

    public static String liveTrading(TraderSnapshot t) {
        return liveOff(t) ? "Off" : Fmt.text(t.trading);
    }

    public static String botStrip(TraderSnapshot t) {
        return botMode(t) + " · Live " + (liveOff(t) ? "OFF" : Fmt.text(t.trading));
    }

    // ------------------------------------------------------------------ order book

    /** Linha do book. depth = total acumulado / maior total exibido (0..1). */
    public record BookRow(boolean ask, double price, double size, double total, double depth) {
    }

    /** asks na ordem de exibição (mais longe primeiro, melhor ask junto do mid); bids melhor primeiro. */
    public record Book(List<BookRow> asks, List<BookRow> bids, Double mid, Double spread) {
        public boolean empty() {
            return asks.isEmpty() && bids.isEmpty();
        }
    }

    public static Book book(List<Level> asks, List<Level> bids, int levels) {
        List<Level> a = asks.stream().sorted(Comparator.comparingDouble(Level::price)).limit(levels).toList();
        List<Level> b = bids.stream().sorted(Comparator.comparingDouble(Level::price).reversed()).limit(levels).toList();
        double max = Math.max(cumulative(a), cumulative(b));
        List<BookRow> askRows = rows(a, true, max);
        java.util.Collections.reverse(askRows);
        Double mid = a.isEmpty() || b.isEmpty() ? null : (a.getFirst().price() + b.getFirst().price()) / 2;
        Double spread = a.isEmpty() || b.isEmpty() ? null : a.getFirst().price() - b.getFirst().price();
        return new Book(askRows, rows(b, false, max), mid, spread);
    }

    private static double cumulative(List<Level> levels) {
        return levels.stream().mapToDouble(Level::size).sum();
    }

    private static List<BookRow> rows(List<Level> levels, boolean ask, double max) {
        List<BookRow> out = new ArrayList<>(levels.size());
        double total = 0;
        for (Level l : levels) {
            total += l.size();
            out.add(new BookRow(ask, l.price(), l.size(), total, max <= 0 ? 0 : Math.min(1, total / max)));
        }
        return out;
    }

    // ------------------------------------------------------------------ recent trades

    public record Trade(String time, String side, String size, String price) {
        public boolean buy() {
            return "BUY".equalsIgnoreCase(side);
        }

        public boolean sell() {
            return "SELL".equalsIgnoreCase(side);
        }
    }

    /** Negociações públicas (aggTrade) já formatadas: hora local, lado do agressor, tamanho e preço. Nunca fills de conta. */
    public static List<Trade> marketTrades(List<TraderSnapshot.MarketTrade> src, int n) {
        List<Trade> out = new ArrayList<>(Math.min(n, src.size()));
        for (TraderSnapshot.MarketTrade t : src.subList(0, Math.min(n, src.size()))) {
            out.add(new Trade(Fmt.time(t.time()), t.buy() ? "BUY" : "SELL", String.format(Locale.US, "%.3f", t.size()), Fmt.price(t.price())));
        }
        return out;
    }

    /** Texto do book quando o feed está vivo mas o book não é consistente (null = nenhuma nota). */
    public static String bookNote(TraderSnapshot t) {
        return t.bookState == null || "LIVE".equals(t.bookState) ? null
                : "RESYNCING".equals(t.bookState) ? "Order book resynchronizing…" : "Order book syncing…";
    }

    /** tradeRows = Time, Symbol, Side, Size, Price, Fee (fills da conta; a aba Trades usa esta fonte, Recent Trades NÃO). */
    public static List<Trade> trades(List<String[]> rows, int n) {
        List<Trade> out = new ArrayList<>(Math.min(n, rows.size()));
        for (String[] r : rows.subList(0, Math.min(n, rows.size()))) {
            out.add(new Trade(cell(r, 0), cell(r, 2), cell(r, 3), cell(r, 4)));
        }
        return out;
    }

    private static String cell(String[] row, int i) {
        return i < row.length && row[i] != null && !row[i].isBlank() ? row[i] : Fmt.NA;
    }

    // ------------------------------------------------------------------ change detection

    /** Hash de tudo que o Desk desenha; igual = nenhum nó precisa ser tocado. O relógio não entra. */
    public static int fingerprint(TraderSnapshot t) {
        return Objects.hash(t.source, t.loading, t.mode, t.trading, t.account, t.botState, t.strategy, t.strategyStatus,
                t.signal, t.backendOnline, t.symbol, t.market, t.feed, t.feedUpdatedAt, t.price, t.change24hPct, t.high24h,
                t.low24h, t.volume24h, t.markPrice, t.indexPrice, t.bookState, t.marketTrades, t.asks, t.bids, t.candles, t.equity, t.dailyPnl, t.exposure, t.drawdown,
                t.positions, t.orders, rows(t.positionRows), rows(t.orderRows), rows(t.tradeRows), rows(t.signalRows),
                rows(t.activityRows));
    }

    private static int rows(List<String[]> rows) {
        return Arrays.deepHashCode(rows.toArray());
    }
}
