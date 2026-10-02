package panel.model;

import java.util.List;

/** Definições estáticas de hipóteses e famílias de features (metadados, sem cálculo). */
public final class Catalog {
    public record HypothesisDef(String id, String name, String feature) {
    }

    public record FeatureDef(String category, String name, List<String> prefixes) {
    }

    public static final List<HypothesisDef> HYPOTHESES = List.of(
            new HypothesisDef("H001", "Order Flow Imbalance", "OFI"),
            new HypothesisDef("H002", "Microprice Deviation", "Microprice Deviation"),
            new HypothesisDef("H003", "Depth Imbalance", "Depth Imbalance"),
            new HypothesisDef("H004", "Aggressive Trade Imbalance", "Trade Imbalance"));

    public static final List<FeatureDef> FEATURES = List.of(
            new FeatureDef("Order Book", "OFI", List.of("ofi_")),
            new FeatureDef("Order Book", "Depth Imbalance", List.of("depth_imbalance_")),
            new FeatureDef("Order Book", "Microprice", List.of("microprice")),
            new FeatureDef("Order Book", "Microprice Deviation", List.of("microprice_deviation")),
            new FeatureDef("Order Book", "Spread", List.of("spread_")),
            new FeatureDef("Order Book", "Depth", List.of("bid_depth_", "ask_depth_", "total_depth_")),
            new FeatureDef("Trades", "Aggressive Trade Imbalance", List.of("trade_imbalance_")),
            new FeatureDef("Trades", "Aggressive Buy Volume", List.of("aggressive_buy_volume")),
            new FeatureDef("Trades", "Aggressive Sell Volume", List.of("aggressive_sell_volume")),
            new FeatureDef("Price / Volatility", "Trailing Mid Return", List.of("mid_return_")),
            new FeatureDef("Price / Volatility", "Realized Volatility", List.of("realized_volatility_")),
            new FeatureDef("Data Quality", "Book / Event / Trade Age", List.of("book_age_", "event_age_", "trade_age_")));

    public static final List<Long> HORIZONS_MS = List.of(250L, 500L, 1000L, 3000L, 5000L, 15000L, 30000L, 60000L);

    private Catalog() {
    }
}
