package panel.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Leitura somente-leitura dos artefatos TRAIN gerados pelo backend Python. */
public class ResearchReportRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path reports;
    private final List<String> warnings;

    public ResearchReportRepository(Path reports, List<String> warnings) {
        this.reports = reports;
        this.warnings = warnings;
    }

    public Path reports() {
        return reports;
    }

    public JsonNode json(Path file) {
        String normalized = file.toAbsolutePath().normalize().toString().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("VALIDATION") || normalized.contains("HOLDOUT")) {
            warnings.add("Blocked protected partition path");
            return null;
        }
        try {
            if (Files.exists(file) && (!file.toRealPath().startsWith(reports.toRealPath())
                    || file.toRealPath().toString().toUpperCase(java.util.Locale.ROOT).contains("VALIDATION")
                    || file.toRealPath().toString().toUpperCase(java.util.Locale.ROOT).contains("HOLDOUT"))) {
                warnings.add("Blocked report outside TRAIN report root");
                return null;
            }
        } catch (IOException e) {
            warnings.add("Report path unavailable");
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException e) {
            warnings.add("Não foi possível ler " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    public JsonNode report(String name) {
        return json(reports.resolve(name));
    }

    public Path stageDir(String stage, String datasetId, String sessionId) {
        return reports.resolve(stage).resolve(datasetId).resolve("train").resolve(sessionId);
    }

    /** Lê somente o cabeçalho do CSV gzip de features. */
    public List<String> csvHeader(Path gz) {
        if (!Files.isRegularFile(gz)) {
            return List.of();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? List.of() : Arrays.asList(line.split(","));
        } catch (IOException e) {
            warnings.add("Cabeçalho de features ilegível: " + e.getMessage());
            return List.of();
        }
    }
}
