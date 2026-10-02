package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.adapter.AdaptiveTraderCli;
import panel.adapter.FileResearchBackend;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.model.StageState;

class FileResearchBackendTest {
    @TempDir
    Path tmp;

    private Snapshot load() {
        Settings s = new Settings();
        s.projectPath = tmp.toString();
        s.cliPath = tmp.resolve("missing-cli").toString();
        return new FileResearchBackend(new AdaptiveTraderCli(() -> s.cliPath)).load(s);
    }

    @Test
    void emptyProjectDoesNotBreak() {
        Snapshot s = load();
        assertNull(s.datasetId);
        assertTrue(s.sessions.isEmpty());
        assertTrue(!s.backendOnline);
    }

    @Test
    void readsPartialLabelsAndToleratesMissingFields() throws Exception {
        Path r = tmp.resolve("reports/research");
        Files.createDirectories(r);
        Files.writeString(r.resolve("train-checkpoint-aggregate.json"),
                "{\"partition\":\"TRAIN\",\"dataset_id\":\"ds1\",\"session_count\":2,\"sample_count\":10,\"sessions\":[{\"session_id\":\"microstructure-20260814T011521Z-usd_m_futures\"},{\"session_id\":\"microstructure-20260814T014523Z-usd_m_futures\"}]}");
        Path lbl = r.resolve("labels/ds1/train/microstructure-20260814T011521Z-usd_m_futures");
        Files.createDirectories(lbl);
        Files.writeString(lbl.resolve("metadata.json"),
                "{\"dataset_id\":\"ds1\",\"status\":\"COMPLETE\",\"anchor_count\":100,\"row_count\":800,\"label_schema_version\":\"pure-forward-mid-v1\",\"horizons\":[250],\"valid_label_counts_by_horizon\":{\"250\":90}}");
        Snapshot s = load();
        assertEquals("ds1", s.datasetId);
        assertEquals(2, s.sessions.size());
        assertEquals(1, s.labelDone);
        assertEquals(1, s.labelMissing);
        assertEquals(StageState.PARTIAL, s.labelState);
        assertEquals("pure-forward-mid-v1", s.labelSchema);
        assertTrue(s.horizonStats.isEmpty());
        assertNull(s.featureDone == null ? null : s.anchorCount);
    }
}
