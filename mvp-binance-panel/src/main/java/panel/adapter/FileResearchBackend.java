package panel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import panel.model.Catalog;
import panel.model.DataSource;
import panel.model.FeatureInfo;
import panel.model.HorizonStat;
import panel.model.HypothesisInfo;
import panel.model.SessionInfo;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.repository.ResearchReportRepository;
import panel.util.Json;

/** Backend REAL: lê os relatórios TRAIN já gerados pelo adaptive-trader. Não calcula nada. */
public class FileResearchBackend implements ResearchBackend {
    private static final DateTimeFormatter SESSION_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final CommandAdapter cli;

    public FileResearchBackend(CommandAdapter cli) {
        this.cli = cli;
    }

    @Override
    public Snapshot load(Settings settings) {
        Snapshot s = new Snapshot();
        s.source = DataSource.REAL;
        s.backendOnline = cli.available() && Files.isDirectory(settings.project());
        s.backendNote = s.backendOnline ? "adaptive-trader CLI found" : "CLI not found at " + settings.cliPath;
        ResearchReportRepository repo = new ResearchReportRepository(settings.reports(), s.warnings);

        JsonNode cp = repo.report("train-checkpoint-aggregate.json");
        JsonNode ft = repo.report("train-feature-aggregate.json");
        JsonNode base = cp != null ? cp : ft;
        if (base == null) {
            s.warnings.add("Nenhum agregado TRAIN encontrado em " + settings.reports());
            return s;
        }
        if (!"TRAIN".equalsIgnoreCase(Json.str(base, "partition"))) {
            s.warnings.add("Rejected non-TRAIN or unpartitioned summary");
            return s;
        }
        s.datasetId = Json.str(base, "dataset_id");
        if (s.datasetId == null || !s.datasetId.matches("[a-zA-Z0-9_-]+")) {
            s.warnings.add("Missing or invalid TRAIN dataset id");
            return s;
        }
        s.partition = Json.str(base, "partition") == null ? "TRAIN" : Json.str(base, "partition");
        s.datasetCreatedAt = Json.str(base, "created_at");
        Long count = Json.lng(base, "session_count");
        s.sessionCount = count == null ? null : count.intValue();
        s.checkpointSamples = Json.lng(cp, "sample_count");
        s.anchorCount = Json.lng(ft, "row_count");
        s.schemas.put("checkpoint pipeline", Json.str(cp, "pipeline_version"));
        s.schemas.put("checkpoint schema", Json.str(cp, "schema_version"));
        s.featureSchema = Json.str(ft, "feature_schema_version");
        s.schemas.put("features", s.featureSchema);
        s.featureGeneratedAt = Json.str(ft, "created_at");
        s.hashes.put("dataset_id", s.datasetId);
        s.hashes.put("campaign", Json.str(cp, "campaign_hash"));
        s.hashes.put("checkpoint config", Json.str(cp, "config_hash"));
        s.hashes.put("checkpoint aggregate", Json.str(cp, "aggregate_hash"));
        s.hashes.put("feature config", Json.str(ft, "feature_config_hash"));
        s.hashes.put("feature aggregate", Json.str(ft, "aggregate_hash"));
        s.paths.put("project", settings.projectPath);
        s.paths.put("reports", settings.reports().toString());
        s.paths.put("recorded data", settings.project().resolve("data/microstructure").toString());

        List<String> ids = new ArrayList<>();
        JsonNode cpSessions = Json.get(cp, "sessions");
        if (cpSessions != null) {
            cpSessions.forEach(n -> ids.add(Json.str(n, "session_id")));
        } else if (Json.get(ft, "sessions") != null) {
            Json.get(ft, "sessions").forEach(n -> ids.add(Json.str(n, "session_id")));
        }
        Map<String, JsonNode> ftBySession = new LinkedHashMap<>();
        if (Json.get(ft, "sessions") != null) {
            Json.get(ft, "sessions").forEach(n -> ftBySession.put(Json.str(n, "session_id"), n));
        }

        int cpDone = 0, ftDone = 0, lbDone = 0, lbMissing = 0, lbInvalid = 0;
        Instant lastEnd = null;
        String firstFeaturePath = null;
        for (String id : ids) {
            if (id == null || !id.matches("microstructure-[0-9]{8}T[0-9]{6}Z-[a-z_]+")
                    || id.contains("validation") || id.contains("holdout")) {
                s.warnings.add("Rejected unsafe TRAIN session id");
                continue;
            }
            Map<String, String> d = new LinkedHashMap<>();
            JsonNode cm = repo.json(repo.stageDir("checkpoints", s.datasetId, id).resolve("metadata.json"));
            JsonNode fm = repo.json(repo.stageDir("features", s.datasetId, id).resolve("metadata.json"));
            JsonNode lm = repo.json(repo.stageDir("labels", s.datasetId, id).resolve("metadata.json"));

            StageState cs = stateOf(cm);
            StageState fs = stateOf(fm);
            StageState ls = stateOf(lm);
            String error = null;
            if (lm != null && s.datasetId != null && !s.datasetId.equals(Json.str(lm, "dataset_id"))) {
                ls = StageState.FAILED;
                error = "Label metadata belongs to another dataset (stale)";
            }
            if (cs == StageState.READY) cpDone++;
            if (fs == StageState.READY) ftDone++;
            if (ls == StageState.READY) {
                lbDone++;
                if (s.labelSchema == null) {
                    s.labelSchema = Json.str(lm, "label_schema_version");
                    s.hashes.put("label config", Json.str(lm, "label_config_hash"));
                }
                JsonNode hs = Json.get(lm, "horizons");
                if (hs != null && s.horizons.isEmpty()) {
                    hs.forEach(h -> s.horizons.add(h.asLong()));
                }
            } else if (ls == StageState.MISSING) {
                lbMissing++;
            } else if (ls == StageState.FAILED) {
                lbInvalid++;
            }

            Instant start = Json.instant(cm, "session_start");
            Instant end = Json.instant(cm, "session_end");
            if (start == null) {
                start = fromSessionId(id);
            }
            if (end != null && (lastEnd == null || end.isAfter(lastEnd))) {
                lastEnd = end;
            }
            JsonNode fe = ftBySession.get(id);
            if (firstFeaturePath == null && fe != null && fs == StageState.READY) {
                firstFeaturePath = Json.str(fe, "path");
            }
            Long anchors = Json.lng(Json.get(cm, "row_counts"), "feature_anchors");
            if (anchors == null) anchors = Json.lng(fm, "row_count");
            d.put("session_id", id);
            d.put("session_start", Json.str(cm, "session_start"));
            d.put("session_end", Json.str(cm, "session_end"));
            d.put("event_count", Json.str(cm, "event_count"));
            d.put("checkpoint samples", Json.str(cm, "sample_count"));
            d.put("anchor_count (checkpoint)", Json.str(Json.get(cm, "row_counts"), "feature_anchors"));
            d.put("feature rows", Json.str(fm, "row_count"));
            d.put("label rows", Json.str(lm, "row_count"));
            d.put("checkpoint hash", Json.str(cm, "output_hash"));
            d.put("feature hash", Json.str(fm, "output_hash"));
            d.put("label hash", Json.str(lm, "output_hash"));
            d.put("checkpoint file", cm == null ? null : repo.stageDir("checkpoints", s.datasetId, id).resolve("outputs").toString());
            d.put("feature file", fe == null ? null : Json.str(fe, "path"));
            d.put("label file", lm == null ? null : repo.stageDir("labels", s.datasetId, id).resolve(Json.str(lm, "output") == null ? "forward_mid_labels.csv.gz" : Json.str(lm, "output")).toString());
            d.put("label status", lm == null ? "MISSING" : Json.str(lm, "status"));
            d.put("valid labels by horizon", Json.str(lm, "valid_label_counts_by_horizon"));
            s.sessions.add(new SessionInfo(id, start, end, Json.lng(cm, "event_count"), anchors, cs, fs, ls, error, d));
        }
        s.lastSessionEnd = lastEnd;
        if (s.sessionCount == null && !ids.isEmpty()) s.sessionCount = ids.size();
        int total = s.labelsTotalSessions();
        s.checkpointDone = cpDone;
        s.featureDone = ftDone;
        s.labelDone = lbDone;
        s.labelMissing = lbMissing;
        s.labelInvalid = lbInvalid;
        s.checkpointState = aggregate(cpDone, total);
        s.featureState = aggregate(ftDone, total);
        s.labelState = aggregate(lbDone, total);
        if (lbInvalid > 0) s.labelState = StageState.FAILED;
        if (s.horizons.isEmpty()) {
            s.horizons.addAll(Catalog.HORIZONS_MS);
        }

        readLabelAggregate(s, repo);
        readFeatures(s);

        
        for (Catalog.HypothesisDef h : Catalog.HYPOTHESES) {
            s.hypotheses.add(new HypothesisInfo(h.id(), h.name(), h.feature(), StageState.PENDING, "Pure Forward Mid", "250ms → 60s", "TRAIN", "Not executed", null));
        }
        return s;
    }

    private static void readLabelAggregate(Snapshot s, ResearchReportRepository repo) {
        JsonNode agg = repo.report("train-label-aggregate.json");
        if (agg != null && (!"TRAIN".equals(Json.str(agg, "partition"))
                || !s.datasetId.equals(Json.str(agg, "dataset_id")))) {
            s.warnings.add("Rejected mismatched label aggregate");
            agg = null;
        }
        s.labelTotal = Json.lng(agg, "row_count");
        s.labelAnchors = Json.lng(agg, "anchor_count");
        s.labelAggregate = agg;
        s.labelAggregateAvailable = agg != null;
        JsonNode per = Json.get(agg, "statistics_by_horizon", "by_horizon", "horizon_stats");
        if (per != null) {
            List<JsonNode> rows = new ArrayList<>();
            if (per.isArray()) {
                per.forEach(rows::add);
            } else {
                per.fields().forEachRemaining(e -> {
                    if (e.getValue() instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
                        o.put("horizon_ms", e.getKey());
                    }
                    rows.add(e.getValue());
                });
            }
            for (JsonNode r : rows) {
                String h = Json.str(r, "horizon_ms", "horizon");
                if (h == null) {
                    s.warnings.add("Aggregate de labels: linha sem horizonte");
                    continue;
                }
                JsonNode err = Json.get(r, "future_timestamp_error_ms", "timing_error_ms", "timestamp_error_ms", "temporal_error_ms");
                try {
                    s.horizonStats.add(new HorizonStat(Long.parseLong(h), Json.lng(r, "valid", "valid_count", "valid_label_count", "labels_valid"), Json.lng(r, "invalid", "invalid_count", "labels_missing"),
                            Json.dbl(r, "coverage") != null ? Json.dbl(r, "coverage") : Json.dbl(r, "coverage_pct") == null ? null : Json.dbl(r, "coverage_pct") / 100.0, Json.dbl(err, "median", "p50"), Json.dbl(err, "p95"), Json.dbl(err, "p99"), Json.dbl(err, "max"), false));
                } catch (NumberFormatException e) {
                    s.warnings.add("Aggregate de labels: horizonte inválido " + h);
                }
            }
            if (!s.horizonStats.isEmpty()) {
                return;
            }
        }
        if (agg != null) {
            s.warnings.add("train-label-aggregate.json sem estatísticas por horizonte reconhecíveis");
        }
    }

    private static void readFeatures(Snapshot s) {
        List<String> columns = List.of();
        for (Catalog.FeatureDef def : Catalog.FEATURES) {
            List<String> matched = columns.stream().filter(c -> def.prefixes().stream().anyMatch(c::startsWith)).toList();
            StageState st = columns.isEmpty() ? s.featureState : (matched.isEmpty() ? StageState.MISSING : s.featureState);
            s.features.add(new FeatureInfo(def.category(), def.name(), st, s.featureSchema, matched, s.featureGeneratedAt, s.hashes.get("feature aggregate")));
        }
    }

    private static StageState stateOf(JsonNode meta) {
        if (meta == null) {
            return StageState.MISSING;
        }
        return "COMPLETE".equalsIgnoreCase(Json.str(meta, "status")) ? StageState.READY : StageState.FAILED;
    }

    private static StageState aggregate(int done, int total) {
        if (total == 0 || done == 0) {
            return total == 0 ? StageState.UNKNOWN : StageState.MISSING;
        }
        return done >= total ? StageState.READY : StageState.PARTIAL;
    }

    private static Instant fromSessionId(String id) {
        try {
            return LocalDateTime.parse(id.substring("microstructure-".length(), id.indexOf('Z') + 1), SESSION_TS).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

}
