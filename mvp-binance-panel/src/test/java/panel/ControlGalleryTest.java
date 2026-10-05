package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;
import panel.design.ByxButton;
import panel.design.ByxRegion;
import panel.design.ByxStatusChip;
import panel.design.ByxStatusDot;
import panel.design.ControlGallery;
import panel.design.RegionState;
import panel.design.StatusState;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** Critério de saída do passo 4: a galeria renderiza todos os estados de todos os controles em FULL, REDUCED e OFF. */
class ControlGalleryTest {
    private static final File OUT = new File("target/gallery");

    private static <T> List<T> all(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Node n, Class<T> type, List<T> out) {
        if (type.isInstance(n)) {
            out.add(type.cast(n));
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                collect(c, type, out);
            }
        }
    }

    private static List<String> cellStates(Node section) {
        List<String> out = new ArrayList<>();
        for (Node n : all(section, Node.class)) {
            Object s = n.getProperties().get("gallery.state");
            if (s != null) {
                out.add(s.toString());
            }
        }
        return out;
    }

    @Test
    void rendersEveryStateInEveryMode() throws Exception {
        OUT.mkdirs();
        List<String> cssWarnings = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    cssWarnings.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger css = Logger.getLogger("javafx.css");
        css.addHandler(h);
        try {
            for (MotionPreference mode : MotionPreference.values()) {
                Map<String, Object> r = FxSupport.fx(() -> {
                    MotionService m = new MotionService();
                    m.preference.set(mode);
                    ControlGallery g = new ControlGallery(m);
                    Scene scene = new Scene(g, 1440, 900);
                    g.applyCss();
                    g.layout();
                    javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
                    sp.setFill(panel.design.DesignTokens.get().color("colors.surface.bg0"));
                    WritableImage img = g.page().snapshot(sp, null);
                    try {
                        javax.imageio.ImageIO.write(toAwt(img), "png",
                                new File(OUT, "gallery-" + mode.name().toLowerCase() + ".png"));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    var sections = g.sections();
                    List<RegionState> regionStates = all(sections.get("Region states"), ByxRegion.class).stream()
                            .map(ByxRegion::state).toList();
                    List<StatusState> chipStates = all(sections.get("Status chips"), ByxStatusChip.class).stream()
                            .map(ByxStatusChip::state).toList();
                    boolean shimmer = all(sections.get("Region states"), ByxRegion.class).stream().anyMatch(ByxRegion::shimmering);
                    boolean pulse = all(sections.get("Status chips"), ByxStatusDot.class).stream().anyMatch(ByxStatusDot::pulsing);
                    boolean spinner = all(sections.get("Buttons"), ByxButton.class).stream()
                            .filter(ByxButton::isLoading).anyMatch(b -> b.spinner().spinning());
                    Map<String, Object> out = Map.of("sections", List.copyOf(sections.keySet()), "regions", regionStates,
                            "chips", chipStates, "buttons", cellStates(sections.get("Buttons")),
                            "fields", cellStates(sections.get("Fields")), "loops", new boolean[] {shimmer, pulse, spinner},
                            "size", new double[] {img.getWidth(), img.getHeight()});
                    disposeAll(g);
                    return out;
                });
                assertEquals(ControlGallery.SECTIONS, r.get("sections"), mode.name());
                assertEquals(List.of(RegionState.values()), r.get("regions"), mode + " region states");
                assertTrue(((List<?>) r.get("chips")).containsAll(List.of(StatusState.values())), mode + " chips");
                @SuppressWarnings("unchecked")
                List<String> buttons = (List<String>) r.get("buttons");
                for (String s : new String[] {"hover", "focus", "pressed", "disabled", "loading"}) {
                    assertEquals(ByxButton.Variant.values().length, buttons.stream().filter(s::equals).count(), mode + " button " + s);
                }
                assertEquals(List.of("empty", "text", "masked", "focused", "invalid", "disabled", "revealed"), r.get("fields"));
                boolean[] loops = (boolean[]) r.get("loops");
                boolean full = mode == MotionPreference.FULL;
                assertEquals(full, loops[0], mode + " skeleton shimmer");
                assertEquals(full, loops[1], mode + " status pulse");
                assertEquals(mode != MotionPreference.OFF, loops[2], mode + " spinner");
                double[] size = (double[]) r.get("size");
                assertTrue(size[0] > 1000 && size[1] > 1000, mode + " rendered " + size[0] + "x" + size[1]);
            }
        } finally {
            css.removeHandler(h);
        }
        assertEquals(List.of(), cssWarnings, "JavaFX CSS warnings while rendering the gallery");
    }

    @Test
    void switchingModeRebuildsWithoutLeftoverLoops() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            MotionService m = new MotionService();
            ControlGallery g = new ControlGallery(m);
            new Scene(g, 1440, 900);
            g.applyCss(); // cria a skin do ScrollPane: só então o conteúdo entra no grafo da cena
            List<ByxRegion> fullRegions = all(g, ByxRegion.class);
            boolean fullShimmer = fullRegions.stream().anyMatch(ByxRegion::shimmering);
            m.preference.set(MotionPreference.REDUCED);
            g.applyCss();
            boolean oldStopped = fullRegions.stream().noneMatch(ByxRegion::shimmering);
            boolean reducedPulse = all(g, ByxStatusDot.class).stream().anyMatch(ByxStatusDot::pulsing);
            m.preference.set(MotionPreference.OFF);
            g.applyCss();
            boolean offSpinner = all(g, ByxButton.class).stream().filter(ByxButton::isLoading).anyMatch(b -> b.spinner().spinning());
            disposeAll(g);
            return new boolean[] {fullShimmer, oldStopped, reducedPulse, offSpinner};
        });
        assertTrue(r[0], "FULL shimmers");
        assertTrue(r[1], "regions from the previous mode are disposed");
        assertEquals(false, r[2], "no pulse in REDUCED");
        assertEquals(false, r[3], "no spinning in OFF");
    }

    /** Sem javafx-swing: copia os pixels ARGB para uma BufferedImage. */
    static java.awt.image.BufferedImage toAwt(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getPixelReader().getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static void disposeAll(ControlGallery g) {
        all(g, ByxRegion.class).forEach(ByxRegion::dispose);
        all(g, ByxButton.class).stream().filter(ByxButton::isLoading).forEach(b -> b.setLoading(false));
        g.host().dispose();
    }
}
