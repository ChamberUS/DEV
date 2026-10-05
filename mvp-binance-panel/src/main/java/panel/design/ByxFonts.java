package panel.design;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import javafx.scene.control.Labeled;
import javafx.scene.text.Font;

/**
 * Registra as fontes empacotadas do tema V2 uma única vez. UI: Schibsted Grotesk 400/600 (todos os
 * estilos de UI do BYX_DESIGN_TOKENS.json usam só esses pesos). Dados: JetBrains Mono 400/500/600.
 */
public final class ByxFonts {
    public static final String UI = "Schibsted Grotesk";
    public static final String DATA = "JetBrains Mono";
    /** JavaFX registra cada TTF estático como família própria; o peso é escolhido pelo nome da família. */
    public static final String UI_SEMIBOLD = "Schibsted Grotesk SemiBold";
    public static final String DATA_MEDIUM = "JetBrains Mono Medium";
    public static final String DATA_SEMIBOLD = "JetBrains Mono SemiBold";
    static final String[] FILES = {"SchibstedGrotesk-Regular", "SchibstedGrotesk-SemiBold", "JetBrainsMono-Regular",
            "JetBrainsMono-Medium", "JetBrainsMono-SemiBold"};

    private static boolean loaded;

    private ByxFonts() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        for (String f : FILES) {
            try (InputStream in = ByxFonts.class.getResourceAsStream("/fonts/" + f + ".ttf")) {
                if (in == null || Font.loadFont(in, 13) == null) {
                    throw new IllegalStateException("font not loaded: " + f);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        loaded = true;
    }

    /** Família para um peso de token (400/500/600). -fx-font-weight não é confiável com faces estáticas. */
    public static String family(boolean data, int weight) {
        if (data) {
            return weight >= 600 ? DATA_SEMIBOLD : weight >= 500 ? DATA_MEDIUM : DATA;
        }
        if (weight == 500) {
            throw new IllegalArgumentException("Schibsted Grotesk 500 is not bundled; UI tokens use 400 or 600");
        }
        return weight >= 600 ? UI_SEMIBOLD : UI;
    }

    /**
     * Estilo "label" (12/600 caixa alta). JavaFX CSS não tem text-transform nem letter-spacing:
     * a caixa alta é aplicada no texto; o espaçamento .06em fica como desvio conhecido.
     */
    public static <T extends Labeled> T upper(T l) {
        l.setText(l.getText() == null ? null : l.getText().toUpperCase(Locale.ROOT));
        return l;
    }
}
