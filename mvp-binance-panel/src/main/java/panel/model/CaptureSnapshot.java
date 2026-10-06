package panel.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Immutable observations. Timers use these anchors without more I/O. */
public record CaptureSnapshot(State state, Long pid, boolean processAlive, Instant processStartedAt,
        String campaignId, Instant campaignStartedAt, String symbol, String market,
        Instant checkedAt, Instant lastUpdate, String storagePath, Long capturedBytes,
        Long diskFreeBytes, Long diskTotalBytes, Instant storageCheckedAt, List<String> warnings,
        String sessionId, Long collectorPid, Instant lastEvent) {
    public enum State { RUNNING, STOPPED, STALE, UNKNOWN }
    public static final long TARGET_SECONDS = 86400;
    public CaptureSnapshot { warnings = List.copyOf(warnings); }
    public CaptureSnapshot(State state, Long pid, boolean processAlive, Instant processStartedAt,
            String campaignId, Instant campaignStartedAt, String symbol, String market,
            Instant checkedAt, Instant lastUpdate, String storagePath, Long capturedBytes,
            Long diskFreeBytes, Long diskTotalBytes, Instant storageCheckedAt, List<String> warnings) {
        this(state, pid, processAlive, processStartedAt, campaignId, campaignStartedAt, symbol, market,
                checkedAt, lastUpdate, storagePath, capturedBytes, diskFreeBytes, diskTotalBytes,
                storageCheckedAt, warnings, null, null, null);
    }

    public static CaptureSnapshot unknown(Instant now, String warning) {
        return new CaptureSnapshot(State.UNKNOWN, null, false, null, null, null, null, null,
                now, null, null, null, null, null, null, List.of(warning));
    }

    public Duration continuousElapsed(Instant now) { return elapsed(processStartedAt, now); }
    public Duration campaignElapsed(Instant now) { return elapsed(campaignStartedAt, now); }
    private Duration elapsed(Instant start, Instant now) {
        if (start == null) return null;
        Duration d = Duration.between(start, processAlive ? now : checkedAt);
        return d.isNegative() ? Duration.ZERO : d;
    }
    public Double progress(Instant now) {
        Duration d = campaignElapsed(now);
        return d == null ? null : Math.clamp(d.toSeconds() / (double) TARGET_SECONDS, 0, 1);
    }
    public static String elapsedText(Duration d) {
        if (d == null) return "N/A";
        long seconds = Math.max(0, d.toSeconds());
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600 % 24, seconds / 60 % 60, seconds % 60);
        return seconds >= TARGET_SECONDS ? seconds / TARGET_SECONDS + "d " + clock : clock;
    }
}
