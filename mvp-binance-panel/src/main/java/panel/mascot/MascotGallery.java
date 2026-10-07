package panel.mascot;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.Snapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Galeria de DESENVOLVIMENTO do mascote (só em builds LOCAL_QA; fora do rail; aberta pela command palette). Mostra cada {@link MascotState} em FULL, REDUCED e OFF lado a lado, com tipo (loop /
 * um-tiro), duração, frames e poster, e uma régua de tamanhos (64..192 px) para revisar a escala sem upscale. Nada aqui chama rede, serviço ou escrita. Ao sair da tela tudo para; ao descartar, libera.
 */
public final class MascotGallery implements View {
    static final int[] SIZES = {64, 96, 128, 160, 192};

    private final MotionService motion;
    private final ScrollPane scroll;
    private final List<MascotView> all = new ArrayList<>();
    private final List<Runnable> starters = new ArrayList<>();
    private final MascotAssets assets;
    private final VBox page;
    private boolean requested;
    private boolean visible;

    public MascotGallery(MotionService motion, MascotAssets assets) {
        this.motion = motion;
        VBox page = Kit.page(14);
        page.getChildren().add(Kit.header("Mascot gallery", "Development review of every mascot state in FULL, REDUCED and OFF motion. Only present in LOCAL_QA builds."));
        page.getChildren().add(Kit.environment("DEV", "Review tool", "Not part of the product navigation. No network, no service call, nothing is written."));
        this.assets = assets;
        this.page = page;
        scroll = Kit.scroll(page);
    }

    private void build(VBox page, MascotManifest m, Throwable ex) {
        if (ex != null || m == null) {
            page.getChildren().add(Kit.panel("Assets", ByxBadge.of("MANIFEST UNAVAILABLE", ByxBadge.Tone.NEGATIVE), Kit.muted("The mascot manifest could not be read. The product falls back to posters or the empty stage.")));
            return;
        }
        for (MascotState s : MascotState.values()) {
            MascotManifest.Entry e = m.entry(s).orElse(null);
            if (e == null) {
                continue;
            }
            Label kind = ByxBadge.of(e.loop() ? "LOOP" : "ONE-SHOT", e.loop() ? ByxBadge.Tone.INFO : ByxBadge.Tone.ACCENT);
            long ms = e.durationMs() > 0 ? e.durationMs() : Math.round(e.frames() * 1000.0 / e.fps());
            HBox heads = new HBox(8, Fx.label(s.name(), "byx-section-title-sm"), kind, ByxBadge.of(ms + " ms · " + e.frames() + " frames · " + (e.durationMs() > 0 ? Math.round(e.frames() * 1000.0 / e.durationMs()) : e.fps()) + " fps", ByxBadge.Tone.NEUTRAL));
            heads.setAlignment(Pos.CENTER_LEFT);
            HBox views = new HBox(18);
            views.setAlignment(Pos.CENTER_LEFT);
            ByxButton replay = new ByxButton(e.loop() ? "Restart" : "Replay", ByxButton.Variant.SECONDARY, motion).small();
            MascotView full = cell(views, "FULL", MotionPreference.FULL, s);
            cell(views, "REDUCED", MotionPreference.REDUCED, s);
            cell(views, "OFF", MotionPreference.OFF, s);
            replay.setOnAction(ev -> start(full, s));
            views.getChildren().add(replay);
            page.getChildren().add(Kit.panel(null, heads, Kit.dim(e.recommendedUse()), views));
        }
        FlowPane sizes = new FlowPane(16, 12);
        for (int px : SIZES) {
            MascotView v = new MascotView(motion, px, MascotAssets.shared(), javafx.application.Platform::runLater, () -> 1.0, MascotStage.ACCENT_BYX);
            v.setMotionMode(MotionPreference.FULL);
            all.add(v);
            starters.add(() -> v.setState(MascotState.IDLE));
            VBox box = new VBox(4, v, Fx.label(px + " px", "byx-desk-secondary"));
            box.setAlignment(Pos.CENTER);
            sizes.getChildren().add(box);
        }
        page.getChildren().add(Kit.panel("Sizes", Kit.muted("IDLE at 64, 96, 128, 160 and 192 px, decoded at the size shown (no upscale)."), sizes));
        if (visible) {
            starters.forEach(Runnable::run);
        }
    }

    private MascotView cell(HBox row, String label, MotionPreference mode, MascotState s) {
        MascotView v = new MascotView(motion, 112, MascotAssets.shared(), javafx.application.Platform::runLater, () -> 1.0, MascotStage.ACCENT_BYX);
        v.setMotionMode(mode);
        all.add(v);
        starters.add(() -> start(v, s));
        VBox box = new VBox(4, v, Fx.label(label, "byx-desk-secondary"));
        box.setAlignment(Pos.CENTER);
        row.getChildren().add(box);
        return v;
    }

    private static void start(MascotView v, MascotState s) {
        if (s.loops()) {
            v.setState(s);
        } else {
            v.setState(MascotState.IDLE);
            v.play(s);
        }
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        // sem assinatura
    }

    @Override
    public void onShow() {
        visible = true;
        if (!requested) { // LAZY: nada é lido antes de a galeria ser aberta
            requested = true;
            assets.manifest().whenComplete((m, ex) -> javafx.application.Platform.runLater(() -> build(page, m, ex)));
        }
        starters.forEach(Runnable::run);
    }

    @Override
    public void onHide() {
        visible = false;
        all.forEach(MascotView::stop);
    }

    /** Libera tudo (decoders/imagens). */
    public void dispose() {
        onHide();
        all.forEach(MascotView::dispose);
        all.clear();
        starters.clear();
    }

    int views() {
        return all.size();
    }
}
