package panel.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Estado completo do projeto numa leitura. Campos nulos = N/A. */
public class Snapshot implements Cloneable {
    public String backendVersion;
    public String frozenSpecStatus;
    public String validationReadiness;
    public final String validationStatus = "LOCKED";
    public final String finalHoldout = "SEALED";
    public String campaignId;
    public String captureStreams;
    public Instant captureStartedAt;
    public Instant captureStatusAt;
    public String recorderHealth;
    public DataSource source = DataSource.REAL;
    public Instant loadedAt = Instant.now();
    public boolean loading;
    public boolean backendOnline;
    public String backendNote;

    public String datasetId;
    public String partition = "TRAIN";
    public String datasetCreatedAt;
    public Integer sessionCount;
    public Long anchorCount;
    public Long checkpointSamples;
    public Map<String, String> schemas = new LinkedHashMap<>();
    public Map<String, String> hashes = new LinkedHashMap<>();
    public Map<String, String> paths = new LinkedHashMap<>();

    public StageState checkpointState = StageState.UNKNOWN;
    public Integer checkpointDone;
    public StageState featureState = StageState.UNKNOWN;
    public Integer featureDone;
    public String featureSchema;
    public String featureGeneratedAt;

    public StageState labelState = StageState.UNKNOWN;
    public Integer labelDone;
    public Integer labelMissing;
    public Integer labelInvalid;
    public String labelSchema;
    public Long labelTotal;
    public Long labelAnchors;
    public List<Long> horizons = new ArrayList<>();
    public List<HorizonStat> horizonStats = new ArrayList<>();
    public boolean labelAggregateAvailable;
    public JsonNode labelAggregate;

    public CaptureInfo capture = new CaptureInfo(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    public List<SessionInfo> sessions = new ArrayList<>();
    public List<FeatureInfo> features = new ArrayList<>();
    public List<HypothesisInfo> hypotheses = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public Instant lastSessionEnd;

    public Snapshot copy() {
        try {
            Snapshot s = (Snapshot) super.clone();
            s.loadedAt = Instant.now();
            s.schemas = new LinkedHashMap<>(schemas);
            s.hashes = new LinkedHashMap<>(hashes);
            s.paths = new LinkedHashMap<>(paths);
            s.warnings = new ArrayList<>(warnings);
            s.sessions = new ArrayList<>(sessions);
            s.features = new ArrayList<>(features);
            s.hypotheses = new ArrayList<>(hypotheses);
            s.horizons = new ArrayList<>(horizons);
            s.horizonStats = new ArrayList<>(horizonStats);
            return s;
        } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }

    public int labelsTotalSessions() {
        return sessionCount == null ? 0 : sessionCount;
    }

    /** Hash do conteúdo exibível (ignora loadedAt). Permite às telas pular reconstrução quando nada mudou. */
    public int fingerprint() {
        return java.util.Objects.hash(backendVersion, frozenSpecStatus, validationReadiness, campaignId, captureStreams, captureStartedAt, captureStatusAt, recorderHealth, loading, source, backendOnline, backendNote, datasetId, partition, datasetCreatedAt, sessionCount, anchorCount, checkpointSamples,
                schemas, hashes, paths, checkpointState, checkpointDone, featureState, featureDone, featureSchema, featureGeneratedAt,
                labelState, labelDone, labelMissing, labelInvalid, labelSchema, labelTotal, labelAnchors, horizons, horizonStats, labelAggregateAvailable,
                capture, sessions, features, hypotheses, warnings, lastSessionEnd);
    }
}
