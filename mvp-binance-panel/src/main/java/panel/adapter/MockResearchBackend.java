package panel.adapter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import panel.model.CaptureInfo;
import panel.model.Catalog;
import panel.model.DataSource;
import panel.model.FeatureInfo;
import panel.model.HorizonStat;
import panel.model.HypothesisInfo;
import panel.model.SessionInfo;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.model.StageState;

/** Dados FICTÍCIOS para visualizar telas sem backend. A UI sempre sinaliza DATA SOURCE: MOCK. */
public class MockResearchBackend implements ResearchBackend {
    @Override
    public Snapshot load(Settings settings) {
        Snapshot s = new Snapshot();
        s.source = DataSource.MOCK;
        s.backendOnline = true;
        s.backendNote = "mock backend";
        s.datasetId = "mock0000000000000000000000000000000000000000000000000000000000dead";
        s.datasetCreatedAt = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        s.sessionCount = 12;
        s.anchorCount = 80_000L;
        s.checkpointSamples = 2_400_000L;
        s.schemas.put("labels", "pure-forward-mid-v1");
        s.hashes.put("dataset_id", s.datasetId);
        s.paths.put("project", "(mock)");
        s.featureSchema = "causal-microstructure-features-v1";
        s.featureGeneratedAt = Instant.now().minus(2, ChronoUnit.DAYS).toString();
        s.checkpointState = StageState.READY;
        s.checkpointDone = 12;
        s.featureState = StageState.READY;
        s.featureDone = 12;
        s.labelState = StageState.PARTIAL;
        s.labelDone = 7;
        s.labelMissing = 5;
        s.labelInvalid = 0;
        s.labelSchema = "pure-forward-mid-v1";
        s.labelTotal = 7 * 8 * 6_600L;
        s.labelAnchors = 46_000L;
        s.horizons.addAll(Catalog.HORIZONS_MS);
        double[] cov = {0.999, 0.998, 0.995, 0.99, 0.985, 0.97, 0.94, 0.90};
        double[] med = {18, 22, 31, 48, 64, 90, 120, 180};
        for (int i = 0; i < 8; i++) {
            long valid = Math.round(s.labelAnchors * cov[i]);
            s.horizonStats.add(new HorizonStat(Catalog.HORIZONS_MS.get(i), valid, s.labelAnchors - valid, cov[i], med[i], med[i] * 3, med[i] * 6, med[i] * 20, false));
        }
        s.labelAggregateAvailable = true;
        for (int i = 0; i < 12; i++) {
            Instant start = Instant.now().minus(12 - i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Map<String, String> d = new LinkedHashMap<>();
            d.put("session_id", "mock-session-" + i);
            d.put("note", "MOCK DATA");
            StageState lb = i < 7 ? StageState.READY : StageState.MISSING;
            s.sessions.add(new SessionInfo("mock-session-" + i, start, start.plusSeconds(3000 + i * 60), 100_000L + i * 1000, 6_600L, StageState.READY, StageState.READY, lb, null, d));
        }
        s.lastSessionEnd = Instant.now().minus(1, ChronoUnit.DAYS);
        for (Catalog.FeatureDef f : Catalog.FEATURES) {
            s.features.add(new FeatureInfo(f.category(), f.name(), StageState.READY, s.featureSchema, List.of(f.prefixes().get(0) + "…"), s.featureGeneratedAt, "mockhash"));
        }
        s.capture = new CaptureInfo("RUNNING", "CONNECTED", "Binance", "USD-M-FUTURES", "BTCUSDT", "mock-session-live", "00:42:10", 1_204_332L, 412.5,
                980_100L, 224_232L, 2L, Instant.now(), "3.1 GB", "(mock)", "0");
        double[][] q = {{-0.8, -0.3, 0.0, 0.3, 0.9}, {-0.5, -0.2, 0.0, 0.2, 0.4}, {-0.2, -0.1, 0.0, 0.1, 0.2}, {-0.4, -0.1, 0.0, 0.2, 0.5}};
        int i = 0;
        for (Catalog.HypothesisDef h : Catalog.HYPOTHESES) {
            List<Double> qs = List.of(q[i][0], q[i][1], q[i][2], q[i][3], q[i][4]);
            s.hypotheses.add(new HypothesisInfo(h.id(), h.name(), h.feature(), i == 0 ? StageState.READY : StageState.PENDING, "Pure Forward Mid", "250ms → 60s", "TRAIN",
                    i == 0 ? "MOCK result" : "Not executed", i == 0 ? qs : null));
            i++;
        }
        s.warnings.add("MOCK mode: values are fictional.");
        return s;
    }
}
