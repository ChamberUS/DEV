package panel.researchview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import panel.model.CaptureInfo;
import panel.model.PipelineStage;
import panel.model.SessionInfo;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.service.PipelineService;
import panel.util.Fmt;

/**
 * Estado do Research Overview derivado do {@link Snapshot}, sem JavaFX. Nada é inventado: campo ausente vira N/A ou
 * um estado semântico (pending, unavailable). Os gates (VALIDATION, FINAL_HOLDOUT) vêm das constantes do Snapshot e
 * nenhum dado de entrada os altera: a UI nunca concede permissão.
 */
public final class ResearchModel {
    private ResearchModel() {
    }

    // ------------------------------------------------------------------ pipeline

    public enum StepState { COMPLETE, CURRENT, PENDING, LOCKED, FAILED }

    /** summary: uma linha real (só exibida em EXPANDED). running: o recorder está RUNNING (barra respira em FULL). */
    public record Step(String id, String name, StepState state, String summary, String target, boolean running) {
    }

    static StepState map(StageState s) {
        return switch (s) {
            case READY -> StepState.COMPLETE;
            case RUNNING, PARTIAL -> StepState.CURRENT;
            case LOCKED, BLOCKED -> StepState.LOCKED;
            case FAILED -> StepState.FAILED;
            case PENDING, MISSING, UNKNOWN -> StepState.PENDING;
        };
    }

    private static final Set<String> HIDDEN = Set.of("sessions", "paper");

    public static List<Step> steps(Snapshot s, boolean labelsRunning) {
        List<PipelineStage> raw;
        boolean recorderUnknown = false;
        try {
            raw = PipelineService.stages(s, labelsRunning);
        } catch (IllegalArgumentException unknownRecorder) { // StageState.valueOf(recorder): um recorder desconhecido nunca derruba a tela
            Snapshot copy = s.copy();
            copy.capture = new CaptureInfo(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            raw = PipelineService.stages(copy, labelsRunning);
            recorderUnknown = true;
        }
        int total = s.labelsTotalSessions();
        boolean capturing = "RUNNING".equals(s.capture.recorder());
        List<Step> out = new ArrayList<>();
        for (PipelineStage p : raw) {
            if (HIDDEN.contains(p.target())) {
                continue;
            }
            StepState state = map(p.state());
            String id = p.target();
            String name = switch (id) {
                case "dataset" -> "Dataset";
                case "labels" -> "Labels";
                default -> p.title();
            };
            String summary = switch (id) {
                case "capture" -> s.capture.recorder() == null ? "Recorder N/A" : titleCase(s.capture.recorder());
                case "dataset" -> p.state() == StageState.READY ? (s.sessionCount == null ? Fmt.NA : s.sessionCount + " sessions") : p.summary();
                case "features" -> Fmt.ratio(s.featureDone, total);
                default -> p.summary();
            };
            if (id.equals("capture")) {
                if (recorderUnknown) {
                    state = StepState.PENDING;
                }
                out.add(new Step(id, name, state, summary, id, capturing));
            } else {
                out.add(new Step(id, name, state, summary, id, false));
            }
        }
        return out;
    }

    static String titleCase(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ research guard

    public enum GuardKind { OPEN, LOCKED, SEALED, UNKNOWN }

    public record Gate(String name, String state, String note, GuardKind kind) {
    }

    /**
     * Os três ambientes. VALIDATION e FINAL_HOLDOUT vêm das constantes do Snapshot; TRAIN só é "Open" se a
     * partição real for TRAIN. Nenhuma entrada (fixture, backend) abre VALIDATION ou desfaz o selo.
     */
    public static List<Gate> guard(Snapshot s) {
        Gate train = "TRAIN".equals(s.partition)
                ? new Gate("TRAIN", "Open", "Hypothesis research", GuardKind.OPEN)
                : new Gate("TRAIN", Fmt.NA, "Partition not reported", GuardKind.UNKNOWN);
        Gate validation = new Gate("VALIDATION", "Locked", "Not accessible", GuardKind.LOCKED);
        Gate holdout = new Gate("FINAL_HOLDOUT", "Sealed", "Protected", GuardKind.SEALED);
        return List.of(train, validation, holdout);
    }

    // ------------------------------------------------------------------ headline

    public static String datasetLabel(Snapshot s) {
        return (s.source == panel.model.DataSource.MOCK ? "MOCK · " : "") + "Dataset " + Fmt.shortHash(s.datasetId);
    }

    public static String partitionBadge(Snapshot s) {
        return "PARTITION · " + Fmt.text(s.partition);
    }

    public static String schemaBadge(Snapshot s) {
        return "SCHEMA · " + Fmt.text(s.labelSchema);
    }

    public static String stageBadge(Snapshot s, boolean labelsRunning) {
        return "STAGE · " + PipelineService.currentStage(s, labelsRunning) + " · " + (labelsRunning ? "RUNNING" : "IDLE");
    }

    // ------------------------------------------------------------------ next allowed step

    public record Next(String title, String target, String button) {
    }

    /** Reflete a regra real (PipelineService.currentStage). O botão só abre a tela; nunca executa nada. */
    public static Next next(Snapshot s, boolean labelsRunning) {
        return switch (PipelineService.currentStage(s, labelsRunning)) {
            case "NO DATASET" -> new Next("No dataset reported", "dataset", "Open dataset");
            case "CHECKPOINT" -> new Next("Complete the checkpoint", "dataset", "Open dataset");
            case "FEATURE GENERATION" -> new Next("Generate features", "features", "Open features");
            case "PURE MID LABEL GENERATION", "PURE MID LABELS (INCOMPLETE)" -> new Next("Complete the Pure Mid labels", "labels", "Open labels");
            default -> new Next("Run hypotheses on TRAIN", "hypotheses", "Open hypotheses");
        };
    }

    // ------------------------------------------------------------------ KPIs

    public record Kpi(String title, String value, String tone, String note) {
    }

    public static List<Kpi> kpis(Snapshot s) {
        boolean capturing = "RUNNING".equals(s.capture.recorder());
        long done = s.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        String sessionNote = s.sessionCount != null && s.labelDone != null && s.sessionCount.equals(s.labelDone)
                ? "All labeled" : "Labels " + Fmt.ratio(s.labelDone, s.labelsTotalSessions());
        return List.of(
                new Kpi("Capture", capturing ? "LIVE" : Fmt.text(s.capture.recorder()), capturing ? "pos" : "dim",
                        s.capture.recorder() == null ? "Recorder N/A" : "Uptime " + Fmt.text(s.capture.sessionDuration())),
                new Kpi("Sessions", Fmt.num(s.sessionCount), s.sessionCount == null ? "dim" : null, sessionNote),
                new Kpi("Anchors", Fmt.num(s.anchorCount), s.anchorCount == null ? "dim" : null,
                        s.checkpointSamples == null ? "Rows N/A" : Fmt.num(s.checkpointSamples) + " rows"),
                new Kpi("Hypotheses", s.datasetId == null ? Fmt.NA : done + "/" + s.hypotheses.size(), s.datasetId == null ? "dim" : "purple",
                        Fmt.text(s.frozenSpecStatus)));
    }

    public static List<String[]> datasetRows(Snapshot s) {
        return List.of(
                new String[] {"Label schema", Fmt.text(s.labelSchema)},
                new String[] {"Horizons", s.horizons.isEmpty() ? Fmt.NA : String.valueOf(s.horizons.size())},
                new String[] {"Feature schema", Fmt.text(s.featureSchema)},
                new String[] {"Features complete", Fmt.ratio(s.featureDone, s.labelsTotalSessions())});
    }

    // ------------------------------------------------------------------ sessions

    public enum SegState { COMPLETE, CAPTURING, FAILED, PENDING }

    public record Seg(String id, SegState state) {
    }

    public record Sessions(List<Seg> segments, String summary) {
    }

    /**
     * Uma célula por sessão real. A sessão ativa (recorder RUNNING + currentSession) é uma célula CAPTURING, uma vez só:
     * troca a sua entrada em {@code sessions} ou é acrescentada ao fim se a lista não a tem. Nenhum contador fixo.
     */
    public static Sessions sessions(Snapshot s) {
        Map<String, SessionInfo> byId = new LinkedHashMap<>();
        for (SessionInfo info : s.sessions) {
            if (info.id() != null) {
                byId.putIfAbsent(info.id(), info);
            }
        }
        boolean capturing = "RUNNING".equals(s.capture.recorder());
        String active = capturing && s.capture.currentSession() != null && !s.capture.currentSession().isBlank() ? s.capture.currentSession() : null;
        if (active != null) {
            byId.putIfAbsent(active, null);
        }
        List<Seg> segs = new ArrayList<>();
        int ready = 0, live = 0, failed = 0, other = 0;
        for (var e : byId.entrySet()) {
            SegState st;
            if (e.getKey().equals(active)) {
                st = SegState.CAPTURING;
                live++;
            } else {
                SessionInfo.Overall o = e.getValue().overall();
                st = switch (o) {
                    case COMPLETE -> SegState.COMPLETE;
                    case FAILED -> SegState.FAILED;
                    case PARTIAL, MISSING -> SegState.PENDING;
                };
                switch (st) {
                    case COMPLETE -> ready++;
                    case FAILED -> failed++;
                    default -> other++;
                }
            }
            segs.add(new Seg(e.getKey(), st));
        }
        String summary = segs.isEmpty() ? "Session states unavailable"
                : ready + " ready · " + live + " capturing" + (failed == 0 ? "" : " · " + failed + " failed") + (other == 0 ? "" : " · " + other + " other");
        return new Sessions(segs, summary);
    }

    // ------------------------------------------------------------------ attention

    public static String warningText(int n) {
        return n == 0 ? "No warnings" : n + (n == 1 ? " WARNING" : " WARNINGS");
    }

    public static String failedJobsText(int n) {
        return n + (n == 1 ? " failed job" : " failed jobs") + ". Details in Logs.";
    }

    // ------------------------------------------------------------------ capture side panel

    public static String[] captureRows(Snapshot s) {
        return new String[] {s.capture.recorder() == null ? Fmt.NA : titleCase(s.capture.recorder()), Fmt.text(s.capture.sessionDuration()),
                Fmt.text(s.capture.currentSession())};
    }

    /** Hash do que o Overview desenha (sem loadedAt): igual = nenhum nó é tocado. */
    public static int fingerprint(Snapshot s, boolean labelsRunning, int failedJobs) {
        return java.util.Objects.hash(s.fingerprint(), labelsRunning, failedJobs);
    }
}
