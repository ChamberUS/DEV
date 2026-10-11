package panel.mascot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.Snapshot;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Galeria 2 de DESENVOLVIMENTO do mascote (só em builds LOCAL_QA; fora do rail; command palette). Um lugar para rever a PERSONALIDADE: seletor de estado, modo FULL/REDUCED/OFF, modo do palco
 * (HALO/TRANSPARENT/SURFACE), tamanho semântico, âncora, fundos escuros, contexto + dica (com o cooldown real do guia), simulação de janela sem foco, demo de ponteiro (alvo sintético em órbita) e a matriz
 * completa estado × modo. Nada aqui chama rede, serviço ou escrita; ao sair da tela tudo para; ao descartar, libera.
 */
public final class MascotGallery implements View {
    static final int[] SIZES = {64, 96, 128, 160, 192};
    private static final String[] BACKGROUNDS = {"bg0 #0B0E16", "bg1 #121723", "bg2 #1A2030", "bg3 #232A3D", "hover #2C3550", "light #EEF1F8"};

    private final MotionService motion;
    private final MascotAssets assets;
    private final ScrollPane scroll;
    private final VBox page;
    private final List<MascotView> matrix = new ArrayList<>();
    private final List<Runnable> starters = new ArrayList<>();
    private final MascotGuide guide = new MascotGuide(new StaticMascotGuideProvider(), System::currentTimeMillis); // guia PRÓPRIO da galeria: não consome o cooldown do app
    private final StackPane previewHost = new StackPane();
    private final Pane markerLayer = new Pane();
    private final Circle marker = new Circle(5, Color.web("#5ED6C4"));
    private final Label hintStatus = Kit.dim("");
    private MascotView view;
    private MascotHintBubble bubble;
    private MascotState state = MascotState.IDLE;
    private MotionPreference mode = MotionPreference.FULL;
    private MascotStage.Mode stageMode = MascotStage.DEFAULT_MODE;
    private MascotSize sizeChoice = MascotSize.MEDIUM;
    private MascotAnchor anchor = MascotAnchor.TOP_RIGHT;
    private String background = BACKGROUNDS[1];
    private MascotContext context = MascotContext.CHAIN_OFFLINE;
    private boolean unfocused;
    private Timeline orbit;
    private double angle;
    private boolean requested;
    private boolean visible;

    public MascotGallery(MotionService motion, MascotAssets assets) {
        this.motion = motion;
        this.assets = assets;
        page = Kit.page(14);
        page.getChildren().add(Kit.header("Mascot gallery", "Development review of the mascot's personality. Only present in LOCAL_QA builds."));
        page.getChildren().add(Kit.environment("DEV", "Review tool", "Not part of the product navigation. No network, no service call, nothing is written."));
        scroll = Kit.scroll(page);
    }

    private void buildControls() {
        List<String> states = Arrays.stream(MascotState.values()).map(Enum::name).toList();
        VBox controls = Kit.panel("Controls",
                row("State", new Kit.Segmented(states, state.name(), v -> { state = MascotState.valueOf(v); applyState(); })),
                row("Motion", new Kit.Segmented(List.of("FULL", "REDUCED", "OFF"), "FULL", v -> { mode = MotionPreference.valueOf(v); view.setMotionMode(mode); applyState(); })),
                row("Stage", new Kit.Segmented(List.of("HALO", "TRANSPARENT", "SURFACE"), stageMode.name(), v -> { stageMode = MascotStage.Mode.valueOf(v); view.setStageMode(stageMode); })),
                row("Size", new Kit.Segmented(Arrays.stream(MascotSize.values()).map(s -> s.name() + " " + s.px()).toList(), sizeChoice.name() + " " + sizeChoice.px(), v -> { sizeChoice = MascotSize.valueOf(v.split(" ")[0]); rebuild(); })),
                row("Anchor", new Kit.Segmented(Arrays.stream(MascotAnchor.values()).map(Enum::name).toList(), anchor.name(), v -> { anchor = MascotAnchor.valueOf(v); rebuild(); })),
                row("Background", new Kit.Segmented(Arrays.asList(BACKGROUNDS), background, v -> { background = v; previewHost.setStyle("-fx-background-color: " + v.split(" ")[1] + "; -fx-background-radius: 12;"); })),
                contextRow(), simulationRow());
        previewHost.setMinHeight(230);
        previewHost.setPadding(new Insets(16));
        previewHost.setStyle("-fx-background-color: #121723; -fx-background-radius: 12;");
        markerLayer.setMouseTransparent(true);
        marker.setVisible(false);
        markerLayer.getChildren().add(marker);
        StackPane stackedPreview = new StackPane(previewHost, markerLayer);
        page.getChildren().addAll(controls, Kit.panel("Preview", Kit.muted("Move the pointer around the mascot (IDLE follows it with its eyes; THINKING, PROCESSING and SYNCING ignore it). Click it for the hint. "
                + "Window focus loss and the orbit demo are simulated below."), stackedPreview));
        rebuild();
    }

    private HBox contextRow() {
        ComboBox<MascotContext> box = new ComboBox<>();
        box.getItems().setAll(MascotContext.values());
        box.setValue(context);
        box.setOnAction(e -> context = box.getValue());
        ByxButton ask = new ByxButton("Ask (click)", ByxButton.Variant.SECONDARY, motion).small();
        ask.setOnAction(e -> guide.ask(context).ifPresentOrElse(h -> { hintStatus.setText("ask: always answers"); bubble.show(h); }, () -> hintStatus.setText("ask: no hint for this context (the normal error UI is the authority)")));
        ByxButton offer = new ByxButton("Offer (auto, rate-limited)", ByxButton.Variant.SECONDARY, motion).small();
        offer.setOnAction(e -> guide.offer(context).ifPresentOrElse(h -> { hintStatus.setText("offer: shown"); bubble.show(h); if (h.reaction() != null) { view.play(h.reaction()); } },
                () -> hintStatus.setText("offer: suppressed (cooldown " + MascotGuide.COOLDOWN_MS / 1000 + " s, or first-visit already given this session, or no hint)")));
        ByxButton reset = new ByxButton("Reset guide session", ByxButton.Variant.GHOST, motion).small();
        reset.setOnAction(e -> { guide.resetSession(); hintStatus.setText("guide session reset"); });
        HBox h = new HBox(8, box, ask, offer, reset, hintStatus);
        h.setAlignment(Pos.CENTER_LEFT);
        return row("Context", h);
    }

    private HBox simulationRow() {
        ToggleButton focus = new ToggleButton("Window unfocused");
        focus.setOnAction(e -> { unfocused = focus.isSelected(); view.setFocusOverride(unfocused ? Boolean.FALSE : null); });
        ToggleButton orbitToggle = new ToggleButton("Orbit pointer demo");
        orbitToggle.setOnAction(e -> { if (orbitToggle.isSelected()) { startOrbit(); } else { stopOrbit(); } });
        ByxButton replay = new ByxButton("Replay state", ByxButton.Variant.SECONDARY, motion).small();
        replay.setOnAction(e -> applyState());
        HBox h = new HBox(8, focus, orbitToggle, replay);
        h.setAlignment(Pos.CENTER_LEFT);
        return row("Simulation", h);
    }

    private static HBox row(String label, Node content) {
        Label l = Fx.label(label, "byx-desk-secondary");
        l.setMinWidth(92);
        HBox h = new HBox(10, l, content);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void rebuild() {
        stopOrbit();
        if (bubble != null) {
            bubble.hide();
        }
        if (view != null) {
            view.dispose();
        }
        view = new MascotView(motion, sizeChoice);
        view.setStageMode(stageMode);
        view.setMotionMode(mode);
        view.setFocusOverride(unfocused ? Boolean.FALSE : null);
        bubble = new MascotHintBubble(view);
        view.setInteractive(true, () -> guide.ask(context).ifPresentOrElse(bubble::toggle, () -> hintStatus.setText("click: no hint for this context")));
        Label sample = Fx.label("Sample content card", "byx-section-title-sm");
        VBox content = new VBox(6, sample, Kit.muted("This stands in for a screen section. The mascot is placed by its closed anchor; there are no per-screen coordinates."));
        content.setPadding(new Insets(8));
        content.setMinHeight(140);
        previewHost.getChildren().setAll(MascotPlacement.place(content, view, anchor));
        applyState();
    }

    private void applyState() {
        if (view == null) {
            return;
        }
        if (state.loops()) {
            view.setState(state);
        } else {
            view.setState(MascotState.IDLE);
            view.play(state);
        }
    }

    private void startOrbit() {
        stopOrbit();
        marker.setVisible(true);
        orbit = new Timeline(new KeyFrame(Duration.millis(50), e -> {
            if (view.getScene() == null) {
                return;
            }
            angle += 0.07;
            Point2D c = view.localToScene(view.size() / 2, view.size() / 2);
            double r = Math.max(120, view.size() * 1.6);
            double x = c.getX() + Math.cos(angle) * r;
            double y = c.getY() + Math.sin(angle) * r * 0.7;
            view.pointerAtScene(x, y);
            Point2D local = markerLayer.sceneToLocal(x, y);
            marker.setCenterX(local.getX());
            marker.setCenterY(local.getY());
        }));
        orbit.setCycleCount(Animation.INDEFINITE);
        orbit.play();
    }

    private void stopOrbit() {
        if (orbit != null) {
            orbit.stop();
            orbit = null;
        }
        marker.setVisible(false);
    }

    private void buildMatrix(MascotManifest m, Throwable ex) {
        if (ex != null || m == null) {
            page.getChildren().add(Kit.panel("Assets", ByxBadge.of("MANIFEST UNAVAILABLE", ByxBadge.Tone.NEGATIVE), Kit.muted("The mascot manifest could not be read. The product falls back to posters or nothing.")));
            return;
        }
        buildControls();
        for (MascotState s : MascotState.values()) {
            MascotManifest.Entry e = m.entry(s).orElse(null);
            if (e == null) {
                continue;
            }
            Label kind = ByxBadge.of(e.loop() ? "LOOP" : "ONE-SHOT", e.loop() ? ByxBadge.Tone.INFO : ByxBadge.Tone.ACCENT);
            long ms = e.durationMs() > 0 ? e.durationMs() : Math.round(e.frames() * 1000.0 / e.fps());
            HBox heads = new HBox(8, Fx.label(s.name() + (s == MascotState.IDLE ? " (procedural rig)" : ""), "byx-section-title-sm"), kind, ByxBadge.of(ms + " ms · " + e.frames() + " frames", ByxBadge.Tone.NEUTRAL));
            heads.setAlignment(Pos.CENTER_LEFT);
            HBox views = new HBox(18);
            views.setAlignment(Pos.CENTER_LEFT);
            cell(views, "FULL", MotionPreference.FULL, s);
            cell(views, "REDUCED", MotionPreference.REDUCED, s);
            cell(views, "OFF", MotionPreference.OFF, s);
            page.getChildren().add(Kit.panel(null, heads, Kit.dim(e.recommendedUse()), views));
        }
        if (visible) {
            starters.forEach(Runnable::run);
        }
    }

    private void cell(HBox row, String label, MotionPreference mode, MascotState s) {
        MascotView v = new MascotView(motion, MascotSize.MEDIUM);
        v.setMotionMode(mode);
        matrix.add(v);
        starters.add(() -> start(v, s));
        VBox box = new VBox(4, v, Fx.label(label, "byx-desk-secondary"));
        box.setAlignment(Pos.CENTER);
        row.getChildren().add(box);
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
            assets.manifest().whenComplete((m, ex) -> javafx.application.Platform.runLater(() -> buildMatrix(m, ex)));
        } else if (view != null) {
            applyState();
        }
        starters.forEach(Runnable::run);
    }

    @Override
    public void onHide() {
        visible = false;
        stopOrbit();
        if (bubble != null) {
            bubble.hide();
        }
        if (view != null) {
            view.stop();
        }
        matrix.forEach(MascotView::stop);
    }

    public void dispose() {
        onHide();
        if (view != null) {
            view.dispose();
            view = null;
        }
        matrix.forEach(MascotView::dispose);
        matrix.clear();
        starters.clear();
    }

    int views() {
        return matrix.size();
    }
}
