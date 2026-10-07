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

    /** Camada do rig do IDLE (corpo ou olho): arquivo, recorte no canvas de 384 e pivô (corpo: base central; olho: centro). */
    public record RigPart(String file, Cell cell, double pivotX, double pivotY) { }

    /** Corpo com os olhos preenchidos + dois olhos separados (alfa suave): permite olhar/piscar sem mover o sprite inteiro. */
    public record Rig(RigPart body, RigPart eyeLeft, RigPart eyeRight, RigPart halo) { }

    /**
     * cell: recorte no canvas lógico de 384 (onde desenhar); cellPxW/H: tamanho da célula NA SHEET (a sheet pode ser gravada em escala menor, {@code sheetScale}, ex.: PROCESSING a 0,75; o desenho
     * em tela continua em coordenadas do canvas).
     */
    public record Entry(MascotState state, boolean loop, int frames, int fps, int durationMs, int holdMs, String sheet, String poster, int cols, int rows, Cell cell, int posterFrame, String recommendedUse,
            double sheetScale, int cellPxW, int cellPxH, Rig rig) {
        public int sheetWidth() { return cols * cellPxW; }

        public int sheetHeight() { return rows * cellPxH; }
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

    private static RigPart part(JsonNode n) throws IOException {
        JsonNode c = n.path("cell");
        Cell cell = new Cell(c.path("x").asInt(), c.path("y").asInt(), c.path("w").asInt(), c.path("h").asInt());
        if (!n.path("file").asText().matches("rig/[a-z-]+\\.png") || cell.w() < 1 || cell.h() < 1 || cell.x() < 0 || cell.y() < 0 || cell.x() + cell.w() > CANVAS || cell.y() + cell.h() > CANVAS || n.path("pivot").size() != 2) {
            throw new IOException("manifest rig part");
        }
        return new RigPart(n.path("file").asText(), cell, n.path("pivot").get(0).asDouble(), n.path("pivot").get(1).asDouble());
    }

    private static Rig rig(JsonNode n) throws IOException {
        return new Rig(part(n.path("body")), part(n.path("eyeLeft")), part(n.path("eyeRight")), n.has("halo") ? part(n.path("halo")) : null);
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
            double scale = n.has("sheetScale") ? n.path("sheetScale").asDouble() : 1.0;
            JsonNode cp = n.has("cellPx") ? n.path("cellPx") : c;
            Rig rig = n.has("rig") ? rig(n.path("rig")) : null;
            Entry e = new Entry(s, n.path("loop").asBoolean(), n.path("frames").asInt(), Math.max(1, n.path("fps").asInt()), n.path("durationMs").asInt(), n.path("holdMs").asInt(), n.path("sheet").asText(), n.path("poster").asText(),
                    n.path("cols").asInt(), n.path("rows").asInt(), cell, n.path("posterFrame").asInt(), n.path("recommendedUse").asText(""), scale, cp.path("w").asInt(), cp.path("h").asInt(), rig);
            if (scale < 0.25 || scale > 1.0 || e.cellPxW() < 1 || e.cellPxH() < 1) {
                throw new IOException("manifest scale " + s);
            }
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
