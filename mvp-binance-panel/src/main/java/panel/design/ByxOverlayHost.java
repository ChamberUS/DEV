package panel.design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Host das camadas 30–80 (P3.13). O que está aberto é estado lógico (pilha), não nós em animação: fechar
 * atualiza a pilha na hora e só a saída visual roda depois. Esc fecha apenas a camada do topo; abrir
 * qualquer camada fecha popovers; diálogos prendem o foco e devolvem ao abridor; toasts nunca pegam foco.
 */
public class ByxOverlayHost extends StackPane {
    /** Diálogo aberto. {@link #close()} é idempotente. */
    public final class DialogHandle {
        final Node panel;
        final Region backdrop;
        final StackPane holder;
        final boolean persistent;
        final Node opener;
        final Runnable onCancel;
        boolean open = true;

        DialogHandle(Node panel, Region backdrop, StackPane holder, boolean persistent, Node opener, Runnable onCancel) {
            this.panel = panel;
            this.backdrop = backdrop;
            this.holder = holder;
            this.persistent = persistent;
            this.opener = opener;
            this.onCancel = onCancel;
        }

        public boolean isOpen() {
            return open;
        }

        public void close() {
            closeDialog(this);
        }
    }

    public enum ToastKind {
        SUCCESS("check", "pos", "Success"), INFO("info", "inf", "Info"), WARNING("warning", "wrn", "Warning"),
        ERROR("error", "neg", "Error");

        final String icon;
        final String tone;
        final String label;

        ToastKind(String icon, String tone, String label) {
            this.icon = icon;
            this.tone = tone;
            this.label = label;
        }
    }

    private static final int TOAST_MAX = DesignTokens.get().node("components.toast.maxVisible").asInt();

    private final MotionService motion;
    private final Map<OverlayLayer, Pane> layers = new EnumMap<>(OverlayLayer.class);
    private final StackPane base = new StackPane();
    private final Deque<DialogHandle> dialogs = new ArrayDeque<>();
    private final List<Node> popovers = new ArrayList<>();
    private final VBox toastStack = new VBox(8);
    private final List<Node> toasts = new ArrayList<>();
    private Node palette;
    private Region paletteBackdrop;
    private Runnable paletteOnClose;
    private Node saveBar;
    private long generation;
    private final ChangeListener<Node> focusGuard = (o, a, b) -> keepFocusInDialog(b);

    public ByxOverlayHost(Node content, MotionService motion) {
        this.motion = motion;
        getStyleClass().add("byx-overlay-host");
        base.getChildren().add(content);
        getChildren().add(base);
        for (OverlayLayer l : java.util.Arrays.stream(OverlayLayer.values()).sorted(Comparator.comparingInt(OverlayLayer::z)).toList()) {
            Pane p = l == OverlayLayer.TOASTS ? new StackPane(toastStack) : new StackPane();
            p.setPickOnBounds(false);
            p.getStyleClass().addAll("byx-layer", "layer-" + l.name().toLowerCase(java.util.Locale.ROOT));
            p.setViewOrder(-l.z());
            layers.put(l, p);
            getChildren().add(p);
        }
        toastStack.getStyleClass().add("byx-toast-stack");
        toastStack.setAlignment(Pos.BOTTOM_LEFT);
        toastStack.setPickOnBounds(false);
        toastStack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(toastStack, Pos.BOTTOM_LEFT);
        StackPane.setMargin(toastStack, new Insets(16));
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        sceneProperty().addListener((o, a, b) -> {
            if (a != null) {
                a.focusOwnerProperty().removeListener(focusGuard);
            }
            if (b != null) {
                b.focusOwnerProperty().addListener(focusGuard);
            }
        });
    }

    public Pane layer(OverlayLayer l) {
        return layers.get(l);
    }

    /** Camada lógica do topo (null se nada aberto além de toasts/save bar). */
    public OverlayLayer topLayer() {
        if (!dialogs.isEmpty()) {
            return OverlayLayer.DIALOG;
        }
        if (palette != null) {
            return OverlayLayer.PALETTE;
        }
        if (!popovers.isEmpty()) {
            return OverlayLayer.POPOVER;
        }
        return null;
    }

    public int openDialogs() {
        return dialogs.size();
    }

    public int openPopovers() {
        return popovers.size();
    }

    public boolean paletteOpen() {
        return palette != null;
    }

    public int visibleToasts() {
        return toasts.size();
    }

    public void setToastMargin(Insets insets) {
        StackPane.setMargin(toastStack, insets);
    }

    // ---------------------------------------------------------------- save bar (30)

    public void showSaveBar(Node bar) {
        hideSaveBar();
        saveBar = bar;
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        layers.get(OverlayLayer.SAVEBAR).getChildren().add(bar);
        enter(bar, "settingsSaveBarShow", 24);
    }

    public void hideSaveBar() {
        if (saveBar != null) {
            Node old = saveBar;
            saveBar = null;
            exit(old, "settingsSaveBarHide", layers.get(OverlayLayer.SAVEBAR));
        }
    }

    public boolean saveBarVisible() {
        return saveBar != null;
    }

    // ---------------------------------------------------------------- popover (40)

    /** Popover posicionado pelo chamador (x, y no host). Abrir qualquer camada fecha os popovers anteriores. */
    public void openPopover(Node popover, double x, double y) {
        closePopovers();
        generation++;
        popover.setManaged(false);
        popover.relocate(x, y);
        popovers.add(popover);
        layers.get(OverlayLayer.POPOVER).getChildren().add(popover);
        if (popover instanceof Parent p) {
            p.applyCss();
            p.autosize();
        }
        enter(popover, "menuOpen", -4);
    }

    public void closePopovers() {
        for (Node p : List.copyOf(popovers)) {
            popovers.remove(p);
            exit(p, "menuClose", layers.get(OverlayLayer.POPOVER));
        }
    }

    // ---------------------------------------------------------------- palette (60)

    public void openPalette(Node panel, Runnable onClose) {
        closePopovers();
        closePalette();
        generation++;
        palette = panel;
        paletteOnClose = onClose;
        paletteBackdrop = backdrop(0.78);
        paletteBackdrop.setOnMouseClicked(e -> closePalette());
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new Insets(DesignTokens.get().number("components.palette.top"), 0, 0, 0));
        layers.get(OverlayLayer.PALETTE).getChildren().addAll(paletteBackdrop, panel);
        fadeBackdrop(paletteBackdrop, 0.78, "paletteOpen");
        popIn(panel, "paletteOpen");
    }

    public void closePalette() {
        if (palette == null) {
            return;
        }
        Node p = palette;
        Region b = paletteBackdrop;
        Runnable cb = paletteOnClose;
        palette = null;
        paletteBackdrop = null;
        paletteOnClose = null;
        exit(p, "paletteClose", layers.get(OverlayLayer.PALETTE));
        exit(b, "paletteClose", layers.get(OverlayLayer.PALETTE));
        if (cb != null) {
            cb.run();
        }
    }

    // ---------------------------------------------------------------- dialog (70)

    /**
     * Abre um diálogo. persistent: Esc e clique no fundo não fecham (sessão expirada, onboarding).
     * initialFocus: nó que recebe o foco (Cancel em diálogos destrutivos); null = primeiro focável.
     */
    public DialogHandle openDialog(Node panel, boolean persistent, Node initialFocus, Runnable onCancel) {
        closePopovers();
        generation++;
        Node opener = getScene() == null ? null : getScene().getFocusOwner();
        Region backdrop = backdrop(0.82);
        StackPane holder = new StackPane(backdrop, panel);
        holder.getStyleClass().add("byx-dialog-holder");
        StackPane.setAlignment(panel, Pos.CENTER);
        DialogHandle h = new DialogHandle(panel, backdrop, holder, persistent, opener, onCancel);
        backdrop.setOnMouseClicked(e -> {
            if (!h.persistent) {
                cancel(h);
            }
        });
        dialogs.push(h);
        layers.get(OverlayLayer.DIALOG).getChildren().add(holder);
        fadeBackdrop(backdrop, 0.82, "dialogOpen");
        popIn(panel, "dialogOpen");
        Node focus = initialFocus != null ? initialFocus : firstFocusable(panel);
        if (focus != null) {
            focus.requestFocus();
        }
        return h;
    }

    /**
     * Confirmação padrão (components.dialog 440 / 24 / 14). Destrutiva: botão negativo e foco em Cancel.
     */
    public DialogHandle confirm(String title, String text, String confirmLabel, boolean destructive, Runnable onConfirm) {
        Label t = new Label(title);
        t.getStyleClass().add("byx-section-title");
        t.setWrapText(true);
        Label body = new Label(text);
        body.getStyleClass().addAll("byx-body", "byx-secondary");
        body.setWrapText(true);
        ByxButton cancel = new ByxButton("Cancel", ByxButton.Variant.SECONDARY, motion);
        ByxButton ok = new ByxButton(confirmLabel, destructive ? ByxButton.Variant.DANGER : ByxButton.Variant.PRIMARY, motion);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, ok);
        VBox panel = new VBox(12, t, body, actions);
        panel.getStyleClass().add("byx-dialog");
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        panel.setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        panel.setAccessibleText(title);
        DialogHandle[] ref = new DialogHandle[1];
        cancel.setOnAction(e -> cancel(ref[0]));
        ok.setOnAction(e -> {
            closeDialog(ref[0]);
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        ref[0] = openDialog(panel, false, destructive ? cancel : ok, null);
        return ref[0];
    }

    private void cancel(DialogHandle h) {
        if (h != null && h.open) {
            closeDialog(h);
            if (h.onCancel != null) {
                h.onCancel.run();
            }
        }
    }

    private void closeDialog(DialogHandle h) {
        if (h == null || !h.open) {
            return;
        }
        h.open = false;
        dialogs.remove(h);
        generation++;
        exit(h.holder, "dialogClose", layers.get(OverlayLayer.DIALOG));
        Node back = dialogs.isEmpty() ? h.opener : firstFocusable(dialogs.peek().panel);
        if (back != null && back.getScene() != null && !back.isDisabled()) {
            back.requestFocus();
        }
    }

    // ---------------------------------------------------------------- toasts (80)

    /** Toast: success/info 4 s, warning 6 s, error fica até dispensar; hover pausa; 4º remove o mais antigo. */
    public Node toast(ToastKind kind, String text) {
        Label type = new Label(kind.label.toUpperCase(java.util.Locale.ROOT));
        type.getStyleClass().addAll("byx-label", "byx-toast-type");
        Label body = new Label(text);
        body.getStyleClass().addAll("byx-body", "byx-toast-text");
        body.setWrapText(true);
        VBox copy = new VBox(2, type, body);
        HBox.setHgrow(copy, Priority.ALWAYS);
        ByxButton dismiss = new ByxButton("Dismiss", ByxButton.Variant.GHOST, motion).small();
        dismiss.setFocusTraversable(false);
        HBox t = new HBox(12, ByxIcon.of(kind.icon, 18, kind.tone), copy, dismiss);
        t.getStyleClass().addAll("byx-toast", "toast-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        t.setAlignment(Pos.TOP_LEFT);
        t.setFocusTraversable(false);
        t.setAccessibleText(kind.label + ": " + text);
        dismiss.setOnAction(e -> removeToast(t));
        while (toasts.size() >= TOAST_MAX) {
            Node oldest = toasts.remove(0);
            toastStack.getChildren().remove(oldest); // imediato (toastStack.note)
            stopToastTimer(oldest);
        }
        toasts.add(t);
        toastStack.getChildren().add(t);
        enter(t, "toastEnter", 8);
        Duration d = autoDismiss(kind);
        if (d != null) {
            PauseTransition timer = new PauseTransition(d);
            timer.setOnFinished(e -> removeToast(t));
            t.getProperties().put("byx.toast.timer", timer);
            t.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> timer.pause());
            t.addEventHandler(MouseEvent.MOUSE_EXITED, e -> timer.play());
            timer.play();
        }
        return t;
    }

    /** BYX_MOTION_TOKENS.json#tokens.toastStack.autoDismissMs; texto ("never ...") = sem auto-dispensa. */
    static Duration autoDismiss(ToastKind kind) {
        var v = panel.motion.MotionSpec.get().token("toastStack").raw().path("autoDismissMs")
                .path(kind.name().toLowerCase(java.util.Locale.ROOT));
        return v.isNumber() ? Duration.millis(v.asDouble()) : null;
    }

    private void removeToast(Node t) {
        if (toasts.remove(t)) {
            stopToastTimer(t);
            exit(t, "toastExit", toastStack);
        }
    }

    private static void stopToastTimer(Node t) {
        if (t.getProperties().remove("byx.toast.timer") instanceof PauseTransition p) {
            p.stop();
        }
    }

    // ---------------------------------------------------------------- keyboard and focus

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            OverlayLayer top = topLayer();
            if (top == null) {
                return;
            }
            e.consume();
            switch (top) {
                case DIALOG -> {
                    DialogHandle h = dialogs.peek();
                    if (!h.persistent) {
                        cancel(h);
                    }
                }
                case PALETTE -> closePalette();
                case POPOVER -> closePopovers();
                default -> { }
            }
        } else if (e.getCode() == KeyCode.TAB && !dialogs.isEmpty()) {
            List<Node> f = focusables(dialogs.peek().panel);
            if (f.isEmpty()) {
                e.consume();
                return;
            }
            Node owner = getScene() == null ? null : getScene().getFocusOwner();
            int i = f.indexOf(owner);
            int next = e.isShiftDown() ? (i <= 0 ? f.size() - 1 : i - 1) : (i < 0 || i == f.size() - 1 ? 0 : i + 1);
            f.get(next).requestFocus();
            e.consume();
        }
    }

    private void keepFocusInDialog(Node owner) {
        if (dialogs.isEmpty() || owner == null) {
            return;
        }
        Node panel = dialogs.peek().panel;
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == panel) {
                return;
            }
        }
        Node first = firstFocusable(panel);
        if (first != null) {
            first.requestFocus();
        }
    }

    /**
     * Foco adiado (runLater): só aplica se nenhuma camada abriu ou fechou no meio, senão o foco cairia
     * atrás de um diálogo (motion rules).
     */
    public void requestFocusDeferred(Node target) {
        long g = generation;
        javafx.application.Platform.runLater(() -> {
            if (g == generation && target.getScene() != null) {
                target.requestFocus();
            }
        });
    }

    private static Node firstFocusable(Node root) {
        List<Node> f = focusables(root);
        return f.isEmpty() ? null : f.get(0);
    }

    private static List<Node> focusables(Node root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node n, List<Node> out) {
        if (!n.isVisible() || n.isDisabled()) {
            return;
        }
        if (n.isFocusTraversable()) {
            out.add(n);
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                collect(c, out);
            }
        }
    }

    // ---------------------------------------------------------------- motion

    private Region backdrop(double opacity) {
        Region b = new Region();
        b.getStyleClass().add("byx-backdrop");
        b.setOpacity(opacity);
        return b;
    }

    private void fadeBackdrop(Region b, double to, String token) {
        Duration d = motion == null ? Duration.ZERO : motion.duration(token);
        if (d.equals(Duration.ZERO)) {
            b.setOpacity(to);
            return;
        }
        FadeTransition f = new FadeTransition(d, b);
        f.setFromValue(0);
        f.setToValue(to);
        f.setInterpolator(motion.easing(token));
        track(b, f);
        f.play();
    }

    /** opacity 0→1 e escala .98→1 só em FULL (reducedScale 1). */
    private void popIn(Node n, String token) {
        Duration d = motion == null ? Duration.ZERO : motion.duration(token);
        n.setOpacity(1);
        n.setScaleX(1);
        n.setScaleY(1);
        if (d.equals(Duration.ZERO)) {
            return;
        }
        FadeTransition f = new FadeTransition(d, n);
        f.setFromValue(0);
        f.setToValue(1);
        f.setInterpolator(motion.easing(token));
        ParallelTransition p = new ParallelTransition(f);
        if (motion.scaleAllowed()) {
            n.setScaleX(0.98);
            n.setScaleY(0.98);
            p.getChildren().add(new Timeline(new KeyFrame(d, new KeyValue(n.scaleXProperty(), 1, motion.easing(token)),
                    new KeyValue(n.scaleYProperty(), 1, motion.easing(token)))));
        }
        track(n, p);
        p.play();
    }

    /** Entrada com deslocamento vertical (fromY) só em FULL. */
    private void enter(Node n, String token, double fromY) {
        Duration d = motion == null ? Duration.ZERO : motion.duration(token);
        n.setOpacity(1);
        n.setTranslateY(0);
        if (d.equals(Duration.ZERO)) {
            return;
        }
        FadeTransition f = new FadeTransition(d, n);
        f.setFromValue(0);
        f.setToValue(1);
        f.setInterpolator(motion.easing(token));
        ParallelTransition p = new ParallelTransition(f);
        if (motion.translateAllowed() && fromY != 0) {
            n.setTranslateY(fromY);
            p.getChildren().add(new Timeline(new KeyFrame(d, new KeyValue(n.translateYProperty(), 0, motion.easing(token)))));
        }
        track(n, p);
        p.play();
    }

    /** Saída visual depois da mudança lógica; o nó sai do layer ao fim (ou na hora em OFF). */
    private void exit(Node n, String token, Pane from) {
        if (n == null) {
            return;
        }
        n.setMouseTransparent(true);
        Duration d = motion == null ? Duration.ZERO : motion.duration(token);
        if (d.equals(Duration.ZERO)) {
            stopTracked(n);
            from.getChildren().remove(n);
            return;
        }
        FadeTransition f = new FadeTransition(d, n);
        f.setToValue(0);
        f.setInterpolator(motion.easing(token));
        f.setOnFinished(e -> from.getChildren().remove(n));
        track(n, f);
        f.play();
    }

    private static void track(Node n, javafx.animation.Animation a) {
        stopTracked(n);
        n.getProperties().put("byx.overlay.anim", a);
    }

    private static void stopTracked(Node n) {
        if (n.getProperties().remove("byx.overlay.anim") instanceof javafx.animation.Animation a) {
            a.stop();
        }
    }

    /** Remove tudo e para animações/timers (descarte do host). */
    public void dispose() {
        for (DialogHandle h : List.copyOf(dialogs)) {
            h.open = false;
        }
        dialogs.clear();
        popovers.clear();
        palette = null;
        saveBar = null;
        for (Node t : List.copyOf(toasts)) {
            stopToastTimer(t);
        }
        toasts.clear();
        for (Pane p : layers.values()) {
            p.getChildren().forEach(ByxOverlayHost::stopTracked);
        }
        toastStack.getChildren().clear();
        layers.forEach((l, p) -> {
            if (l != OverlayLayer.TOASTS) {
                p.getChildren().clear();
            }
        });
        Scene s = getScene();
        if (s != null) {
            s.focusOwnerProperty().removeListener(focusGuard);
        }
    }
}
