package panel.design;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import panel.motion.MotionService;
import panel.motion.MotionSpec;

/**
 * RegionContainer (P3.10): propriedade {@code state} e uma camada por estado. Aceita só os estados que o
 * componente pode ter. LOADING nunca é infinito (nota lenta com Retry/Keep waiting, depois ERROR); esses
 * tempos são lógicos e iguais em FULL, REDUCED e OFF. Callback de timer de um estado anterior não faz nada.
 */
public class ByxRegion extends StackPane {
    private static final PseudoClass STALE = PseudoClass.getPseudoClass("stale");

    /** Texto e ações do estado. Mensagens vêm do chamador (backend/conteúdo), nunca de exceções cruas. */
    public record Detail(String title, String text, String actionText, Runnable action) {
        public static Detail of(String title, String text) {
            return new Detail(title, text, null, null);
        }

        public static Detail none() {
            return new Detail(null, null, null, null);
        }

        public Detail withAction(String label, Runnable run) {
            return new Detail(title, text, label, run);
        }
    }

    private final String name;
    private final Set<RegionState> allowed;
    private final MotionService motion;
    private final ReadOnlyObjectWrapper<RegionState> state = new ReadOnlyObjectWrapper<>(this, "state");
    private final StackPane contentHolder = new StackPane();
    private final VBox contentStack = new VBox(12);
    private Node content;
    private Runnable onRetry;
    private Duration slowAfter;
    private Duration timeoutAfter;
    private long token;
    private PauseTransition slowTimer;
    private PauseTransition timeoutTimer;
    private Animation swap;
    private Timeline shimmer;
    private final DoubleProperty shimmerPhase = new SimpleDoubleProperty(0);
    private VBox slowNote;
    private Label lastUpdate;

    public ByxRegion(String name, Set<RegionState> allowedStates, MotionService motion) {
        this.name = name;
        this.allowed = Collections.unmodifiableSet(EnumSet.copyOf(allowedStates));
        this.motion = motion;
        if (allowed.contains(RegionState.LOADING) && !allowed.contains(RegionState.ERROR)) {
            throw new IllegalArgumentException(name + ": LOADING requires ERROR (loading is never infinite)");
        }
        DesignTokens t = DesignTokens.get();
        slowAfter = Duration.millis(t.number("behaviour.loadingSlowAfterMs"));
        timeoutAfter = Duration.millis(t.number("behaviour.loadingTimeoutAfterMs"));
        getStyleClass().add("byx-region");
        contentStack.getChildren().add(contentHolder);
        VBox.setVgrow(contentHolder, Priority.ALWAYS);
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                stopShimmer();
            } else if (state.get() == RegionState.LOADING) {
                startShimmer();
            }
        });
    }

    public String name() {
        return name;
    }

    public Set<RegionState> allowed() {
        return allowed;
    }

    public ReadOnlyObjectProperty<RegionState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public RegionState state() {
        return state.get();
    }

    /** Conteúdo real (READY); continua visível em PARTIAL, STALE e DEGRADED. Atualizar não reanima. */
    public void setContent(Node node) {
        content = node;
        contentHolder.getChildren().setAll(node == null ? java.util.List.of() : java.util.List.of(node));
    }

    public void setOnRetry(Runnable r) {
        onRetry = r;
    }

    /** Só para testes e demonstrações (a referência comprime os limites apenas em DEMO). */
    public void setLoadingThresholds(Duration slow, Duration timeout) {
        slowAfter = slow;
        timeoutAfter = timeout;
    }

    public void setState(RegionState s) {
        setState(s, Detail.none());
    }

    public void setState(RegionState s, Detail detail) {
        if (!allowed.contains(s)) {
            throw new IllegalArgumentException(name + " cannot be " + s + " (allowed " + allowed + ")");
        }
        RegionState prev = state.get();
        token++;
        cancelTimers();
        Node layer = build(s, detail);
        boolean sameLayer = prev != null && layerKey(prev) == layerKey(s) && prev.showsContent() && s.showsContent();
        getChildren().setAll(layer);
        state.set(s);
        pseudoClassStateChanged(STALE, s == RegionState.STALE);
        contentHolder.setOpacity(s == RegionState.STALE ? DesignTokens.get().number("behaviour.staleOpacity") : 1);
        if (s == RegionState.LOADING) {
            startShimmer();
            startLoadingTimers(token);
        } else {
            stopShimmer();
        }
        if (prev != null && prev != s && !sameLayer) {
            playSwap(layer);
        }
    }

    /** Nota lenta visível (LOADING passou de loadingSlowAfterMs). */
    public boolean slowNoteVisible() {
        return slowNote != null && slowNote.isVisible() && state.get() == RegionState.LOADING;
    }

    public boolean shimmering() {
        return shimmer != null && shimmer.getStatus() == Animation.Status.RUNNING;
    }

    /** Para timers e animações; a região não é reutilizada depois. */
    public void dispose() {
        token++;
        cancelTimers();
        stopShimmer();
        if (swap != null) {
            swap.stop();
            swap = null;
        }
    }

    // ---------------------------------------------------------------- layers

    private Object layerKey(RegionState s) {
        return s.showsContent() ? "content" : s;
    }

    private Node build(RegionState s, Detail d) {
        if (s.showsContent()) {
            contentStack.getChildren().setAll(contentHolder);
            switch (s) {
                case PARTIAL -> contentStack.getChildren().add(0, new ByxBanner(ByxBanner.Kind.WARNING,
                        or(d.title(), "Some values are unavailable"), or(d.text(), "Part of this section could not be read. The rest is current.")));
                case DEGRADED -> contentStack.getChildren().add(0, new ByxBanner(ByxBanner.Kind.WARNING,
                        or(d.title(), "Degraded"), or(d.text(), "Live but slower or less complete than usual.")));
                case STALE -> contentStack.getChildren().add(0, staleBar(d));
                default -> { }
            }
            if (s == RegionState.PARTIAL && onRetry != null) {
                ByxButton retry = new ByxButton(or(d.actionText(), "Retry failed part"), ByxButton.Variant.SECONDARY, motion).small();
                retry.setOnAction(e -> run(d.action() != null ? d.action() : onRetry));
                contentStack.getChildren().add(1, retry);
            }
            return contentStack;
        }
        Node n = switch (s) {
            case LOADING -> loadingLayer();
            case EMPTY -> message("info", null, or(d.title(), "Nothing to show"), d.text(), d.actionText(), d.action(), "empty");
            case ERROR -> message("error", "neg", or(d.title(), "Could not load this section."), d.text(),
                    or(d.actionText(), "Retry"), d.action() != null ? d.action() : onRetry, "error");
            case UNAVAILABLE -> message("warning", "wrn", or(d.title(), "Unavailable"),
                    or(d.text(), "The source does not exist right now."), null, null, "unavailable");
            case LOCKED -> message("lock", "neg", or(d.title(), "Locked"), d.text(), null, null, "locked");
            case NOT_CONFIGURED -> message("settings", null, or(d.title(), "Not configured"),
                    or(d.text(), "Nothing has been set up yet."), d.actionText(), d.action(), "not-configured");
            default -> throw new IllegalStateException(s.name());
        };
        return n;
    }

    private Node staleBar(Detail d) {
        Label chip = new Label("STALE");
        chip.getStyleClass().add("byx-stale-chip");
        lastUpdate = new Label("LAST UPDATE " + or(d.text(), "—"));
        lastUpdate.getStyleClass().addAll("byx-label", "byx-stale-time");
        HBox bar = new HBox(10, chip, lastUpdate);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("byx-stale-bar");
        return bar;
    }

    public String lastUpdateText() {
        return lastUpdate == null ? null : lastUpdate.getText();
    }

    private Node message(String icon, String tone, String title, String text, String actionText, Runnable action,
            String cls) {
        Label t = new Label(title);
        t.getStyleClass().add("byx-section-title-sm");
        t.setWrapText(true);
        VBox box = new VBox(8, ByxIcon.of(icon, 20, tone), t);
        if (text != null && !text.isBlank()) {
            Label body = new Label(text);
            body.getStyleClass().addAll("byx-body", "byx-secondary");
            body.setWrapText(true);
            box.getChildren().add(body);
        }
        // ação só quando é real: sem handler, sem botão
        if (actionText != null && action != null) {
            ByxButton b = new ByxButton(actionText, ByxButton.Variant.SECONDARY, motion).small();
            b.setOnAction(e -> run(action));
            box.getChildren().add(b);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("byx-region-message", cls);
        box.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane wrap = new StackPane(box);
        wrap.setAlignment(Pos.CENTER_LEFT);
        return wrap;
    }

    private Node loadingLayer() {
        VBox skel = new VBox(10);
        skel.getStyleClass().add("byx-skeleton");
        double[] widths = {0.70, 0.55, 0.65}; // referência .sk: 8 px, raio 4, 70/55/65%
        for (double w : widths) {
            Rectangle r = new Rectangle(0, 8);
            r.setArcWidth(8);
            r.setArcHeight(8);
            r.widthProperty().bind(widthProperty().subtract(32).multiply(w));
            r.fillProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(this::skeletonFill, shimmerPhase, ByxTheme.modeProperty()));
            r.getStyleClass().add("byx-skeleton-bar");
            skel.getChildren().add(r);
        }
        slowNote = new VBox(8);
        slowNote.getStyleClass().add("byx-slow-note");
        Label slowText = new Label("This is taking longer than expected.");
        slowText.getStyleClass().addAll("byx-body", "byx-secondary");
        HBox actions = new HBox(8);
        if (onRetry != null) {
            ByxButton retry = new ByxButton("Retry", ByxButton.Variant.SECONDARY, motion).small();
            retry.setOnAction(e -> run(onRetry));
            actions.getChildren().add(retry);
        }
        ByxButton keep = new ByxButton("Keep waiting", ByxButton.Variant.GHOST, motion).small();
        keep.setOnAction(e -> {
            slowNote.setVisible(false);
            slowNote.setManaged(false);
        });
        actions.getChildren().add(keep);
        slowNote.getChildren().addAll(slowText, actions);
        slowNote.setVisible(false);
        slowNote.setManaged(false);
        VBox box = new VBox(14, skel, slowNote);
        box.setAccessibleText(name + " loading");
        return box;
    }

    private LinearGradient skeletonFill() {
        Color base = ByxTheme.color(ThemeToken.SURFACE_ELEVATED);
        Color hi = ByxTheme.paint("#303958", ThemeToken.SURFACE_HOVER); // destaque da referência (.sk)
        double p = shimmerPhase.get();
        if (p <= 0) {
            return new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, base), new Stop(1, base));
        }
        double c = -0.5 + p * 2; // percorre 200% (backgroundShiftPercent)
        return new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, base),
                new Stop(clamp(c - 0.25), base), new Stop(clamp(c), hi), new Stop(clamp(c + 0.25), base), new Stop(1, base));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ---------------------------------------------------------------- motion and timers

    private void startShimmer() {
        stopShimmer();
        MotionSpec.Resolved r = motion == null ? null : motion.token("regionLoading");
        if (r == null || !r.runs() || getScene() == null) {
            return; // REDUCED e OFF: esqueleto estático
        }
        shimmer = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(shimmerPhase, 0.001)),
                new KeyFrame(r.duration(), new KeyValue(shimmerPhase, 1)));
        shimmer.setCycleCount(Animation.INDEFINITE);
        shimmer.play();
    }

    private void stopShimmer() {
        if (shimmer != null) {
            shimmer.stop();
            shimmer = null;
        }
        shimmerPhase.set(0);
    }

    private void startLoadingTimers(long t) {
        slowTimer = new PauseTransition(slowAfter);
        slowTimer.setOnFinished(e -> {
            if (t == token && state.get() == RegionState.LOADING && slowNote != null) {
                slowNote.setVisible(true);
                slowNote.setManaged(true);
            }
        });
        timeoutTimer = new PauseTransition(timeoutAfter);
        timeoutTimer.setOnFinished(e -> {
            if (t == token && state.get() == RegionState.LOADING) {
                setState(RegionState.ERROR, Detail.of("Could not load this section.", null));
            }
        });
        slowTimer.play();
        timeoutTimer.play();
    }

    private void cancelTimers() {
        if (slowTimer != null) {
            slowTimer.stop();
            slowTimer = null;
        }
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }

    private void playSwap(Node layer) {
        if (swap != null) {
            swap.stop();
            swap = null;
        }
        layer.setOpacity(1);
        Duration d = motion == null ? Duration.ZERO : motion.duration("regionStateSwap");
        if (d.equals(Duration.ZERO)) {
            return;
        }
        FadeTransition f = new FadeTransition(d, layer);
        f.setFromValue(0);
        f.setToValue(1);
        f.setInterpolator(motion.easing("regionStateSwap"));
        f.setOnFinished(e -> {
            layer.setOpacity(1);
            swap = null;
        });
        swap = f;
        f.play();
    }

    private static void run(Runnable r) {
        if (r != null) {
            r.run();
        }
    }

    private static String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
