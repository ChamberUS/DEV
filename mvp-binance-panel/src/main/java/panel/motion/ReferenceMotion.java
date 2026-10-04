package panel.motion;

import java.util.List;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.util.Duration;

/** Executable CSS motion from the bundled reference; no navigation or data semantics. */
public final class ReferenceMotion {
    private final MotionService motion;
    private static final String INSTALLED = "reference.motion";

    public ReferenceMotion(MotionService motion) { this.motion = motion; }

    public void install(Node node) {
        if (node.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        node.sceneProperty().addListener((o, was, now) -> { if (now == null) settleNode(node); });
        if (node instanceof Button button) {
            var styles = button.getStyleClass();
            if (styles.contains("btn") && !styles.contains("command-row")) buttonHover(button);
            if (styles.contains("nav-item") || styles.contains("seg-btn") || styles.contains("ws-tab")) paint(button);
            if (styles.contains("nav-item") && button.getGraphic() != null) install(button.getGraphic());
        }
        if (node instanceof javafx.scene.shape.SVGPath path && path.getStyleClass().contains("svg-icon")) {
            boolean[] writing = {false};
            path.strokeProperty().addListener((o, from, to) -> {
                if (writing[0] || from == null || to == null) return;
                tween(path, "stroke", MotionTokens.CONTROL, f -> {
                    writing[0] = true; path.setStroke(blend(from, to, f)); writing[0] = false;
                }, () -> {});
            });
        }
        if (node instanceof TextInputControl input && !insidePalette(node)) {
            paint(input);
            DropShadow ring = new DropShadow();
            ring.setRadius(3); ring.setSpread(1); ring.setColor(Color.TRANSPARENT);
            input.focusedProperty().addListener((o, was, focused) -> {
                double alpha = ring.getColor().getOpacity();
                input.setEffect(ring);
                tween(input, "focus", MotionTokens.CONTROL, f -> ring.setColor(
                        Color.web("#7C96FF22").deriveColor(0, 1, 1, (alpha + ((focused ? 34.0 / 255 : 0) - alpha) * f) / (34.0 / 255))),
                        () -> { if (!focused) input.setEffect(null); });
            });
        }
        if (node.getStyleClass().contains("skeleton") && node instanceof Region region) shimmer(region);
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) install(scroll.getContent());
            scroll.contentProperty().addListener((o, was, now) -> { if (now != null) install(now); });
        } else if (node instanceof javafx.scene.control.TitledPane titled) {
            if (titled.getContent() != null) install(titled.getContent());
            titled.contentProperty().addListener((o, was, now) -> { if (now != null) install(now); });
        } else if (node instanceof javafx.scene.control.TabPane tabs) {
            java.util.function.Consumer<javafx.scene.control.Tab> tab = item -> {
                if (item.getContent() != null) install(item.getContent());
                item.contentProperty().addListener((o, was, now) -> { if (now != null) install(now); });
            };
            tabs.getTabs().forEach(tab);
            tabs.getTabs().addListener((ListChangeListener<javafx.scene.control.Tab>) change -> {
                while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(tab);
            });
        }
        if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(this::install);
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(this::install);
            });
        }
    }

    public void bind(Node root) {
        install(root);
        ChangeListener<MotionPreference> listener = (o, was, now) -> { if (now != MotionPreference.FULL) settleTree(root); };
        root.getProperties().put("reference.preference.listener", listener);
        motion.preference.addListener(new WeakChangeListener<>(listener));
    }

    private static boolean insidePalette(Node node) {
        for (Node parent = node; parent != null; parent = parent.getParent())
            if (parent.getStyleClass().contains("command-panel")) return true;
        return false;
    }

    public void tooltip(Button button, javafx.scene.layout.StackPane wrap, String text) {
        var label = new javafx.scene.control.Label(text);
        label.getStyleClass().add("rail-motion-tooltip");
        label.setMouseTransparent(true); label.setOpacity(0); label.setTranslateX(-4);
        var popup = new javafx.stage.Popup();
        popup.getContent().add(label);
        button.sceneProperty().addListener((o, was, now) -> { if (now == null) { settleTree(label); popup.hide(); } });
        button.hoverProperty().addListener((o, was, hovered) -> {
            if (hovered && button.getScene() != null && button.getScene().getWindow() != null) {
                var bounds = wrap.localToScreen(wrap.getBoundsInLocal());
                if (bounds != null) {
                    label.getStylesheets().setAll(button.getScene().getStylesheets());
                    popup.show(button, bounds.getMinX() + 62, bounds.getMinY());
                    label.applyCss(); label.autosize();
                    popup.setY(bounds.getMinY() + (bounds.getHeight() - popup.getHeight()) / 2);
                }
            }
            double opacity = label.getOpacity(), x = label.getTranslateX();
            tween(label, "tooltip", MotionTokens.TOOLTIP, f -> {
                label.setOpacity(opacity + ((hovered ? 1 : 0) - opacity) * f);
                label.setTranslateX(x + ((hovered ? 0 : -4) - x) * f);
            }, () -> { if (!hovered) popup.hide(); });
        });
        ChangeListener<MotionPreference> listener = (o, was, now) -> { if (now != MotionPreference.FULL) settleTree(label); };
        button.getProperties().put("reference.tooltip.listener", listener);
        motion.preference.addListener(new WeakChangeListener<>(listener));
    }

    private void buttonHover(Button button) {
        ColorAdjust filter = new ColorAdjust();
        button.hoverProperty().addListener((o, was, hovered) -> {
            double startY = button.getTranslateY(), startBrightness = filter.getBrightness();
            double endY = hovered && !button.isDisabled() ? -1 : 0;
            double endBrightness = endY == -1 ? .05 : 0;
            button.setEffect(filter);
            tween(button, "hover", MotionTokens.CONTROL, f -> {
                button.setTranslateY(startY + (endY - startY) * f);
                filter.setBrightness(startBrightness + (endBrightness - startBrightness) * f);
            }, () -> { if (endBrightness == 0) button.setEffect(null); });
        });
    }

    /** Each CSS property has its own channel; stylesheet changes are coalesced by the FX pulse. */
    private void paint(Region node) {
        boolean[] writing = {false};
        node.backgroundProperty().addListener((o, from, to) -> {
            if (writing[0] || from == null || to == null) return;
            tween(node, "background", MotionTokens.CONTROL, f -> {
                writing[0] = true;
                var fills = new java.util.ArrayList<BackgroundFill>();
                int count = Math.max(from.getFills().size(), to.getFills().size());
                for (int i = 0; i < count; i++) {
                    var a = from.getFills().isEmpty() ? null : from.getFills().get(Math.min(i, from.getFills().size() - 1));
                    var b = to.getFills().isEmpty() ? null : to.getFills().get(Math.min(i, to.getFills().size() - 1));
                    var geometry = b == null ? a : b;
                    fills.add(new BackgroundFill(blend(a == null ? Color.TRANSPARENT : a.getFill(),
                            b == null ? Color.TRANSPARENT : b.getFill(), f), geometry.getRadii(), geometry.getInsets()));
                }
                node.setBackground(f == 1 ? to : new Background(fills, to.getImages()));
                writing[0] = false;
            }, () -> {});
        });
        node.borderProperty().addListener((o, from, to) -> {
            if (writing[0] || from == null || to == null || from.getStrokes().size() != to.getStrokes().size()) return;
            tween(node, "border", MotionTokens.CONTROL, f -> {
                writing[0] = true;
                var strokes = new java.util.ArrayList<BorderStroke>();
                for (int i = 0; i < to.getStrokes().size(); i++) {
                    var a = from.getStrokes().get(i); var b = to.getStrokes().get(i);
                    strokes.add(new BorderStroke(blend(a.getTopStroke(), b.getTopStroke(), f),
                            blend(a.getRightStroke(), b.getRightStroke(), f), blend(a.getBottomStroke(), b.getBottomStroke(), f),
                            blend(a.getLeftStroke(), b.getLeftStroke(), f), b.getTopStyle(), b.getRightStyle(),
                            b.getBottomStyle(), b.getLeftStyle(), b.getRadii(), b.getWidths(), b.getInsets()));
                }
                node.setBorder(f == 1 ? to : new Border(strokes, to.getImages()));
                writing[0] = false;
            }, () -> {});
        });
        if (node instanceof Labeled labeled) labeled.textFillProperty().addListener((o, from, to) -> {
            if (writing[0]) return;
            tween(node, "text", MotionTokens.CONTROL, f -> {
                writing[0] = true; labeled.setTextFill(blend(from, to, f)); writing[0] = false;
            }, () -> {});
        });
    }

    private static Paint blend(Paint a, Paint b, double f) {
        return a instanceof Color from && b instanceof Color to ? from.interpolate(to, f) : b;
    }

    public Timeline tween(Node node, String channel, Duration duration, java.util.function.DoubleConsumer frame, Runnable finished) {
        String key = "reference." + channel;
        if (node.getProperties().remove(key) instanceof Animation previous) previous.stop();
        if (!motion.full()) { frame.accept(1); finished.run(); return null; }
        var fraction = new SimpleDoubleProperty();
        fraction.addListener((o, was, now) -> frame.accept(now.doubleValue()));
        Timeline timeline = new Timeline(new KeyFrame(duration, new KeyValue(fraction, 1, MotionTokens.CSS_EASE)));
        Runnable settle = () -> { timeline.stop(); frame.accept(1); finished.run(); node.getProperties().remove(key, timeline); };
        node.getProperties().put(key, timeline);
        node.getProperties().put(key + ".settle", settle);
        timeline.setOnFinished(e -> {
            node.getProperties().remove(key, timeline); node.getProperties().remove(key + ".settle", settle); finished.run();
        });
        frame.accept(0); timeline.play();
        return timeline;
    }

    public void enter(Node node, Duration duration, Duration delay, double opacity) {
        tweenEntry(node, duration, delay, opacity);
    }

    private void tweenEntry(Node node, Duration duration, Duration delay, double opacity) {
        if (!motion.full()) { node.setOpacity(opacity); node.setTranslateY(0); return; }
        Timeline t = tween(node, "entry", duration, f -> {
            node.setOpacity(opacity * f); node.setTranslateY(8 * (1 - f));
        }, () -> {});
        t.stop(); t.setDelay(delay);
        var scene = node.getScene();
        if (scene == null) { t.playFromStart(); return; }
        String pending = "reference.entry.pending.settle";
        if (node.getProperties().remove(pending) instanceof Runnable cancel) cancel.run();
        Runnable[] ready = {null};
        Runnable cancel = () -> scene.removePostLayoutPulseListener(ready[0]);
        ready[0] = () -> {
            cancel.run(); node.getProperties().remove(pending, cancel);
            if (node.getProperties().get("reference.entry") == t) t.playFromStart();
        };
        node.getProperties().put(pending, cancel);
        scene.addPostLayoutPulseListener(ready[0]);
    }

    public void enterCards(Node root) {
        if (root instanceof Parent parent && !(root instanceof javafx.scene.control.TableView<?>)) {
            List<Node> children = parent.getChildrenUnmodifiable();
            for (int i = 0; i < children.size(); i++) {
                Node child = children.get(i);
                if (child.getStyleClass().contains("card") || child.getStyleClass().contains("th-header")
                        || child.getStyleClass().contains("guard-strip") || child.getStyleClass().contains("th-strip")) {
                    enter(child, MotionTokens.CARD_ENTRY, Duration.millis(i >= 1 && i <= 3 ? i * 60 : 0), 1);
                } else enterCards(child);
            }
        }
    }

    private void settleNode(Node node) {
        var keys = new java.util.ArrayList<>(node.getProperties().keySet());
        for (Object key : keys) if (key.toString().startsWith("reference.") && key.toString().endsWith(".settle")) {
            if (node.getProperties().remove(key) instanceof Runnable settle) settle.run();
        }
    }

    public void settleTree(Node node) {
        settleNode(node);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::settleTree);
    }

    public void breathe(Node node, Duration period, javafx.animation.Interpolator easing) {
        visibleLoop(node, () -> new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 1)),
                new KeyFrame(period.divide(2), new KeyValue(node.opacityProperty(), .45, easing)),
                new KeyFrame(period, new KeyValue(node.opacityProperty(), 1, easing))), () -> node.setOpacity(1));
    }

    private void shimmer(Region node) {
        visibleLoop(node, () -> {
            Color dark = Color.web("#1C2131"), light = Color.web("#283049");
            var offset = new SimpleDoubleProperty();
            offset.addListener((o, was, now) -> {
                double x = now.doubleValue();
                node.setBackground(new Background(new BackgroundFill(new LinearGradient(x, 0, x + 2, 0, true,
                        CycleMethod.REPEAT, new Stop(.25, dark), new Stop(.5, light), new Stop(.75, dark)),
                        new javafx.scene.layout.CornerRadii(5), Insets.EMPTY)));
            });
            return new Timeline(new KeyFrame(MotionTokens.SHIMMER, new KeyValue(offset, 2, MotionTokens.CSS_EASE)));
        }, () -> node.setBackground(new Background(new BackgroundFill(Color.web("#1C2131"),
                new javafx.scene.layout.CornerRadii(5), Insets.EMPTY))));
    }

    private void visibleLoop(Node node, java.util.function.Supplier<Animation> factory, Runnable reset) {
        Runnable refresh = () -> {
            if (motion.full() && node.getScene() != null && !node.getProperties().containsKey("reference.loop")) {
                Animation loop = motion.loop(node, factory);
                node.getProperties().put("reference.loop", loop);
            } else if (!motion.full() || node.getScene() == null) {
                if (node.getProperties().remove("reference.loop") instanceof Animation loop) motion.removeLoop(loop);
                reset.run();
            }
            motion.refreshLoops();
        };
        ChangeListener<MotionPreference> preference = (o, was, now) -> refresh.run();
        var weak = new WeakChangeListener<>(preference);
        node.getProperties().put("reference.loop.listener", preference);
        node.sceneProperty().addListener((o, was, now) -> {
            if (was != null) motion.preference.removeListener(weak);
            if (now != null) motion.preference.addListener(weak);
            refresh.run();
        });
        node.visibleProperty().addListener((o, was, now) -> refresh.run());
        if (node.getScene() != null) motion.preference.addListener(weak);
        refresh.run();
    }
}
