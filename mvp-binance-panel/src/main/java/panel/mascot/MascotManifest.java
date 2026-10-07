package panel.mascot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Manifesto empacotado dos assets (gerado por tools/mascot/build_mascot_assets.sh): um registro por {@link MascotState}. Pequeno (poucos KB); lido uma vez, fora da thread FX. */
public final class MascotManifest {
    public static final String RESOURCE = "/panel/mascot/mascot-manifest.json";
    public static final int CANVAS = 384;

    /** Recorte (no canvas lógico de 384 px) em que cada frame da sprite sheet está. */
    public record Cell(int x, int y, int w, int h) { }

    public record Entry(MascotState state, boolean loop, int frames, int fps, int durationMs, int holdMs, String sheet, String poster, int cols, int rows, Cell cell, int posterFrame, String recommendedUse) {
        public int sheetWidth() { return cols * cell.w(); }

        public int sheetHeight() { return rows * cell.h(); }
    }

    private final Map<MascotState, Entry> entries = new EnumMap<>(MascotState.class);
    private final String sourceSha256;

    private MascotManifest(String sourceSha256) {
        this.sourceSha256 = sourceSha256;
    }

    public Optional<Entry> entry(MascotState s) {
        return Optional.ofNullable(entries.get(s));
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    /** Falha fechada: manifesto ausente, inválido ou incompleto = IOException (o mascote cai no vazio seguro, nunca em exceção global). */
    public static MascotManifest load(String resource) throws IOException {
        try (InputStream in = MascotManifest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("manifest missing");
            }
            return parse(new JsonMapper().readTree(in.readNBytes(256 * 1024)));
        } catch (RuntimeException e) {
            throw new IOException("manifest invalid", e);
        }
    }

    static MascotManifest parse(JsonNode root) throws IOException {
        if (root.path("schema").asInt() != 1 || root.path("canvas").asInt() != CANVAS) {
            throw new IOException("manifest schema");
        }
        MascotManifest m = new MascotManifest(root.path("source").path("sha256").asText(""));
        for (JsonNode n : root.path("states")) {
            MascotState s = MascotState.valueOf(n.path("state").asText());
            JsonNode c = n.path("cell");
            Cell cell = new Cell(c.path("x").asInt(), c.path("y").asInt(), c.path("w").asInt(), c.path("h").asInt());
            Entry e = new Entry(s, n.path("loop").asBoolean(), n.path("frames").asInt(), Math.max(1, n.path("fps").asInt()), n.path("durationMs").asInt(), n.path("holdMs").asInt(), n.path("sheet").asText(), n.path("poster").asText(),
                    n.path("cols").asInt(), n.path("rows").asInt(), cell, n.path("posterFrame").asInt(), n.path("recommendedUse").asText(""));
            if (e.frames() < 1 || cell.w() < 1 || cell.h() < 1 || cell.x() < 0 || cell.y() < 0 || cell.x() + cell.w() > CANVAS || cell.y() + cell.h() > CANVAS || e.cols() < 1 || e.rows() < 1 || e.cols() * e.rows() < e.frames()
                    || e.sheetWidth() > 4096 || e.sheetHeight() > 4096 || !e.sheet().matches("states/[a-z]+\\.png") || !e.poster().matches("posters/[a-z]+\\.png") || e.loop() != s.loops()) {
                throw new IOException("manifest entry " + s);
            }
            m.entries.put(s, e);
        }
        for (MascotState s : MascotState.values()) {
            if (!m.entries.containsKey(s)) {
                throw new IOException("manifest lacks " + s);
            }
        }
        return m;
    }
}
