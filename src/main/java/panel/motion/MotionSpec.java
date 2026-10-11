package panel.motion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.animation.Interpolator;
import javafx.util.Duration;

/**
 * Tokens de /design/BYX_MOTION_TOKENS.json (handoff V2, 3.0.0-final) resolvidos por modo.
 * Valores textuais do JSON: "none" = a animação não existe no modo; "static" = um quadro parado;
 * "instant 100%" = salta para o estado final. Sem reducedMs: teto {@link #REDUCED_FALLBACK_CAP}.
 */
public final class MotionSpec {
    public static final String RESOURCE = "/design/BYX_MOTION_TOKENS.json";
    public static final Interpolator STANDARD = Interpolator.SPLINE(0.2, 0.8, 0.2, 1);
    public static final Interpolator ACCELERATE = Interpolator.SPLINE(0.4, 0, 1, 1);
    /** Maior reducedMs não-loop do JSON; usado só quando o token não declara reducedMs. */
    public static final Duration REDUCED_FALLBACK_CAP = Duration.millis(120);

    /** Como o token se apresenta num modo. */
    public enum Kind { ANIMATED, INSTANT, STATIC, NONE }

    public record Resolved(Kind kind, Duration duration, Duration delay) {
        public boolean runs() {
            return kind == Kind.ANIMATED && duration.greaterThan(Duration.ZERO);
        }
    }

    public record Timing(String name, Duration full, Duration fullMax, Duration delay, String easing, boolean loop,
            JsonNode reduced, JsonNode off, JsonNode raw) {

        public Interpolator interpolator() {
            return switch (easing) {
                case "accelerate" -> ACCELERATE;
                case "linear", "none" -> Interpolator.LINEAR;
                default -> easing.startsWith("sine") ? Interpolator.EASE_BOTH : STANDARD;
            };
        }

        /** delayMs é tempo lógico (tooltip, desfazer): vale igual em FULL, REDUCED e OFF. */
        public Resolved resolve(MotionPreference mode) {
            return switch (mode) {
                case FULL -> new Resolved(Kind.ANIMATED, full, delay);
                case REDUCED -> reduced.isMissingNode() || reduced.isNull()
                        ? new Resolved(Kind.ANIMATED, full.greaterThan(REDUCED_FALLBACK_CAP) ? REDUCED_FALLBACK_CAP : full, delay)
                        : fromValue(reduced);
                case OFF -> off.isMissingNode() || off.isNull() ? new Resolved(Kind.INSTANT, Duration.ZERO, delay) : fromValue(off);
            };
        }

        private Resolved fromValue(JsonNode v) {
            if (v.isNumber()) {
                Duration d = Duration.millis(v.asDouble());
                return d.equals(Duration.ZERO) ? new Resolved(Kind.INSTANT, Duration.ZERO, delay) : new Resolved(Kind.ANIMATED, d, delay);
            }
            String s = v.asText().trim();
            if (s.equals("none")) {
                return new Resolved(Kind.NONE, Duration.ZERO, delay);
            }
            if (s.equals("static")) {
                return new Resolved(Kind.STATIC, Duration.ZERO, delay);
            }
            if (s.startsWith("instant")) {
                return new Resolved(Kind.INSTANT, Duration.ZERO, delay);
            }
            throw new IllegalStateException("unknown motion value '" + s + "' in " + name);
        }
    }

    private static volatile MotionSpec instance;

    private final String version;
    private final Map<String, Timing> tokens;

    private MotionSpec(JsonNode root) {
        version = root.path("version").asText();
        Map<String, Timing> m = new LinkedHashMap<>();
        root.path("tokens").fields().forEachRemaining(e -> m.put(e.getKey(), parse(e.getKey(), e.getValue())));
        tokens = Collections.unmodifiableMap(m);
    }

    public static MotionSpec get() {
        MotionSpec s = instance;
        if (s == null) {
            synchronized (MotionSpec.class) {
                if (instance == null) {
                    try (InputStream in = MotionSpec.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException("missing resource " + RESOURCE);
                        }
                        instance = new MotionSpec(new ObjectMapper().readTree(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                s = instance;
            }
        }
        return s;
    }

    private static Timing parse(String name, JsonNode t) {
        JsonNode d = t.path("durationMs");
        Duration min;
        Duration max;
        if (d.isNumber()) {
            min = max = Duration.millis(d.asDouble());
        } else {
            String[] range = d.asText().split("\\.\\.");
            if (range.length != 2) {
                throw new IllegalStateException("bad durationMs in " + name);
            }
            min = Duration.millis(Double.parseDouble(range[0].trim()));
            max = Duration.millis(Double.parseDouble(range[1].trim()));
        }
        return new Timing(name, min, max, Duration.millis(t.path("delayMs").asDouble(0)), t.path("easing").asText("standard"),
                t.path("loop").asBoolean(false), t.path("reducedMs"), t.path("offMs"), t);
    }

    public String version() {
        return version;
    }

    public Map<String, Timing> tokens() {
        return tokens;
    }

    public Timing token(String name) {
        Timing t = tokens.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown motion token " + name);
        }
        return t;
    }
}
