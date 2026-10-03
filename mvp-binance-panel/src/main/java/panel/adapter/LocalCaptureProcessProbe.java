package panel.adapter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import panel.model.CaptureSnapshot;
import panel.model.CaptureSnapshot.State;

public final class LocalCaptureProcessProbe implements CaptureProcessProbe {
    public record ProcessInfo(boolean alive, Instant start, String command, List<String> arguments,
                              String symbol, String market) { }
    public record Storage(Long bytes, Long free, Long total, Instant updated, String warning) { }
    public interface Source {
        String text(Path file) throws IOException;
        ProcessInfo process(long pid, Path script, String campaign) throws IOException;
        Storage storage(Path root) throws IOException;
    }
    private static final Pattern CAMPAIGN = Pattern.compile("[a-zA-Z0-9_-]+-(\\d{8}T\\d{6}Z)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final Path stateDir;
    private final Path storagePath;
    private final Clock clock;
    private final Source source;
    private Storage storage;
    private Instant storageAt;
    private Long observedPid;
    private Instant observedStart;

    public LocalCaptureProcessProbe(Path stateDir, Path storagePath, Path cli) {
        this(stateDir, storagePath, Clock.systemUTC(), new NioSource(cli));
    }
    public LocalCaptureProcessProbe(Path stateDir, Path storagePath, Clock clock, Source source) {
        this.stateDir = stateDir; this.storagePath = storagePath; this.clock = clock; this.source = source;
    }
    public static Instant campaignStart(String id) {
        if (id == null) return null;
        var matcher = CAMPAIGN.matcher(id);
        if (!matcher.matches()) return null;
        try { return LocalDateTime.parse(matcher.group(1), STAMP).toInstant(ZoneOffset.UTC); }
        catch (java.time.DateTimeException e) { return null; }
    }
    @Override public synchronized CaptureSnapshot read() {
        Instant now = clock.instant();
        var warnings = new ArrayList<String>();
        Long pid = null; boolean alive = false;
        Instant start = null; String campaign = null; Instant campaignStart = null;
        String symbol = null; String market = null;
        State state = State.UNKNOWN;
        try {
            String raw = optional(stateDir.resolve("capture.pid"));
            campaign = optional(stateDir.resolve("current_campaign"));
            if (campaign != null && campaign.isEmpty()) campaign = null;
            campaignStart = campaignStart(campaign);
            if (campaign != null && (campaignStart == null || campaignStart.isAfter(now))) {
                warnings.add("Invalid current campaign timestamp"); campaignStart = null;
            }
            if (raw == null) state = State.STOPPED;
            else {
                try { pid = Long.valueOf(raw); } catch (NumberFormatException e) { warnings.add("Invalid PID file"); }
                if (pid == null || pid <= 0) { state = State.STALE; pid = null; warnings.add("PID must be a positive integer"); }
                else {
                    Path script = stateDir.resolve("continuous_capture.sh").toAbsolutePath().normalize();
                    ProcessInfo p = source.process(pid, script, campaign);
                    if (!p.alive()) {
                        state = pid.equals(observedPid) ? State.STOPPED : State.STALE;
                        if (pid.equals(observedPid)) start = observedStart;
                        warnings.add("PID file refers to a process that is no longer running");
                    } else if (!matches(p, script)) {
                        state = State.STALE; warnings.add("PID belongs to a different process");
                    } else if (pid.equals(observedPid) && observedStart != null && p.start() != null
                            && !observedStart.equals(p.start())) {
                        state = State.STALE; warnings.add("PID was reused; supervisor identity changed");
                    } else {
                        alive = true; start = p.start(); observedPid = pid; observedStart = start;
                        symbol = p.symbol(); market = p.market();
                        state = campaignStart == null ? State.UNKNOWN : State.RUNNING;
                        if (campaignStart == null) warnings.add("Campaign TRANSITION: waiting for current_campaign");
                        if (start == null) warnings.add("Process start time unavailable");
                        if (p.arguments() == null) warnings.add("Process arguments unavailable; identity only partially verified");
                    }
                }
            }
        } catch (IOException | SecurityException e) { warnings.add("Process/state read unavailable: " + e.getClass().getSimpleName()); }
        if (storageAt == null || Duration.between(storageAt, now).toSeconds() >= 60) {
            try { storage = source.storage(storagePath); }
            catch (IOException | SecurityException e) { storage = new Storage(null, null, null, null, "Storage unavailable: " + e.getClass().getSimpleName()); }
            storageAt = now;
        }
        if (storage.warning() != null) warnings.add(storage.warning());
        warnings.add("Process presence is not recorder health; live health telemetry is N/A");
        return new CaptureSnapshot(state, pid, alive, start, campaign, campaignStart, symbol, market,
                now, storage.updated(), storagePath.toString(), storage.bytes(), storage.free(), storage.total(), storageAt, warnings);
    }
    private String optional(Path file) throws IOException {
        try { return source.text(file).strip(); }
        catch (NoSuchFileException e) { return null; }
    }
    private static boolean matches(ProcessInfo process, Path script) {
        if (script.toString().equals(process.command())) return true;
        if (process.arguments() != null) return process.arguments().stream().anyMatch(script.toString()::equals);
        // Restricted process metadata cannot prove a mismatch.
        return process.command() == null || Set.of("bash", "sh", "zsh").contains(Path.of(process.command()).getFileName().toString());
    }
    public static final class NioSource implements Source {
        private final Path cli;
        public NioSource(Path cli) { this.cli = cli.toAbsolutePath().normalize(); }
        @Override public String text(Path file) throws IOException {
            try (var reader = Files.newBufferedReader(file)) {
                char[] chars = new char[4097]; int count = reader.read(chars);
                if (count > 4096) throw new IOException("State file exceeds limit");
                return count < 0 ? "" : new String(chars, 0, count);
            }
        }
        @Override public ProcessInfo process(long pid, Path script, String campaign) {
            var handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) return new ProcessInfo(false, null, null, null, null, null);
            var process = handle.get(); var info = process.info();
            String symbol = null; String market = null;
            try (var children = process.descendants()) {
                for (var child : children.limit(64).toList()) {
                    var ci = child.info(); var args = Arrays.asList(ci.arguments().orElse(new String[0]));
                    int index = args.indexOf(cli.toString());
                    if (index < 0 && !cli.toString().equals(ci.command().orElse(null))) continue;
                    List<String> command = index >= 0 ? args.subList(index + 1, args.size()) : args;
                    if (command.size() < 3 || !command.subList(0, 3).equals(List.of("market", "microstructure", "campaign-record"))) continue;
                    if (!Objects.equals(campaign, option(command, "--campaign-id"))) continue;
                    symbol = option(command, "--symbol");
                    market = switch (Objects.toString(option(command, "--market"), "")) {
                        case "futures" -> "USD-M Futures"; case "spot" -> "Spot"; default -> null;
                    };
                    break;
                }
            }
            return new ProcessInfo(process.isAlive(), info.startInstant().orElse(null), info.command().orElse(null),
                    info.arguments().map(Arrays::asList).orElse(null), symbol, market);
        }
        private static String option(List<String> args, String name) {
            int i = args.indexOf(name); return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
        }
        @Override public Storage storage(Path root) throws IOException {
            var store = Files.getFileStore(root);
            long free = store.getUsableSpace(), total = store.getTotalSpace();
            long deadline = System.nanoTime() + 2_000_000_000L;
            long[] bytes = {0}; Instant[] updated = {null}; boolean[] complete = {true};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) {
                    String name = dir.getFileName().toString().toUpperCase(Locale.ROOT);
                    if (name.contains("VALIDATION") || name.contains("HOLDOUT")) return FileVisitResult.SKIP_SUBTREE;
                    if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                        complete[0] = false; return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
                    if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                        complete[0] = false; return FileVisitResult.TERMINATE;
                    }
                    if (attr.isRegularFile()) {
                        bytes[0] += attr.size(); Instant mtime = attr.lastModifiedTime().toInstant();
                        if (updated[0] == null || mtime.isAfter(updated[0])) updated[0] = mtime;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    complete[0] = false; return FileVisitResult.TERMINATE;
                }
            });
            return new Storage(complete[0] ? bytes[0] : null, free, total, complete[0] ? updated[0] : null,
                    complete[0] ? null : "Storage scan incomplete; captured size is N/A");
        }
    }
}
