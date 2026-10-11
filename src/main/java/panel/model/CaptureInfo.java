package panel.model;

import java.time.Instant;

/** Estado do recorder. Tudo nulo até o backend expor telemetria de captura. */
public record CaptureInfo(String recorder, String connection, String exchange, String market, String symbol,
                          String currentSession, String sessionDuration, Long events, Double eventsPerSec,
                          Long bookUpdates, Long trades, Long reconnects, Instant lastEvent,
                          String diskWritten, String storagePath, String gapWarnings) {
}
