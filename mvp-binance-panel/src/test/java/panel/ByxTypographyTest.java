package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.junit.jupiter.api.Test;
import panel.design.ByxFonts;
import panel.design.DesignTokens;

/** Cada estilo tipográfico do token resolve na face empacotada correta, sem cair em fonte do sistema. */
class ByxTypographyTest {
    private static final Map<String, String> CLASS_FOR_TOKEN = Map.of(
            "display", "byx-display", "authTitle", "byx-auth-title", "pageTitle", "byx-page-title",
            "sectionTitle", "byx-section-title", "sectionTitleSmall", "byx-section-title-sm", "body", "byx-body",
            "data", "byx-data", "dataLarge", "byx-data-lg", "label", "byx-label", "micro", "byx-micro");

    @Test
    void everyTypographyTokenResolvesToBundledFace() throws Exception {
        Map<String, Font> fonts = FxSupport.fx(() -> {
            ByxFonts.load();
            VBox root = new VBox();
            Map<String, Label> labels = new LinkedHashMap<>();
            CLASS_FOR_TOKEN.forEach((token, cls) -> {
                Label l = new Label("Ag 0123");
                l.getStyleClass().add(cls);
                labels.put(token, l);
                root.getChildren().add(l);
            });
            Scene s = new Scene(root);
            s.getStylesheets().add(DesignTokens.class.getResource(DesignTokens.STYLESHEET).toExternalForm());
            s.getStylesheets().add(DesignTokens.class.getResource("/panel/v2/typography.css").toExternalForm());
            root.applyCss();
            Map<String, Font> out = new LinkedHashMap<>();
            labels.forEach((k, l) -> out.put(k, l.getFont()));
            return out;
        });
        DesignTokens t = DesignTokens.get();
        fonts.forEach((token, f) -> {
            var spec = t.node("typography." + token);
            boolean data = "data".equals(spec.path("family").asText());
            String family = ByxFonts.family(data, spec.path("weight").asInt());
            assertEquals(family, f.getFamily(), token + " got " + f.getName());
            assertEquals(spec.path("size").asDouble(), f.getSize(), 0.01, token);
        });
    }

    @Test
    void uppercaseHelper() throws Exception {
        String text = FxSupport.fx(() -> ByxFonts.upper(new Label("System health")).getText());
        assertEquals("SYSTEM HEALTH", text);
    }
}
