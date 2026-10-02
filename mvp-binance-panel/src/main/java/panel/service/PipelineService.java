package panel.service;

import java.util.ArrayList;
import java.util.List;
import panel.model.PipelineStage;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.util.Fmt;

/** Traduz o Snapshot em etapas visuais do pipeline. Apenas apresentação. */
public final class PipelineService {
    private PipelineService() {
    }

    public static List<PipelineStage> stages(Snapshot s, boolean labelsRunning) {
        List<PipelineStage> l = new ArrayList<>();
        int total = s.labelsTotalSessions();
        l.add(new PipelineStage("Capture", s.capture.recorder() == null ? StageState.UNKNOWN : StageState.valueOf(s.capture.recorder()),
                s.capture.recorder() == null ? "Recorder N/A" : s.capture.recorder(), "capture"));
        l.add(new PipelineStage("Sessions", s.sessionCount == null ? StageState.UNKNOWN : StageState.READY, Fmt.num(s.sessionCount), "sessions"));
        l.add(new PipelineStage("Checkpoint", s.checkpointState, describe(s.checkpointState, s.checkpointDone, total), "dataset"));
        l.add(new PipelineStage("Features", s.featureState, describe(s.featureState, s.featureDone, total), "features"));
        StageState labels = labelsRunning && s.labelState != StageState.READY ? StageState.RUNNING : s.labelState;
        l.add(new PipelineStage("Pure Mid Labels", labels, Fmt.ratio(s.labelDone, total), "labels"));
        long hDone = s.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        l.add(new PipelineStage("Hypotheses", hDone == 0 ? StageState.PENDING : (hDone == s.hypotheses.size() ? StageState.READY : StageState.PARTIAL),
                hDone + " / " + s.hypotheses.size() + " completed", "hypotheses"));
        l.add(new PipelineStage("Validation", StageState.LOCKED, "Locked", "validation"));
        l.add(new PipelineStage("Execution", StageState.PENDING, "Pending", "execution"));
        l.add(new PipelineStage("Paper / Shadow", StageState.LOCKED, "Locked", "paper"));
        l.add(new PipelineStage("Live", StageState.LOCKED, "Locked", "live"));
        return l;
    }

    public static String currentStage(Snapshot s, boolean labelsRunning) {
        if (s.datasetId == null) {
            return "NO DATASET";
        }
        if (s.checkpointState != StageState.READY) {
            return "CHECKPOINT";
        }
        if (s.featureState != StageState.READY) {
            return "FEATURE GENERATION";
        }
        if (s.labelState != StageState.READY) {
            return labelsRunning ? "PURE MID LABEL GENERATION" : "PURE MID LABELS (INCOMPLETE)";
        }
        return "HYPOTHESIS RESEARCH";
    }

    private static String describe(StageState st, Integer done, int total) {
        return st == StageState.READY ? "Complete" : Fmt.ratio(done, total);
    }
}
