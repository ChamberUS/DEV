package panel.design;

import java.util.List;
import javafx.scene.Parent;
import javafx.scene.Scene;

/** Folhas do tema V2 na ordem de dependência (tokens antes de tudo). */
public final class ByxTheme {
    public static final List<String> STYLESHEETS = List.of(DesignTokens.STYLESHEET, "/panel/v2/typography.css",
            "/panel/v2/controls.css", "/panel/v2/shell.css", "/panel/v2/auth.css");

    private ByxTheme() {
    }

    public static List<String> urls() {
        return STYLESHEETS.stream().map(s -> ByxTheme.class.getResource(s).toExternalForm()).toList();
    }

    public static void apply(Scene scene) {
        ByxFonts.load();
        urls().forEach(u -> {
            if (!scene.getStylesheets().contains(u)) {
                scene.getStylesheets().add(u);
            }
        });
    }

    public static void apply(Parent parent) {
        ByxFonts.load();
        urls().forEach(u -> {
            if (!parent.getStylesheets().contains(u)) {
                parent.getStylesheets().add(u);
            }
        });
    }
}
