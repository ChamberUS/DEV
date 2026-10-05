package panel.researchview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import panel.model.CaptureInfo;
import panel.model.CaptureSnapshot;
import panel.model.HypothesisInfo;
import panel.model.SessionInfo;
import panel.model.Snapshot;
import panel.model.StageState;

/**
 * Fixtures ISOLADAS de testes e QA: registros em memória, nada é lido nem gravado em disco, nenhum job roda. O
 * app real só mostra o que o backend real entrega. Os gates (VALIDATION, FINAL_HOLDOUT) são constantes do Snapshot:
 * nenhuma fixture consegue alterá-los.
 */
final class ResearchFixtures {
    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private ResearchFixtures() {
    }

    /** O que o app real mostra sem backend: tudo nulo/N/A. */
    static Snapshot empty() {
        return new Snapshot();
    }

    static SessionInfo session(String id, StageState state) {
        return new SessionInfo(id, NOW.minusSeconds(3600), NOW.minusSeconds(1800), 1000L, 100L, state, state, state, null, Map.of());
    }

    /** n sessões completas, features/labels completos, hipóteses sem resultado; recorder RUNNING só se capturing. */
    static Snapshot trainReady(int sessions, boolean capturing) {
        Snapshot s = new Snapshot();
        s.backendOnline = true;
        s.datasetId = "6d3346aa90bbcc11223344";
        s.partition = "TRAIN";
        s.sessionCount = sessions;
        s.anchorCount = 209_478L;
        s.checkpointSamples = 10_264_422L;
        s.checkpointState = StageState.READY;
        s.checkpointDone = sessions;
        s.featureState = StageState.READY;
        s.featureDone = sessions;
        s.featureSchema = "causal-microstructure-features-v1";
        s.labelState = StageState.READY;
        s.labelDone = sessions;
        s.labelSchema = "pure-forward-mid-v1";
        s.horizons = new ArrayList<>(List.of(1L, 2L, 5L, 10L, 30L, 60L, 120L, 300L));
        s.frozenSpecStatus = "Spec frozen";
        for (int i = 0; i < 4; i++) {
            s.hypotheses.add(new HypothesisInfo("h" + i, "Hypothesis " + i, "feature", StageState.PENDING, "target", "1s", "ds", null, List.of()));
        }
        for (int i = 0; i < sessions; i++) {
            s.sessions.add(session(String.format("s%03d", i), StageState.READY));
        }
        s.capture = capturing
                ? new CaptureInfo("RUNNING", "CONNECTED", "Binance", "USD-M-FUTURES", "ETHUSDT", "s-active", "21:09", null, null, null, null, null, null, null, null, null)
                : new CaptureInfo("STOPPED", null, "Binance", "USD-M-FUTURES", "ETHUSDT", null, null, null, null, null, null, null, null, null, null, null);
        return s;
    }

    static CaptureSnapshot capture(CaptureSnapshot.State state) {
        return capture(state, NOW);
    }

    /** now = o relógio contra o qual a fixture é lida (QA usa o relógio real; testes usam NOW). */
    static CaptureSnapshot capture(CaptureSnapshot.State state, Instant now) {
        Instant start = now.minusSeconds(1269);
        return new CaptureSnapshot(state, state == CaptureSnapshot.State.UNKNOWN ? null : 4242L, state == CaptureSnapshot.State.RUNNING, 
                state == CaptureSnapshot.State.UNKNOWN ? null : start, "ethusdt-campaign-20261004", state == CaptureSnapshot.State.UNKNOWN ? null : start,
                "ETHUSDT", "USD-M-FUTURES", now, state == CaptureSnapshot.State.RUNNING ? now : null, "QA-FIXTURE/no-dataset-access",
                2_147_483_648L, 400L * 1024 * 1024 * 1024, 1024L * 1024 * 1024 * 1024, now, List.of());
    }
}
