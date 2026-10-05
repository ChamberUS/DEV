package panel.systemview;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import panel.design.RegionState;

/** Matriz de estados por região (P3.10), lida de /content/system-messages.json: cada região só aceita os estados que pode ter de verdade. */
public final class RegionMatrix {
    private static final Map<String, Set<RegionState>> MATRIX = load();

    private RegionMatrix() {
    }

    private static Map<String, Set<RegionState>> load() {
        Map<String, Set<RegionState>> out = new LinkedHashMap<>();
        try (InputStream in = RegionMatrix.class.getResourceAsStream("/content/system-messages.json")) {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            for (JsonNode row : root.path("regionMatrix")) {
                Set<RegionState> states = EnumSet.noneOf(RegionState.class);
                for (String s : row.path("s").asText().split("\\s+")) {
                    states.add(RegionState.valueOf(s));
                }
                out.put(row.path("c").asText(), Set.copyOf(states));
            }
        } catch (IOException e) {
            throw new IllegalStateException("system-messages.json unreadable", e);
        }
        return Map.copyOf(out);
    }

    public static Map<String, Set<RegionState>> all() {
        return MATRIX;
    }

    public static Set<RegionState> of(String component) {
        Set<RegionState> s = MATRIX.get(component);
        if (s == null) {
            throw new IllegalArgumentException("no region matrix row for " + component);
        }
        return s;
    }

    public static boolean allows(String component, RegionState state) {
        return of(component).contains(state);
    }
}
