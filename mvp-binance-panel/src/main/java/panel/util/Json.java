package panel.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Acesso tolerante a JSON: campo ausente devolve null, nunca exceção. */
public final class Json {
    private Json() {
    }

    public static JsonNode get(JsonNode n, String... names) {
        if (n == null) {
            return null;
        }
        for (String name : names) {
            JsonNode c = n.get(name);
            if (c != null && !c.isNull()) {
                return c;
            }
        }
        return null;
    }

    public static String str(JsonNode n, String... names) {
        JsonNode c = get(n, names);
        return c == null ? null : (c.isValueNode() ? c.asText() : c.toString());
    }

    public static Long lng(JsonNode n, String... names) {
        JsonNode c = get(n, names);
        return c != null && c.isNumber() ? c.asLong() : null;
    }

    public static Double dbl(JsonNode n, String... names) {
        JsonNode c = get(n, names);
        return c != null && c.isNumber() ? c.asDouble() : null;
    }

    public static Instant instant(JsonNode n, String... names) {
        String s = str(n, names);
        try {
            return s == null ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
