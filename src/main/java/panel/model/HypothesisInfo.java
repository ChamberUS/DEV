package panel.model;

import java.util.List;

/** Hipótese; {@code quantileMeans} só é preenchido com resultados reais (ou MOCK). */
public record HypothesisInfo(String id, String name, String feature, StageState state, String target,
                             String horizons, String dataset, String results, List<Double> quantileMeans) {
}
