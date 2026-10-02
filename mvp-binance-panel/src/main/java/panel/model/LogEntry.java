package panel.model;

import java.time.Instant;

public record LogEntry(Instant time, Level level, String text) {
    public enum Level { INFO, WARN, ERROR, COMMAND }
}
