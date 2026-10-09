package panel.design;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumMap;
import java.util.Map;
import javafx.scene.paint.Color;

/** Single source of semantic paints for native graphics, loaded from the approved design JSON. */
public final class ThemePalette {
    public static final String RESOURCE = "/design/BYX_PACKAGE_C2_TOKENS.json";
    private static final Map<ThemeMode, ThemePalette> PALETTES = load();
    private final Map<ThemeToken, Color> colors;

    private ThemePalette(Map<ThemeToken, Color> colors) { this.colors = Map.copyOf(colors); }

    public static ThemePalette of(ThemeMode mode) {
        if (mode == ThemeMode.SYSTEM) throw new IllegalArgumentException("System appearance is unsupported in this runtime");
        return PALETTES.get(java.util.Objects.requireNonNull(mode));
    }

    public Color color(ThemeToken token) { return colors.get(java.util.Objects.requireNonNull(token)); }
    public int size() { return colors.size(); }

    private static Map<ThemeMode, ThemePalette> load() {
        JsonNode tokens = DesignTokens.read(RESOURCE).path("tokens");
        Map<ThemeMode, ThemePalette> palettes = new EnumMap<>(ThemeMode.class);
        for (ThemeMode mode : new ThemeMode[] {ThemeMode.DARK, ThemeMode.LIGHT}) {
            Map<ThemeToken, Color> colors = new EnumMap<>(ThemeToken.class);
            for (ThemeToken token : ThemeToken.values()) {
                JsonNode value = tokens.path(token.key).path(mode.name().toLowerCase(java.util.Locale.ROOT));
                if (!value.isTextual()) throw new IllegalStateException("Missing " + mode + " theme token " + token.key);
                colors.put(token, Color.web(value.asText()));
            }
            palettes.put(mode, new ThemePalette(colors));
        }
        if (tokens.size() != ThemeToken.values().length) throw new IllegalStateException("Incomplete Package C2 token mapping");
        return Map.copyOf(palettes);
    }
}
