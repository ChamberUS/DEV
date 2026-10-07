package panel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import panel.model.ScientificCapture;
import panel.model.ScientificCapture.Admission;
import panel.model.ScientificCapture.Status;

/**
 * Estado da captura científica a partir da fonte AUTORITATIVA já existente ({@link CaptureRuntimeResolver}: runtime registrado,
 * supervisor/coletor verificados por identidade, e o arquivo {@code .part} aberto pelo PRÓPRIO coletor), mais a admissão da última
 * sessão fechada. Somente leitura: nunca inicia, para, reinicia, mata nem renomeia nada.
 * <p>
 * Por que não "o .part mais recente": um {@code .part} órfão antigo existe no disco. O resolvedor só considera arquivos que o coletor
 * verificado mantém ABERTOS (lsof do pid verificado), então um órfão nunca é escolhido.
 * <p>
 * Rotação de sessão (a cada ~30 min, 10-25 s sem arquivo aberto): enquanto o coletor verificado segue vivo e a última escrita foi há
 * menos que {@link #ROTATION_GRACE}, o estado fica RUNNING (nada de RUNNING -> STOPPED -> RUNNING). Passada a tolerância sem arquivo,
 * DEGRADED. STOPPED só quando o resolvedor prova ausência.
 */
public final class ScientificCaptureResolver {
    /** Rotação normal: fecha a sessão, 10 s/20 s de NO_ADMITTED_CHUNK, abre a nova. */
    public static final Duration ROTATION_GRACE = Duration.ofSeconds(90);
    /** Um incidente (sessão rejeitada) só vale como "atual" enquanto for a última sessão fechada E recente. */
    public static final Duration INCIDENT_RELEVANCE = Duration.ofHours(2);
    private static final Pattern SESSION = Pattern.compile("microstructure-\\d{8}T\\d{6}Z-usd_m_futures");
    private static final long MAX_ADMISSION_BYTES = 16_384;

    /** Revarredura completa (todos os processos + lsof) no máximo a cada tanto; entre elas vale o caminho rápido verificado. */
    public static final Duration FULL_RESCAN = Duration.ofSeconds(60);

    @FunctionalInterface
    public interface RuntimeReader {
        CaptureRuntimeResolver.Runtime read(Path state, Path storage, Instant now) throws IOException;
    }

    /** Um processo conhecido pelo pid (teste injeta o seu). */
    public record Proc(boolean alive, Instant start, List<String> args, Long parent) {
    }

    @FunctionalInterface
    public interface Processes {
        java.util.Optional<Proc> find(long pid);

        static Processes system() {
            return pid -> ProcessHandle.of(pid).map(h -> new Proc(h.isAlive(), h.info().startInstant().orElse(null),
                    h.info().arguments().map(java.util.Arrays::asList).orElse(null), h.parent().map(ProcessHandle::pid).orElse(null)));
        }
    }

    /**
     * O que a última leitura COMPLETA verificou: coletor e supervisor (pid + instante de início + argumentos) e o único .part aberto
     * pelo coletor. Entre revarreduras, o mesmo pid só vale se continuar com o mesmo início/argumentos/pai (nenhum reuso de pid).
     */
    private record Verified(CaptureRuntimeResolver.Runtime base, Instant collectorStart, List<String> collectorArgs, Instant supervisorStart,
            Path partFile, Instant fullAt) {
    }

    private final RuntimeReader runtime;
    private final Path state;
    private final Path storage;
    private final Processes processes;
    private final Duration fullRescan;
    private Verified verified;
    private Instant lastRunningAt;
    private Instant lastWriteSeen;
    private String lastSession;

    public ScientificCaptureResolver(RuntimeReader runtime, Path state, Path storage) {
        this(runtime, state, storage, Processes.system(), FULL_RESCAN);
    }

    public ScientificCaptureResolver(RuntimeReader runtime, Path state, Path storage, Processes processes, Duration fullRescan) {
        this.runtime = runtime;
        this.state = state;
        this.storage = storage;
        this.processes = processes;
        this.fullRescan = fullRescan;
    }

    public static ScientificCaptureResolver forLocal(Path state, Path storage, Path cli) {
        CaptureRuntimeResolver resolver = new CaptureRuntimeResolver(cli);
        return new ScientificCaptureResolver(resolver::read, state, storage);
    }

    public synchronized ScientificCapture observe(Instant now) {
        CaptureRuntimeResolver.Runtime rt;
        try {
            rt = fast(now);
            if (rt == null) {
                rt = runtime.read(state, storage, now);
                remember(rt, now);
            }
        } catch (IOException | RuntimeException e) {
            verified = null;
            return ScientificCapture.unknown("runtime not readable (" + e.getClass().getSimpleName() + ")", now);
        }
        if (rt == null) {
            return ScientificCapture.unknown("runtime not resolved", now);
        }
        Admission admission = Admission.UNKNOWN;
        String admissionReason = null;
        String hash = null;
        Closed closed = null;
        try {
            closed = latestClosedAdmission(rt.session());
        } catch (IOException | RuntimeException e) {
            // admissão ilegível: não rebaixa nem promove o estado do processo
        }
        Instant closedAt = null;
        if (closed != null && closed.json() != null) {
            JsonNode latest = closed.json();
            hash = text(latest, "recorder_config_hash");
            boolean admitted = "ADMITTED".equalsIgnoreCase(text(latest, "status")) || latest.path("admitted").asBoolean(false);
            admission = admitted ? Admission.ADMITTED : Admission.REJECTED;
            admissionReason = admitted ? null : text(latest, "reasons");
            closedAt = closed.closedAt();
        } else if (closed != null) {
            admission = Admission.NONE;
        }
        Duration age = rt.lastWrite() == null ? null : Duration.between(rt.lastWrite(), now);
        Status status;
        String reason;
        switch (rt.state()) {
            case RUNNING -> {
                status = Status.RUNNING;
                reason = null;
                lastRunningAt = now;
                lastWriteSeen = rt.lastWrite();
                lastSession = rt.session();
            }
            case STALE -> {
                status = Status.DEGRADED;
                reason = "writer silent " + (age == null ? "?" : Math.max(0, age.toSeconds())) + " s";
            }
            case STOPPED -> {
                status = Status.STOPPED;
                reason = "no registered collector or supervisor is alive";
                lastRunningAt = null;
            }
            default -> {
                // UNKNOWN do resolvedor: coletor verificado sem arquivo aberto = rotação, dentro da tolerância
                if (rt.alive() && rt.collector() != null && rt.supervisor() != null) {
                    if (lastRunningAt != null && Duration.between(lastRunningAt, now).compareTo(ROTATION_GRACE) <= 0) {
                        status = Status.RUNNING;
                        reason = "session rotating";
                    } else {
                        status = Status.DEGRADED;
                        reason = "collector alive but no active writer file";
                    }
                } else {
                    status = Status.UNKNOWN;
                    reason = rt.warnings().isEmpty() ? "no sufficient evidence" : rt.warnings().getFirst();
                }
            }
        }
        // incidente ATUAL: a última sessão fechada foi rejeitada, é recente, e o processo está vivo (se parou, STOPPED já diz tudo)
        if (admission == Admission.REJECTED && closedAt != null && Duration.between(closedAt, now).compareTo(INCIDENT_RELEVANCE) <= 0
                && (status == Status.RUNNING)) {
            status = Status.DEGRADED;
            reason = "last closed session rejected" + (admissionReason == null || admissionReason.isBlank() ? "" : " (" + admissionReason + ")");
        } else if (admission == Admission.REJECTED && (closedAt == null || Duration.between(closedAt, now).compareTo(INCIDENT_RELEVANCE) > 0)) {
            admission = Admission.UNKNOWN; // incidente histórico: não é degradação presente
            admissionReason = null;
        }
        return new ScientificCapture(status, reason, rt.campaign(), hash, rt.session() != null ? rt.session() : status == Status.RUNNING ? lastSession : null,
                age, rt.supervisor(), rt.collector(), admission, admissionReason, now);
    }

    // ---- caminho rápido: re-verifica o que a leitura completa já provou (custo ~0 em vez de varrer todos os processos + lsof) ------------

    private CaptureRuntimeResolver.Runtime fast(Instant now) throws IOException {
        Verified v = verified;
        if (v == null || Duration.between(v.fullAt(), now).compareTo(fullRescan) > 0 || now.isBefore(v.fullAt())) {
            return null;
        }
        CaptureRuntimeResolver.Runtime b = v.base();
        try {
            Path campaignFile = state.resolve("current_campaign");
            if (!Files.isRegularFile(campaignFile, LinkOption.NOFOLLOW_LINKS) || Files.size(campaignFile) > 4096
                    || !Files.readString(campaignFile).strip().equals(b.campaign())) {
                return null; // campanha mudou: leitura completa decide
            }
            Proc collector = processes.find(b.collector()).orElse(null);
            Proc supervisor = processes.find(b.supervisor()).orElse(null);
            if (collector == null || supervisor == null || !collector.alive() || !supervisor.alive()
                    || collector.start() == null || !collector.start().equals(v.collectorStart())
                    || collector.args() == null || !collector.args().equals(v.collectorArgs())
                    || !Long.valueOf(b.supervisor()).equals(collector.parent())
                    || supervisor.start() == null || !supervisor.start().equals(v.supervisorStart())) {
                return null; // qualquer divergência (morreu, reiniciou, pid reutilizado): leitura completa decide
            }
            if (v.partFile() == null || !Files.isRegularFile(v.partFile(), LinkOption.NOFOLLOW_LINKS)) {
                return null; // chunk fechado/rotação: o lsof reencontra o arquivo aberto
            }
            Instant written = Files.getLastModifiedTime(v.partFile(), LinkOption.NOFOLLOW_LINKS).toInstant();
            CaptureSnapshotState st = written.isAfter(now.plusSeconds(5)) ? CaptureSnapshotState.UNKNOWN
                    : Duration.between(written, now).compareTo(CaptureRuntimeResolver.STALE_AFTER) > 0 ? CaptureSnapshotState.STALE : CaptureSnapshotState.RUNNING;
            List<String> warnings = new ArrayList<>(b.warnings());
            if (st == CaptureSnapshotState.STALE) {
                warnings.add("Writer inactive for more than " + CaptureRuntimeResolver.STALE_AFTER.toSeconds() + " seconds");
            }
            return new CaptureRuntimeResolver.Runtime(st.state, b.supervisor(), b.collector(), true, b.start(), b.campaign(), b.symbol(), b.market(),
                    b.session(), written, warnings);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private enum CaptureSnapshotState {
        RUNNING(panel.model.CaptureSnapshot.State.RUNNING), STALE(panel.model.CaptureSnapshot.State.STALE), UNKNOWN(panel.model.CaptureSnapshot.State.UNKNOWN);
        final panel.model.CaptureSnapshot.State state;

        CaptureSnapshotState(panel.model.CaptureSnapshot.State state) {
            this.state = state;
        }
    }

    /** Depois de uma leitura completa com coletor E supervisor verificados e UM único .part na sessão ativa, guarda o que foi provado. */
    private void remember(CaptureRuntimeResolver.Runtime rt, Instant now) {
        verified = null;
        if (rt == null || !rt.alive() || rt.collector() == null || rt.supervisor() == null || rt.session() == null || rt.campaign() == null
                || rt.lastWrite() == null) {
            return;
        }
        Proc collector = processes.find(rt.collector()).orElse(null);
        Proc supervisor = processes.find(rt.supervisor()).orElse(null);
        if (collector == null || supervisor == null || collector.start() == null || collector.args() == null || supervisor.start() == null
                || !Long.valueOf(rt.supervisor()).equals(collector.parent())) {
            return;
        }
        Path part = onlyPartOf(rt.session());
        if (part == null) {
            return;
        }
        verified = new Verified(rt, collector.start(), collector.args(), supervisor.start(), part, now);
    }

    /** O único .part do diretório da sessão ativa; mais de um (ou nenhum) = sem cache, sempre a leitura completa. */
    private Path onlyPartOf(String session) {
        try {
            for (Path day : dayDirs().stream().limit(2).toList()) {
                Path dir = day.resolve(session);
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    try (var files = Files.list(dir)) {
                        List<Path> parts = files.filter(f -> f.getFileName().toString().endsWith(".jsonl.gz.part")
                                && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList();
                        return parts.size() == 1 ? parts.getFirst() : null;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // sem cache
        }
        return null;
    }

    /** Diretórios de dia (mercado/símbolo/AAAA-MM-DD), do mais recente para o mais antigo. */
    private List<Path> dayDirs() throws IOException {
        List<Path> days = new ArrayList<>();
        try (var markets = Files.list(storage)) {
            for (Path market : markets.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).limit(8).toList()) {
                try (var symbols = Files.list(market)) {
                    for (Path symbol : symbols.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).limit(8).toList()) {
                        try (var d = Files.list(symbol)) {
                            d.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(days::add);
                        }
                    }
                }
            }
        }
        days.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return days;
    }

    // ---- admissão da última sessão FECHADA da campanha atual -------------------------------------------------------------------------

    /** json == null: procurou e não há sessão fechada para julgar. */
    private record Closed(JsonNode json, Instant closedAt) {
    }

    /**
     * A sessão fechada mais recente é a de maior nome (carimbo UTC no nome) que tem scientific_admission.json e NÃO é a sessão ativa.
     * Procura só nos dois dias mais recentes do mercado/símbolo da sessão ativa (ou do último dia disponível), sem varrer o armazenamento.
     */
    private Closed latestClosedAdmission(String activeSession) throws IOException {
        if (!Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        List<Path> days = dayDirs();
        Path best = null;
        for (Path day : days.stream().limit(2).toList()) {
            try (var sessions = Files.list(day)) {
                for (Path s : sessions.toList()) {
                    String name = s.getFileName().toString();
                    if (!SESSION.matcher(name).matches() || name.equals(activeSession)) {
                        continue;
                    }
                    if (!Files.isRegularFile(s.resolve("scientific_admission.json"), LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (best == null || name.compareTo(best.getFileName().toString()) > 0) {
                        best = s;
                    }
                }
            }
        }
        if (best == null) {
            return new Closed(null, null);
        }
        Path file = best.resolve("scientific_admission.json");
        if (Files.size(file) > MAX_ADMISSION_BYTES) {
            throw new IOException("Admission file exceeds limit");
        }
        return new Closed(new ObjectMapper().readTree(file.toFile()), Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
