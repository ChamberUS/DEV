package byx.service.identity;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in public phase durations only; no arguments, paths, tokens or authorization results. */
public final class FencingTiming {
    private static volatile boolean enabled;
    private static final Map<String, Stats> phases = new ConcurrentHashMap<>();
    private static final Span DISABLED = new Span(null, 0);
    private FencingTiming() { }
    public static void enable() { phases.clear(); enabled = true; }
    public static Span phase(String name) { return enabled ? new Span(name, System.nanoTime()) : DISABLED; }
    public static Map<String, Map<String, Long>> snapshot() {
        var result = new TreeMap<String, Map<String, Long>>();
        phases.forEach((name, stats) -> result.put(name, stats.snapshot()));
        return result;
    }
    private static final class Stats {
        long count, total, max;
        synchronized void add(long ns) { count++; total += ns; max = Math.max(max, ns); }
        synchronized Map<String, Long> snapshot() { return Map.of("count", count, "totalNanos", total, "maxNanos", max); }
    }
    public static final class Span implements AutoCloseable {
        private final String name;
        private final long start;
        private Span(String name, long start) { this.name = name; this.start = start; }
        public void close() { if (name != null) phases.computeIfAbsent(name, k -> new Stats()).add(System.nanoTime() - start); }
    }
}
