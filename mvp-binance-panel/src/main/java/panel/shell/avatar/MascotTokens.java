package panel.shell.avatar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;

/**
 * Mascot timing and geometry tokens, read from {@code /design/BYX_PACKAGE_B_TOKENS.json} (PKG-B-DESIGN-1) so the design file stays the
 * single source. {@code orbitMsPerRev} (1200) is the design's PROPOSAL, not a value derived from the reel; it is one token so it can be tuned
 * against the original GIF without touching code.
 */
public record MascotTokens(double gazeTauMs, double clampX, double clampY, double bodyLean, double menuTargetX, double menuTargetY,
        double loadingAmplitude, long blinkMinMs, long blinkMaxMs, long blinkSeed, long showDelayMs, long minShowMs, long deadlineMs,
        long errorHoldMs, int ringCount, double ringExtentVsBody, long orbitMsPerRev, long tiltMs, long reactPressMs, long reactHoldMs,
        long reactReturnMs, long press, long hover, long gazeBack, long enter, long settle, long reducedPress, long reducedHover,
        long reducedGazeBack, long reducedEnter, long reducedSettle, double bodyScale, int sizeHeader, String avatarBg, String[] ringColors) {

    private static volatile MascotTokens shared;

    public static MascotTokens shared() {
        MascotTokens t = shared;
        if (t == null) {
            synchronized (MascotTokens.class) {
                t = shared;
                if (t == null) {
                    t = load();
                    shared = t;
                }
            }
        }
        return t;
    }

    public long reactTotalMs() {
        return reactPressMs + reactHoldMs + reactReturnMs;
    }

    static MascotTokens load() {
        try (InputStream in = MascotTokens.class.getResourceAsStream("/design/BYX_PACKAGE_B_TOKENS.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /design/BYX_PACKAGE_B_TOKENS.json");
            }
            return parse(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mascot tokens", e);
        }
    }

    static MascotTokens parse(JsonNode root) {
        JsonNode m = root.get("mascot");
        JsonNode motion = root.get("motion");
        JsonNode red = motion.get("reduced");
        JsonNode dark = root.get("color").get("dark");
        JsonNode react = m.get("clickReaction").get("phases");
        String[] rings = new String[5];
        for (int i = 0; i < 5; i++) {
            rings[i] = dark.get("ring." + (i + 1)).asText();
        }
        return new MascotTokens(m.get("gaze").get("tauMs").asDouble(), m.get("gaze").get("clampX").asDouble(), m.get("gaze").get("clampY").asDouble(),
                m.get("gaze").get("bodyLean").asDouble(), m.get("gaze").get("menuOpenTarget").get(0).asDouble(),
                m.get("gaze").get("menuOpenTarget").get(1).asDouble(), m.get("gaze").get("loadingAmplitude").asDouble(),
                m.get("blink").get("minMs").asLong(), m.get("blink").get("maxMs").asLong(), m.get("blink").get("seed").asLong(),
                m.get("operation").get("showDelayMs").asLong(), m.get("operation").get("minShowMs").asLong(), m.get("operation").get("deadlineMs").asLong(),
                m.get("operation").get("errorHoldMs").asLong(), m.get("rings").get("count").asInt(), m.get("rings").get("extentVsBody").asDouble(),
                m.get("rings").get("orbitMsPerRev").asLong(), m.get("rings").get("tiltMs").asLong(), react.get(0).asLong(), react.get(1).asLong(),
                react.get(2).asLong(), motion.get("press").asLong(), motion.get("hover").asLong(), motion.get("gazeBack").asLong(), motion.get("enter").asLong(),
                motion.get("settle").asLong(), red.get("press").asLong(), red.get("hover").asLong(), red.get("gazeBack").asLong(), red.get("enter").asLong(),
                red.get("settle").asLong(), m.get("bodyScale").asDouble(), m.get("sizeHeader").asInt(), dark.get("avatar.bg").asText(), rings);
    }
}
