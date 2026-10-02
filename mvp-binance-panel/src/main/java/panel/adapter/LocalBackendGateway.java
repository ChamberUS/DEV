package panel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import panel.model.CaptureInfo;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.process.ProcessRunner;
import panel.util.Json;

/** Composes the versioned Python status contract with existing TRAIN report adapters. */
public final class LocalBackendGateway implements BackendGateway {
    @FunctionalInterface
    public interface StatusTransport { String read(Settings settings, boolean healthOnly) throws Exception; }
    private final ResearchBackend reports;
    private final StatusTransport transport;
    private Snapshot cached;
    private JsonNode research;
    private long nextResearch;
    private String config;

    public LocalBackendGateway(ResearchBackend reports) {
        this(reports, (settings, healthOnly) -> {
            Path python = Path.of(settings.cliPath).toAbsolutePath().getParent().resolve("python");
            var args = new java.util.ArrayList<>(List.of(python.toString(), "-m",
                    "adaptive_trader.panel_status", "--project", settings.project().toString()));
            if (healthOnly) args.add("--health-only");
            return new ProcessRunner().capture(args, settings.project(), 8);
        });
    }

    public LocalBackendGateway(ResearchBackend reports, StatusTransport transport) {
        this.reports = reports;
        this.transport = transport;
    }

    @Override
    public synchronized void invalidate() { nextResearch = 0; }

    @Override
    public synchronized Snapshot load(Settings settings) {
        try {
            String current = settings.projectPath + "|" + settings.cliPath + "|" + settings.reportsPath;
            boolean reload = cached == null || !current.equals(config) || System.nanoTime() >= nextResearch;
            JsonNode root = new ObjectMapper().readTree(transport.read(settings, !reload));
            if (root == null || root.path("schema_version").asInt() != 1
                    || !root.path("system").path("online").asBoolean(false)) {
                throw new IllegalArgumentException("Invalid backend status contract");
            }
            if (reload) {
                cached = reports.load(settings);
                research = Json.get(root, "research");
                config = current;
                nextResearch = System.nanoTime() + Math.max(15, settings.researchPollSeconds) * 1_000_000_000L;
            }
            Snapshot s = cached.copy();
            s.backendOnline = true;
            s.backendNote = "Local backend · read-only";
            s.backendVersion = Json.str(Json.get(root, "system"), "version");
            s.paths.put("project", Json.str(Json.get(root, "system"), "project_path"));
            if (research == null) s.warnings.add("Missing research fields in backend contract");
            s.frozenSpecStatus = Json.str(research, "frozen_spec_status");
            s.validationReadiness = Json.str(research, "validation_readiness");
            JsonNode c = Json.get(root, "capture");
            JsonNode running = Json.get(c, "running");
            String recorder = running == null ? null : running.asBoolean() ? "RUNNING" : "STOPPED";
            s.campaignId = Json.str(c, "campaign_id");
            s.captureStreams = Json.str(c, "streams");
            s.captureStartedAt = Json.instant(c, "started_at");
            s.captureStatusAt = Json.instant(c, "last_status_at");
            s.recorderHealth = Json.str(c, "recorder_health");
            Long uptime = Json.lng(c, "uptime_seconds");
            s.capture = new CaptureInfo(recorder, null, c == null ? null : "Binance",
                    Json.str(c, "market"), Json.str(c, "symbol"), Json.str(c, "session_id"),
                    uptime == null ? null : uptime + " s", null, null, null, null, null,
                    Json.instant(c, "last_event_at"), Json.str(c, "disk_usage_bytes"),
                    Json.str(c, "storage_path"), null);
            JsonNode warnings = Json.get(root, "warnings");
            if (warnings != null && warnings.isArray()) warnings.forEach(w -> s.warnings.add(w.asText()));
            if (c == null) s.warnings.add("Missing capture fields in backend contract");
            return s;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            nextResearch = 0;
            Snapshot s = new Snapshot();
            s.backendNote = "BACKEND OFFLINE";
            s.warnings.add("Backend status unavailable (" + e.getClass().getSimpleName() + ")");
            return s;
        }
    }
}
