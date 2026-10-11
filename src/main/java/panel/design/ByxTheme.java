package panel.design;

import java.util.List;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Node;
import javafx.scene.paint.Color;

/** Folhas do tema V2 na ordem de dependência (tokens antes de tudo). */
public final class ByxTheme {
    public static final List<String> STYLESHEETS = List.of(DesignTokens.STYLESHEET, "/panel/v2/typography.css",
            "/panel/v2/controls.css", "/panel/v2/shell.css", "/panel/v2/auth.css", "/panel/v2/trading.css", "/panel/v2/research.css", "/panel/v2/screens.css",
            "/panel/v2/theme-palette.css", "/panel/v2/theme-controls.css");

    private static final ReadOnlyObjectWrapper<ThemeMode> MODE = new ReadOnlyObjectWrapper<>(ThemeMode.DARK);
    private static boolean windowHooksInstalled;
    private static final java.util.Set<Parent> ROOTS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static ThemeMode mode() { return MODE.get(); }
    public static ReadOnlyObjectProperty<ThemeMode> modeProperty() { return MODE.getReadOnlyProperty(); }
    public static boolean systemSupported() { return false; }
    public static Color color(ThemeToken token) { return ThemePalette.of(mode()).color(token); }

    /** Synchronous display-only transition. The same nodes, sessions and operations remain alive. */
    public static void select(ThemeMode next) {
        java.util.Objects.requireNonNull(next);
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Theme updates require the FX Application Thread");
        if (next == ThemeMode.SYSTEM) throw new IllegalArgumentException("System appearance is unsupported in this runtime");
        if (next == mode()) return;
        // Mark cached roots cheaply. JavaFX resolves CSS before layout on the next pulse.
        // Eager applyCss on every detached Scene would traverse unrelated cached views.
        for (Parent root : java.util.List.copyOf(ROOTS)) {
            mark(root, next);

        }
        MODE.set(next);
        Platform.requestNextPulse();
    }

    public static Color paint(String darkBaseline, ThemeToken lightToken) {
        return mode() == ThemeMode.DARK ? Color.web(darkBaseline) : color(lightToken);
    }
    public static Color alpha(Color color, double opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }

    public static void resetSession() { select(ThemeMode.DARK); }

    public static void register(Parent root) { ROOTS.add(root); mark(root, mode()); }

    private static void mark(Parent root, ThemeMode mode) {
        // Pseudo classes preserve the established structural/context class lists.
        root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("byx-theme-dark"), mode == ThemeMode.DARK);
        root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("byx-theme-light"), mode == ThemeMode.LIGHT);
    }

    /** Weak observable subscription scoped to the node's Scene lifetime, with no timer or backend action. */
    public static void observe(Node node, Runnable repaint) {
        ChangeListener<ThemeMode> delegate = (o, a, b) -> repaint.run();
        WeakChangeListener<ThemeMode> weak = new WeakChangeListener<>(delegate);
        node.getProperties().put("byx.theme.delegate", delegate);
        node.sceneProperty().addListener((o, a, b) -> {
            if (a != null) modeProperty().removeListener(weak);
            if (b != null) { modeProperty().addListener(weak); repaint.run(); }
        });
        if (node.getScene() != null) modeProperty().addListener(weak);
    }

    private static void installPopupHooks() {
        if (windowHooksInstalled) return;
        windowHooksInstalled = true;
        // Standard ComboBox, ContextMenu and Tooltip skins live in native PopupWindow scenes.
        javafx.stage.Window.getWindows().addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
            while (change.next()) if (change.wasAdded()) for (var window : change.getAddedSubList()) {
                if (window instanceof javafx.stage.PopupWindow popup && popup.getOwnerWindow() != null) {
                    Scene owner = popup.getOwnerWindow().getScene();
                    if (owner != null && ROOTS.contains(owner.getRoot()) && window.getScene() != null) apply(window.getScene());
                }
            }
        });
    }

    private ByxTheme() {
    }

    public static String stylesheetUrl(String path) {
        var resource = ByxTheme.class.getResource(path);
        if (resource == null) throw new IllegalStateException("Missing theme stylesheet " + path);
        return resource.toExternalForm();
    }

    public static List<String> urls() {
        return STYLESHEETS.stream().map(ByxTheme::stylesheetUrl).toList();
    }

    public static void apply(Scene scene) {
        installPopupHooks();
        ByxFonts.load();
        ROOTS.add(scene.getRoot());
        mark(scene.getRoot(), mode());
        // Root replacement (authentication/shell lifecycle) inherits the active session appearance.
        if (scene.getProperties().putIfAbsent("byx.theme.installed", Boolean.TRUE) == null) {
            scene.rootProperty().addListener((o, a, b) -> { ROOTS.add(b); mark(b, mode()); });
        }
        urls().forEach(u -> {
            if (!scene.getStylesheets().contains(u)) {
                scene.getStylesheets().add(u);
            }
        });
    }

    public static void apply(Parent parent) {
        installPopupHooks();
        ByxFonts.load();
        ROOTS.add(parent);
        mark(parent, mode());
        urls().forEach(u -> {
            if (!parent.getStylesheets().contains(u)) {
                parent.getStylesheets().add(u);
            }
        });
    }
}
