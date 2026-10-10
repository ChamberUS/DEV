package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.adapter.CaptureRuntimeResolver;
import panel.adapter.CaptureRuntimeResolver.Activity;
import panel.adapter.CaptureRuntimeResolver.Process;
import panel.adapter.ScientificCaptureResolver;
import panel.model.CaptureSnapshot.State;
import panel.model.ScientificCapture;
import panel.model.ScientificCapture.Admission;
import panel.model.ScientificCapture.Status;

/** Modelo fechado RUNNING/DEGRADED/STOPPED/UNKNOWN sobre a fonte autoritativa (resolvedor de runtime + admissão da última sessão fechada). */
class ScientificCaptureResolverTest {
    static final Instant NOW = Instant.parse("2026-10-07T20:00:00Z");
    static final String CAMPAIGN = "ethusdt-futures-continuous-20261002T204051Z";
    static final String HASH = "8a6f459323958517c345c82418f2c16d9e293f4c351d41aa4a9ce6091b561dec";
    @TempDir Path temp;
    Path state;
    Path root;
    Path runtime;
    Path checkout;

    /** Resolvedor real com processos e "arquivo aberto" sintéticos (a lógica de identidade/campanha é a de produção). */
    final class Resolver extends CaptureRuntimeResolver {
        volatile List<Process> observations = List.of();
        volatile Activity activity;
        Resolver() { super(temp.resolve("project/.venv/bin/adaptive-trader")); }
        @Override protected List<Process> processes() { return observations; }
        @Override protected Activity activity(long pid, Path root) { return activity; }
    }

    Resolver resolver;
    ScientificCaptureResolver sci;

    @BeforeEach void setup() throws Exception {
        state = Files.createDirectories(temp.resolve("state"));
        root = Files.createDirectories(temp.resolve("project/data/microstructure"));
        runtime = Files.createDirectories(state.resolve("runtime/current"));
        checkout = temp.resolve("project-capture-current");
        Files.createDirectories(checkout.resolve("scripts"));
        Files.writeString(checkout.resolve("scripts/continuous_capture.sh"), "synthetic launcher");
        Path activation = Files.createDirectories(state.resolve("recovery/current")).resolve("activation.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(activation.toFile(), Map.of(
                "checkout", checkout.toString(), "runtime", runtime.toString(), "campaign_id", CAMPAIGN));
        Files.writeString(state.resolve("current_campaign"), CAMPAIGN);
        Files.writeString(state.resolve("capture.pid"), "5179");
        resolver = new Resolver();
        resolver.activity = new Activity("microstructure-20261007T195900Z-usd_m_futures", NOW.minusSeconds(2));
        running();
        sci = new ScientificCaptureResolver(resolver::read, state, root);
    }

    void running() {
        resolver.observations = List.of(
                new Process(5179, 1L, NOW.minusSeconds(3600), "/bin/bash", List.of(checkout.resolve("scripts/continuous_capture.sh").toString().replace('\\', '/'))),
                new Process(5186, 5179L, NOW.minusSeconds(3599), "/usr/bin/python3", List.of(runtime.resolve("bin/adaptive-trader").toString().replace('\\', '/'),
                        "market", "microstructure", "campaign-record", "--campaign-id", CAMPAIGN, "--market", "futures", "--symbol", "ETHUSDT",
                        "--output-dir", root.toString())));
    }

    Path closedSession(String name, boolean admitted, String reasons, Instant closedAt) throws IOException {
        Path dir = Files.createDirectories(root.resolve("futures/ETHUSDT/2026-10-07/" + name));
        Path f = dir.resolve("scientific_admission.json");
        Files.writeString(f, "{\"status\":\"" + (admitted ? "ADMITTED" : "REJECTED") + "\",\"admitted\":" + admitted + ",\"reasons\":\"" + reasons
                + "\",\"recorder_config_hash\":\"" + HASH + "\"}");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.from(closedAt));
        return dir;
    }

    @Test void runningHasCampaignHashAndRecentWrite() throws Exception {
        closedSession("microstructure-20261007T193000Z-usd_m_futures", true, "", NOW.minusSeconds(900));
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.RUNNING, c.status());
        assertEquals(CAMPAIGN, c.campaign());
        assertEquals(HASH, c.configHash());
        assertEquals(Admission.ADMITTED, c.admission());
        assertEquals(Duration.ofSeconds(2), c.lastWriteAge());
        assertEquals(5179L, c.supervisorPid());
        assertEquals(5186L, c.collectorPid());
    }

    @Test void writerSilentBeyondTheLimitWithoutProvenDeathIsDegradedNotStopped() {
        resolver.activity = new Activity("microstructure-20261007T195900Z-usd_m_futures", NOW.minusSeconds(45));
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.DEGRADED, c.status());
        assertTrue(c.reason().contains("writer silent"));
    }

    @Test void provenAbsenceIsStoppedButUncertainEvidenceNeverIs() {
        resolver.observations = List.of();
        Files.exists(state); // supervisor registrado (5179) já não existe: ausência provada
        assertEquals(Status.STOPPED, sci.observe(NOW).status());
        // supervisor registrado ainda vivo, mas sem coletor verificado (respawn): evidência insuficiente => UNKNOWN, nunca STOPPED
        resolver.observations = List.of(new Process(5179, 1L, NOW.minusSeconds(3600), "/bin/bash",
                List.of(checkout.resolve("scripts/continuous_capture.sh").toString().replace('\\', '/'))));
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.UNKNOWN, c.status());
    }

    @Test void resolverFailureOrAmbiguityIsUnknown() {
        ScientificCaptureResolver broken = new ScientificCaptureResolver((s, r, n) -> { throw new IOException("boom"); }, state, root);
        assertEquals(Status.UNKNOWN, broken.observe(NOW).status());
        Process original = resolver.observations.getLast();
        List<Process> two = new ArrayList<>(resolver.observations);
        two.add(new Process(6000, original.parent(), original.start(), original.command(), original.args()));
        resolver.observations = two;
        assertEquals(Status.UNKNOWN, sci.observe(NOW).status());
    }

    @Test void sessionRotationDoesNotFlapThroughStoppedOrUnknown() {
        assertEquals(Status.RUNNING, sci.observe(NOW).status());
        // fecha a sessão: sem arquivo aberto durante o NO_ADMITTED_CHUNK 10 s / 20 s
        resolver.activity = new Activity(null, null);
        for (int s : new int[] {5, 15, 35, 60, 85}) {
            ScientificCapture c = sci.observe(NOW.plusSeconds(s));
            assertEquals(Status.RUNNING, c.status(), "rotation at +" + s + " s must not flap");
            assertEquals("session rotating", c.reason());
        }
        // nova sessão abre
        resolver.activity = new Activity("microstructure-20261007T200100Z-usd_m_futures", NOW.plusSeconds(100));
        assertEquals(Status.RUNNING, sci.observe(NOW.plusSeconds(101)).status());
    }

    @Test void noWriterFileBeyondRotationGraceIsDegraded() {
        sci.observe(NOW);
        resolver.activity = new Activity(null, null);
        ScientificCapture c = sci.observe(NOW.plus(ScientificCaptureResolver.ROTATION_GRACE).plusSeconds(1));
        assertEquals(Status.DEGRADED, c.status());
        // sem histórico (partida a frio) e sem arquivo aberto: o coletor está vivo, mas não há como dizer RUNNING
        ScientificCaptureResolver cold = new ScientificCaptureResolver(resolver::read, state, root);
        assertEquals(Status.DEGRADED, cold.observe(NOW).status());
    }

    @Test void orphanOldPartIsIgnoredBecauseOnlyTheVerifiedCollectorsOpenFileCounts() throws Exception {
        // .part órfão antigo (nunca aberto pelo coletor) + .part ativo novo (aberto): vale o ativo
        Path oldDir = Files.createDirectories(root.resolve("futures/ETHUSDT/2026-10-05/microstructure-20261005T120000Z-usd_m_futures"));
        Path orphan = Files.writeString(oldDir.resolve("events-00007.jsonl.gz.part"), "old");
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofDays(2))));
        Path active = Files.createDirectories(root.resolve("futures/ETHUSDT/2026-10-07/microstructure-20261007T195900Z-usd_m_futures"));
        Files.writeString(active.resolve("events-00003.jsonl.gz.part"), "new");
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.RUNNING, c.status());
        assertEquals("microstructure-20261007T195900Z-usd_m_futures", c.session());
        assertTrue(c.lastWriteAge().compareTo(Duration.ofMinutes(1)) < 0, "age comes from the open file, not from the orphan");
    }

    @Test void activeSessionIsNotJudgedAndLatestClosedSessionDecidesAdmission() throws Exception {
        closedSession("microstructure-20261007T190000Z-usd_m_futures", false, "UNRESOLVED_INCIDENT", NOW.minus(Duration.ofMinutes(100)));
        closedSession("microstructure-20261007T193000Z-usd_m_futures", true, "", NOW.minusSeconds(900));
        // a ativa (195900Z) tem admissão ainda incompleta: não conta
        closedSession("microstructure-20261007T195900Z-usd_m_futures", false, "IN_PROGRESS", NOW.minusSeconds(1));
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.RUNNING, c.status());
        assertEquals(Admission.ADMITTED, c.admission(), "the newest CLOSED session (not the active one, not older rejected ones) decides");
    }

    @Test void currentIncidentDegradesButHistoricalRejectionDoesNot() throws Exception {
        closedSession("microstructure-20261007T193000Z-usd_m_futures", false, "STREAM_LIVENESS_INVALID", NOW.minusSeconds(600));
        ScientificCapture current = sci.observe(NOW);
        assertEquals(Status.DEGRADED, current.status());
        assertTrue(current.reason().contains("STREAM_LIVENESS_INVALID"));
        assertEquals(Admission.REJECTED, current.admission());
        // o mesmo arquivo, muito antigo: histórico, não é degradação presente
        Path f = root.resolve("futures/ETHUSDT/2026-10-07/microstructure-20261007T193000Z-usd_m_futures/scientific_admission.json");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.from(NOW.minus(Duration.ofHours(30))));
        ScientificCapture old = sci.observe(NOW);
        assertEquals(Status.RUNNING, old.status());
        assertEquals(Admission.UNKNOWN, old.admission());
    }

    @Test void unreadableAdmissionNeverChangesTheProcessVerdict() throws Exception {
        Path dir = Files.createDirectories(root.resolve("futures/ETHUSDT/2026-10-07/microstructure-20261007T193000Z-usd_m_futures"));
        Files.writeString(dir.resolve("scientific_admission.json"), "{not json");
        ScientificCapture c = sci.observe(NOW);
        assertEquals(Status.RUNNING, c.status());
        assertEquals(Admission.UNKNOWN, c.admission());
        assertNull(c.configHash());
        assertNotNull(c.checkedAt());
    }

    @Test void legacyStateEnumCoversTheSameVocabulary() {
        for (State s : State.values()) {
            assertNotNull(s);
        }
    }

    // ---- caminho rápido verificado -------------------------------------------------------------------------------------------------

    /** Processos sintéticos coerentes com {@link #running()}; o teste altera o mapa para simular reuso de pid, morte, etc. */
    final class FakeProcesses implements ScientificCaptureResolver.Processes {
        final java.util.Map<Long, ScientificCaptureResolver.Proc> procs = new java.util.concurrent.ConcurrentHashMap<>();
        FakeProcesses() {
            for (Process p : resolver.observations) {
                procs.put(p.pid(), new ScientificCaptureResolver.Proc(true, p.start(), p.args(), p.parent()));
            }
        }
        @Override public java.util.Optional<ScientificCaptureResolver.Proc> find(long pid) { return java.util.Optional.ofNullable(procs.get(pid)); }
    }

    Path activePart() throws IOException {
        Path dir = Files.createDirectories(root.resolve("futures/ETHUSDT/2026-10-07/microstructure-20261007T195900Z-usd_m_futures"));
        Path part = Files.writeString(dir.resolve("events-00003.jsonl.gz.part"), "x");
        Files.setLastModifiedTime(part, java.nio.file.attribute.FileTime.from(NOW.minusSeconds(2)));
        return part;
    }

    @Test void fastPathSkipsTheFullScanBetweenRescansAndStillSeesNewWrites() throws Exception {
        Path part = activePart();
        FakeProcesses procs = new FakeProcesses();
        java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new ScientificCaptureResolver((st, r, n) -> { full.incrementAndGet(); return resolver.read(st, r, n); }, state, root, procs, Duration.ofSeconds(60));
        assertEquals(Status.RUNNING, counting.observe(NOW).status());
        assertEquals(1, full.get());
        for (int i = 1; i <= 5; i++) {
            Files.setLastModifiedTime(part, java.nio.file.attribute.FileTime.from(NOW.plusSeconds(i * 10L - 1)));
            ScientificCapture c = counting.observe(NOW.plusSeconds(i * 10L));
            assertEquals(Status.RUNNING, c.status());
            assertEquals(Duration.ofSeconds(1), c.lastWriteAge(), "fast path reads the real mtime of the verified part file");
        }
        assertEquals(1, full.get(), "5 polls inside the rescan window never repeat the full scan");
        resolver.activity = new Activity("microstructure-20261007T195900Z-usd_m_futures", NOW.plusSeconds(60));
        assertEquals(Status.RUNNING, counting.observe(NOW.plusSeconds(61)).status());
        assertEquals(2, full.get(), "after FULL_RESCAN the full authoritative read runs again");
    }

    @Test void fastPathNeverTrustsAReusedPidOrADeadCollectorOrAChangedCampaign() throws Exception {
        Path part = activePart();
        FakeProcesses procs = new FakeProcesses();
        java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new ScientificCaptureResolver((st, r, n) -> { full.incrementAndGet(); return resolver.read(st, r, n); }, state, root, procs, Duration.ofSeconds(60));
        counting.observe(NOW);
        // 1) pid reutilizado: mesmo pid, outro instante de início
        var orig = procs.procs.get(5186L);
        procs.procs.put(5186L, new ScientificCaptureResolver.Proc(true, orig.start().plusSeconds(500), orig.args(), orig.parent()));
        counting.observe(NOW.plusSeconds(10));
        assertEquals(2, full.get(), "reused pid => full authoritative read");
        procs.procs.put(5186L, orig);
        counting.observe(NOW.plusSeconds(20)); // reprovado e relido; cache refeito
        full.set(0);
        // 2) coletor morreu
        procs.procs.put(5186L, new ScientificCaptureResolver.Proc(false, orig.start(), orig.args(), orig.parent()));
        resolver.observations = List.of();
        assertEquals(Status.STOPPED, counting.observe(NOW.plusSeconds(30)).status());
        assertTrue(full.get() >= 1, "dead collector => full read decides (and proves STOPPED)");
        // 3) campanha trocou
        running();
        procs.procs.put(5186L, orig);
        counting.observe(NOW.plusSeconds(40));
        full.set(0);
        Files.writeString(state.resolve("current_campaign"), "other-campaign");
        counting.observe(NOW.plusSeconds(50));
        assertTrue(full.get() >= 1, "campaign change => full read");
        Files.writeString(state.resolve("current_campaign"), CAMPAIGN);
        assertNotNull(part);
    }

    @Test void fastPathFallsBackWhenTheChunkFileIsFinalized() throws Exception {
        Path part = activePart();
        FakeProcesses procs = new FakeProcesses();
        java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new ScientificCaptureResolver((st, r, n) -> { full.incrementAndGet(); return resolver.read(st, r, n); }, state, root, procs, Duration.ofSeconds(60));
        counting.observe(NOW);
        Files.move(part, part.resolveSibling("events-00003.jsonl.gz")); // chunk fechado: o .part deixou de existir
        resolver.activity = new Activity("microstructure-20261007T195900Z-usd_m_futures", NOW.plusSeconds(9));
        counting.observe(NOW.plusSeconds(10));
        assertEquals(2, full.get(), "finalized chunk => the lsof-based read finds the new open file");
    }

    @Test void twoPartFilesInTheActiveSessionDisableTheCacheInsteadOfGuessing() throws Exception {
        Path part = activePart();
        Files.writeString(part.resolveSibling("events-00002.jsonl.gz.part"), "orphan-in-same-dir");
        FakeProcesses procs = new FakeProcesses();
        java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
        var counting = new ScientificCaptureResolver((st, r, n) -> { full.incrementAndGet(); return resolver.read(st, r, n); }, state, root, procs, Duration.ofSeconds(60));
        counting.observe(NOW);
        counting.observe(NOW.plusSeconds(10));
        counting.observe(NOW.plusSeconds(20));
        assertEquals(3, full.get(), "ambiguous .part => always the authoritative lsof read");
    }
}
