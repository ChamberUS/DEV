package panel.model;

import java.time.Instant;
import java.util.Map;

/** Linha de sessão. Campos nulos significam "não disponível" e viram N/A na UI. */
public record SessionInfo(String id, Instant start, Instant end, Long events, Long anchors,
                          StageState checkpoint, StageState features, StageState labels,
                          String error, Map<String, String> details) {

    public enum Overall { COMPLETE, PARTIAL, MISSING, FAILED }

    public Overall overall() {
        if (error != null || checkpoint == StageState.FAILED || features == StageState.FAILED || labels == StageState.FAILED) {
            return Overall.FAILED;
        }
        if (checkpoint == StageState.READY && features == StageState.READY && labels == StageState.READY) {
            return Overall.COMPLETE;
        }
        if (checkpoint == StageState.MISSING && features == StageState.MISSING && labels == StageState.MISSING) {
            return Overall.MISSING;
        }
        return Overall.PARTIAL;
    }

    public java.time.Duration duration() {
        return start == null || end == null ? null : java.time.Duration.between(start, end);
    }
}
