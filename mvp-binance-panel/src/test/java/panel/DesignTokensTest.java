package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import panel.design.DesignTokens;

/** O tema JavaFX V2 espelha BYX_DESIGN_TOKENS.json; qualquer divergência falha aqui. */
class DesignTokensTest {
    private static final Map<String, String> CSS_FOR_JSON = Map.ofEntries(
            Map.entry("colors.surface.bg0", "-byx-bg0"),
            Map.entry("colors.surface.bg1", "-byx-bg1"),
            Map.entry("colors.surface.bg2", "-byx-bg2"),
            Map.entry("colors.surface.bg3", "-byx-bg3"),
            Map.entry("colors.surface.hover", "-byx-hover"),
            Map.entry("colors.surface.line", "-byx-line"),
            Map.entry("colors.text.primary", "-byx-text-primary"),
            Map.entry("colors.text.secondary", "-byx-text-secondary"),
            Map.entry("colors.text.tertiary", "-byx-text-tertiary"),
            Map.entry("colors.text.onAccent", "-byx-text-on-accent"),
            Map.entry("colors.accent.trading", "-byx-trading"),
            Map.entry("colors.accent.research", "-byx-research"),
            Map.entry("colors.accent.byx", "-byx-byx"),
            Map.entry("colors.semantic.positive", "-byx-positive"),
            Map.entry("colors.semantic.negative", "-byx-negative"),
            Map.entry("colors.semantic.warning", "-byx-warning"),
            Map.entry("colors.semantic.info", "-byx-info"),
            Map.entry("colors.semantic.disabled", "-byx-disabled"),
            Map.entry("colors.bannerText.error", "-byx-banner-error"),
            Map.entry("colors.bannerText.warning", "-byx-banner-warning"),
            Map.entry("colors.bannerText.success", "-byx-banner-success"),
            Map.entry("colors.bannerText.info", "-byx-banner-info"));

    private static String css() throws Exception {
        try (InputStream in = DesignTokens.class.getResourceAsStream(DesignTokens.STYLESHEET)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Valor declarado no primeiro bloco (.root) do tema. */
    private static String rootValue(String css, String name) {
        String rootBlock = css.substring(css.indexOf(".root {"), css.indexOf('}', css.indexOf(".root {")));
        Matcher m = Pattern.compile(Pattern.quote(name) + ":\\s*([^;]+);").matcher(rootBlock);
        assertTrue(m.find(), "missing " + name + " in .root");
        return m.group(1).trim();
    }

    @Test
    void versionIsFinal() {
        assertEquals("3.0.0-final", DesignTokens.get().version());
    }

    @Test
    void everyJsonColorIsMirroredInCss() throws Exception {
        String css = css();
        DesignTokens t = DesignTokens.get();
        JsonNode colors = t.node("colors");
        int checked = 0;
        for (String group : List.of("surface", "text", "accent", "semantic", "bannerText")) {
            var it = colors.path(group).fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                String path = "colors." + group + "." + key;
                if (path.equals("colors.surface.scrim") || path.equals("colors.surface.scrimOpacity")) {
                    continue;
                }
                String cssName = CSS_FOR_JSON.get(path);
                assertTrue(cssName != null, "no CSS mapping for " + path);
                assertEquals(t.node(path).asText().toUpperCase(), rootValue(css, cssName).toUpperCase(), path);
                checked++;
            }
        }
        assertEquals(CSS_FOR_JSON.size(), checked);
    }

    @Test
    void scrimCarriesItsOpacity() throws Exception {
        DesignTokens t = DesignTokens.get();
        String scrim = t.node("colors.surface.scrim").asText();
        int alpha = (int) Math.round(t.number("colors.surface.scrimOpacity") * 255);
        assertEquals((scrim + String.format("%02X", alpha)).toUpperCase(), rootValue(css(), "-byx-scrim").toUpperCase());
    }

    @Test
    void highContrastMatchesAccessibilityTokens() throws Exception {
        String css = css();
        String hc = css.substring(css.indexOf(".root.hc"));
        DesignTokens t = DesignTokens.get();
        JsonNode h = t.node("accessibility.highContrast");
        assertTrue(hc.contains("-byx-text-secondary: " + h.path("text.secondary").asText()));
        assertTrue(hc.contains("-byx-text-tertiary: " + h.path("text.tertiary").asText()));
        assertTrue(hc.contains("-byx-line: " + h.path("line").asText()));
    }

    @Test
    void javafxResolvesContextAccentAndScrim() throws Exception {
        Map<String, javafx.scene.paint.Paint> got = FxSupport.fx(() -> {
            var root = new javafx.scene.layout.StackPane();
            var accent = new javafx.scene.layout.Region();
            accent.setStyle("-fx-background-color: -byx-accent;");
            var scrim = new javafx.scene.layout.Region();
            scrim.setStyle("-fx-background-color: -byx-scrim;");
            root.getChildren().addAll(accent, scrim);
            var scene = new javafx.scene.Scene(root, 10, 10);
            scene.getStylesheets().add(DesignTokens.class.getResource(DesignTokens.STYLESHEET).toExternalForm());
            root.getStyleClass().add("research");
            root.applyCss();
            return Map.of("accent", accent.getBackground().getFills().get(0).getFill(),
                    "scrim", scrim.getBackground().getFills().get(0).getFill());
        });
        DesignTokens t = DesignTokens.get();
        assertEquals(t.color("colors.accent.research"), got.get("accent"));
        javafx.scene.paint.Color scrim = (javafx.scene.paint.Color) got.get("scrim");
        assertEquals(t.number("colors.surface.scrimOpacity"), scrim.getOpacity(), 0.01);
    }

    @Test
    void layersAndStateListsAreExposed() {
        DesignTokens t = DesignTokens.get();
        assertEquals(30, t.layer("savebar"));
        assertEquals(40, t.layer("popover"));
        assertEquals(60, t.layer("commandPalette"));
        assertEquals(70, t.layer("dialog"));
        assertEquals(80, t.layer("toasts"));
        assertEquals(10, t.list("regionStates").size());
        assertEquals(6, t.list("statusStates.states").size());
        assertEquals(68, t.number("layout.rail.width"));
        assertEquals("Schibsted Grotesk", t.uiFont());
        assertEquals("JetBrains Mono", t.dataFont());
    }
}
