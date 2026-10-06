package panel.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import panel.model.CaptureSnapshot.State;

/** Read-only resolution of registered capture runtimes and their current writer. */
public class CaptureRuntimeResolver {
    // Operational writer silence, independent of scientific stream liveness/admission.
    public static final Duration STALE_AFTER = Duration.ofSeconds(30);
    public record Process(long pid, Long parent, Instant start, String command, List<String> args) { }
    public record Activity(String session, Instant written) { }
    public record Runtime(State state, Long supervisor, Long collector, boolean alive, Instant start,
            String campaign, String symbol, String market, String session, Instant lastWrite,
            List<String> warnings) { }
    private final Path cli;
    public CaptureRuntimeResolver(Path cli) { this.cli = cli.toAbsolutePath().normalize(); }

    protected List<Process> processes() {
        try (var handles = ProcessHandle.allProcesses()) {
            return handles.filter(ProcessHandle::isAlive).map(h -> {
                var info = h.info();
                return new Process(h.pid(), h.parent().map(ProcessHandle::pid).orElse(null),
                        info.startInstant().orElse(null), info.command().orElse(null),
                        info.arguments().map(Arrays::asList).orElse(null));
            }).toList();
        }
    }

    protected Activity activity(long collector, Path root) throws IOException {
        var lookup = new ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-p", Long.toString(collector), "-Fn")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!lookup.waitFor(2, TimeUnit.SECONDS)) {
                lookup.destroyForcibly(); // Only our read-only lsof subprocess.
                throw new IOException("Writer file lookup timed out");
            }
            byte[] result = lookup.getInputStream().readNBytes(65537);
            if (result.length > 65536) throw new IOException("Writer file lookup exceeds limit");
            Path selected = null; Instant latest = null;
            for (String line : new String(result, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (!line.startsWith("n") || !line.endsWith(".jsonl.gz.part")) continue;
                Path file = Path.of(line.substring(1)).toAbsolutePath().normalize();
                if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!file.getParent().getFileName().toString().matches("microstructure-\\d{8}T\\d{6}Z-usd_m_futures")) continue;
                Instant written = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (latest == null || written.isAfter(latest)) { selected = file; latest = written; }
            }
            return new Activity(selected == null ? null : selected.getParent().getFileName().toString(), latest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); lookup.destroyForcibly();
            throw new IOException("Writer lookup interrupted", e);
        }
    }

    private static String text(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        if (Files.size(path) > 4096) throw new IOException("State file exceeds limit");
        return Files.readString(path).strip();
    }
    private static String option(List<String> args, String name) {
        int i = args.indexOf(name); return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
    }
    private static Path executable(Process p) {
        if (p.args() != null) for (String a : p.args()) {
            if (a.endsWith("/bin/adaptive-trader")) return Path.of(a).toAbsolutePath().normalize();
        }
        return p.command() != null && p.command().endsWith("/bin/adaptive-trader")
                ? Path.of(p.command()).toAbsolutePath().normalize() : null;
    }

    public Runtime read(Path state, Path storage, Instant now) throws IOException {
        state = state.toAbsolutePath().normalize(); storage = storage.toAbsolutePath().normalize();
        var warnings = new ArrayList<String>();
        String campaign = text(state.resolve("current_campaign"));
        String registeredPid = text(state.resolve("capture.pid"));
        Set<Path> launchers = new HashSet<>(), executables = new HashSet<>();
        launchers.add(state.resolve("continuous_capture.sh")); executables.add(cli);
        Path recovery = state.resolve("recovery");
        if (Files.isDirectory(recovery)) {
            try (var dirs = Files.list(recovery)) {
                for (Path dir : dirs.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().limit(128).toList()) {
                    Path activation = dir.resolve("activation.json");
                    if (!Files.isRegularFile(activation, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.size(activation) > 32768) throw new IOException("Activation metadata exceeds limit");
                    var metadata = new ObjectMapper().readTree(activation.toFile());
                    if (metadata.hasNonNull("checkout")) launchers.add(Path.of(metadata.get("checkout").asText()).resolve("scripts/continuous_capture.sh").toAbsolutePath().normalize());
                    if (metadata.hasNonNull("runtime")) {
                        Path runtime = Path.of(metadata.get("runtime").asText()).toAbsolutePath().normalize();
                        if (runtime.startsWith(state.resolve("runtime"))) executables.add(runtime.resolve("bin/adaptive-trader"));
                    }
                    if (campaign == null && metadata.hasNonNull("campaign_id")) campaign = metadata.get("campaign_id").asText();
                }
            }
        }
        // Prepared runtimes share this existing operational namespace; waiting launchers have no writer child.
        Path runtimes = state.resolve("runtime");
        if (Files.isDirectory(runtimes)) try (var dirs = Files.list(runtimes)) {
            dirs.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).limit(128)
                    .forEach(p -> executables.add(p.resolve("bin/adaptive-trader")));
        }
        var processes = processes();
        Map<Long, Process> byPid = new HashMap<>(); processes.forEach(p -> byPid.put(p.pid(), p));
        List<Process> candidates = new ArrayList<>();
        Path project = storage.getParent().getParent();
        boolean uncertain = false;
        for (Process p : processes) {
            Path exe = executable(p);
            if (exe == null || !executables.contains(exe)) continue;
            if (p.args() == null) { uncertain = true; continue; }
            int invocation = p.args().indexOf("market");
            if (invocation < 0 || p.args().size() < invocation + 3
                    || !p.args().subList(invocation, invocation + 3).equals(List.of("market", "microstructure", "campaign-record"))) continue;
            if (!Objects.equals(campaign, option(p.args(), "--campaign-id"))) continue;
            String output = option(p.args(), "--output-dir");
            if (output == null || !Path.of(output).toAbsolutePath().normalize().equals(storage)) continue;
            Process parent = byPid.get(p.parent());
            if (parent == null || parent.args() == null) { uncertain = true; continue; }
            boolean shell = parent.command() != null && Set.of("bash", "sh", "zsh").contains(Path.of(parent.command()).getFileName().toString());
            boolean launcher = parent.args().stream().anyMatch(a -> {
                if (!a.endsWith("/continuous_capture.sh")) return false;
                Path path = Path.of(a).toAbsolutePath().normalize();
                if (launchers.contains(path)) return true;
                // Handoff metadata registers the prepared runtime; validate its actual checkout launcher too.
                return path.getFileName().toString().equals("continuous_capture.sh")
                        && path.getParent().getFileName().toString().equals("scripts")
                        && Objects.equals(path.getParent().getParent().getParent(), project.getParent())
                        && path.getParent().getParent().getFileName().toString().startsWith(project.getFileName() + "-capture-")
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
            });
            if (shell && launcher) candidates.add(p);
            else { uncertain = true; warnings.add("Collector parent process identity mismatch"); }
        }
        if (candidates.size() != 1) {
            if (candidates.size() > 1) warnings.add("Multiple valid collectors; active runtime is ambiguous");
            if (registeredPid != null) {
                try {
                    Process registered = byPid.get(Long.parseLong(registeredPid));
                    if (registered != null) {
                        boolean knownLauncher = registered.args() != null && registered.args().stream()
                                .anyMatch(a -> launchers.contains(Path.of(a).toAbsolutePath().normalize()));
                        if (registered.args() == null || knownLauncher) {
                            uncertain = true; warnings.add("Registered supervisor has no verified active collector");
                        } else warnings.add("Registered PID belongs to a different process; no active collector found");
                    } else warnings.add("Registered PID is no longer running");
                } catch (NumberFormatException e) { uncertain = true; warnings.add("Invalid registered PID"); }
            }
            return new Runtime(candidates.isEmpty() && !uncertain ? State.STOPPED : State.UNKNOWN,
                    null, null, false, null, campaign, null, null, null, null, warnings);
        }
        Process collector = candidates.getFirst(), supervisor = byPid.get(collector.parent());
        if (supervisor.start() == null) warnings.add("Supervisor start time unavailable");
        if (!Long.toString(supervisor.pid()).equals(registeredPid)) warnings.add("Obsolete registered PID; discovered verified active runtime");
        Activity activity = activity(collector.pid(), storage);
        State status = activity.written() == null || activity.written().isAfter(now.plusSeconds(5)) ? State.UNKNOWN
                : Duration.between(activity.written(), now).compareTo(STALE_AFTER) > 0 ? State.STALE : State.RUNNING;
        if (status == State.STALE) warnings.add("Writer inactive for more than " + STALE_AFTER.toSeconds() + " seconds");
        if (activity.written() == null) warnings.add("Active process verified; writer activity unavailable");
        String market = Objects.equals("futures", option(collector.args(), "--market")) ? "USD-M Futures" : "Spot";
        return new Runtime(status, supervisor.pid(), collector.pid(), true, supervisor.start(), campaign,
                option(collector.args(), "--symbol"), market, activity.session(), activity.written(), warnings);
    }
}
