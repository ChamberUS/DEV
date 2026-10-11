package panel.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.paint.Color;

/**
 * Leitura de /design/BYX_DESIGN_TOKENS.json (handoff V2, 3.0.0-final). Cores de tela vêm de
 * /panel/v2/tokens.css; este acesso existe para números de layout, camadas e listas de estados.
 */
public final class DesignTokens {
    public static final String RESOURCE = "/design/BYX_DESIGN_TOKENS.json";
    public static final String STYLESHEET = "/panel/v2/tokens.css";

    private static volatile DesignTokens instance;

    private final JsonNode root;

    private DesignTokens(JsonNode root) {
        this.root = root;
    }

    public static DesignTokens get() {
        DesignTokens d = instance;
        if (d == null) {
            synchronized (DesignTokens.class) {
                if (instance == null) {
                    instance = new DesignTokens(read(RESOURCE));
                }
                d = instance;
            }
        }
        return d;
    }

    static JsonNode read(String resource) {
        try (InputStream in = DesignTokens.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String version() {
        return root.path("version").asText();
    }

    /** Nó por caminho com pontos, ex.: "layout.rail.width". */
    public JsonNode node(String path) {
        JsonNode n = root;
        for (String part : path.split("\\.")) {
            n = n.path(part);
        }
        if (n.isMissingNode()) {
            throw new IllegalArgumentException("unknown token " + path);
        }
        return n;
    }

    public double number(String path) {
        return node(path).asDouble();
    }

    public Color color(String path) {
        return Color.web(node(path).asText());
    }

    public int layer(String name) {
        return node("layers." + name).asInt();
    }

    public List<String> list(String path) {
        List<String> out = new ArrayList<>();
        node(path).forEach(n -> out.add(n.asText()));
        return out;
    }

    public String uiFont() {
        return node("fontFamilies.ui").asText();
    }

    public String dataFont() {
        return node("fontFamilies.data").asText();
    }
}
