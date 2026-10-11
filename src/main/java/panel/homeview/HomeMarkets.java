package panel.homeview;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import panel.tradeview.DeskModel;

/**
 * Model of the Home "Supported markets" table (B01). Pure: no JavaFX, no I/O, no clock of its own.
 *
 * <p>Two separate facts are modelled and never merged:
 * <ul>
 *   <li>the <b>catalog</b> — which instruments this build supports (a platform fact, {@link Catalog});</li>
 *   <li>the <b>data subscription</b> — whether public price data for them is flowing right now ({@link DeskModel.Feed} + {@link Quote}).</li>
 * </ul>
 * A failed catalog request is {@link State#ERROR}, never {@link State#EMPTY}; a missing number is {@code null} and renders as unavailable,
 * never as zero; data that is stale or from a lost feed is never presented as current.
 */
public final class HomeMarkets {
    private HomeMarkets() {
    }

    public enum State { LOADING, READY, EMPTY, ERROR, OFFLINE, STALE, PARTIAL_DATA, UNSUPPORTED_SEARCH }

    /** Perpetual futures and spot are different instruments even when the base asset is the same. */
    public enum Kind { PERPETUAL, SPOT }

    public record Instrument(String symbol, Kind kind, String venue, String quote) {
        /** Instruments the Terminal can display today (dependency D-05: the Terminal is bound to the ETHUSDT feed). */
        private static final Set<String> TERMINAL_SYMBOLS = Set.of("ETHUSDT");

        public boolean terminalSupported() {
            return kind == Kind.PERPETUAL && TERMINAL_SYMBOLS.contains(symbol);
        }
    }

    /** Observed public values. Any field may be null = "not provided by the feed" (never zero). */
    public record Quote(Double last, Double change24hPct, Double volume24hQuote, Instant observedAt) {
        public boolean anyMissing() {
            return last == null || change24hPct == null || volume24hQuote == null;
        }

        public boolean none() {
            return last == null && change24hPct == null && volume24hQuote == null;
        }
    }

    public sealed interface Catalog permits Catalog.Loading, Catalog.Loaded, Catalog.Failed {
        record Loading() implements Catalog {
        }

        record Loaded(List<Instrument> instruments) implements Catalog {
            public Loaded {
                instruments = List.copyOf(instruments);
            }
        }

        /** The catalog request itself failed. NOT an empty catalog. */
        record Failed(String reason) implements Catalog {
        }
    }

    public record Row(Instrument instrument, Quote quote) {
        public boolean hasMissingValues() {
            return quote == null || quote.anyMissing();
        }
    }

    /**
     * @param rows        rows to show (after the search filter)
     * @param suggestions on UNSUPPORTED_SEARCH: what exists, so the screen can list it
     * @param age         age of the freshest observation, null when unknown
     * @param detail      machine-readable cause (screens map it to text), may be null
     * @param mock        the feed is a fictional mock feed; the screen must say so
     */
    public record Model(State state, List<Row> rows, List<Instrument> suggestions, String query, Duration age, int partialRows, int totalRows,
            boolean single, boolean mock, String detail) {
        public boolean showsValues() {
            return state == State.READY || state == State.STALE || state == State.PARTIAL_DATA || state == State.OFFLINE;
        }
    }

    /** The catalog this build supports: exactly one instrument, from the existing public market feed. */
    public static Catalog productionCatalog() {
        return new Catalog.Loaded(List.of(new Instrument("ETHUSDT", Kind.PERPETUAL, "Binance USD-M Futures", "USDT")));
    }

    public static Model build(Catalog catalog, Function<Instrument, Quote> quotes, DeskModel.Feed feed, boolean mock, String query, Instant now) {
        String q = query == null ? "" : query.trim();
        if (catalog instanceof Catalog.Loading) {
            return new Model(State.LOADING, List.of(), List.of(), q, null, 0, 0, false, mock, null);
        }
        if (catalog instanceof Catalog.Failed f) {
            return new Model(State.ERROR, List.of(), List.of(), q, null, 0, 0, false, mock, f.reason());
        }
        List<Instrument> all = ((Catalog.Loaded) catalog).instruments();
        if (all.isEmpty()) {
            return new Model(State.EMPTY, List.of(), List.of(), q, null, 0, 0, false, mock, null);
        }
        List<Instrument> matched = q.isEmpty() ? all : all.stream().filter(i -> matches(i, q)).toList();
        if (matched.isEmpty()) {
            return new Model(State.UNSUPPORTED_SEARCH, List.of(), all, q, null, 0, 0, all.size() == 1, mock, null);
        }
        List<Row> rows = matched.stream().map(i -> new Row(i, quotes.apply(i))).toList();
        int partial = (int) rows.stream().filter(Row::hasMissingValues).count();
        Instant freshest = rows.stream().map(Row::quote).filter(x -> x != null && x.observedAt() != null).map(Quote::observedAt)
                .max(Instant::compareTo).orElse(null);
        Duration age = freshest == null || now == null ? null : Duration.between(freshest, now);
        boolean anyData = rows.stream().anyMatch(r -> r.quote() != null && !r.quote().none());
        State state;
        String detail = null;
        switch (feed) {
            case NO_FEED -> {
                state = State.OFFLINE;
                detail = "NOT_CONFIGURED";
            }
            case WAITING -> state = anyData ? State.STALE : State.LOADING;
            case DISCONNECTED, UNAVAILABLE -> {
                state = State.OFFLINE;
                detail = feed.name();
            }
            case ERROR -> {
                state = State.ERROR;
                detail = "FEED_ERROR";
            }
            case STALE, RECONNECTING, DEGRADED -> state = State.STALE; // never implied current
            case LIVE, MOCK -> state = partial > 0 ? State.PARTIAL_DATA : State.READY;
            default -> state = State.OFFLINE;
        }
        return new Model(state, rows, List.of(), q, age, partial, rows.size(), all.size() == 1, mock || feed == DeskModel.Feed.MOCK, detail);
    }

    static boolean matches(Instrument i, String q) {
        String n = q.toLowerCase(Locale.ROOT);
        return i.symbol().toLowerCase(Locale.ROOT).contains(n) || i.kind().name().toLowerCase(Locale.ROOT).contains(n)
                || i.venue().toLowerCase(Locale.ROOT).contains(n);
    }
}
