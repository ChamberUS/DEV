package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.motion.ViewTransitionService;
import panel.ui.CommandPalette;

class ReferenceMotionTest {
    static class HoverButton extends Button { void hover(boolean value) { setHover(value); } }
    static class FocusInput extends TextField { void focus(boolean value) { setFocused(value); } }
    static Timeline animation(Region node, String name) {
        Timeline t = (Timeline) node.getProperties().get("reference." + name);
        assertNotNull(t); t.pause(); return t;
    }
    static void finish(Timeline t) { t.jumpTo(t.getTotalDuration()); t.getOnFinished().handle(null); t.stop(); }

    @Test void referenceTokensAndCurvesMatchExecutableCss() {
        assertEquals(200, MotionTokens.CONTROL.toMillis());
        assertEquals(120, MotionTokens.TOOLTIP.toMillis());
        assertEquals(500, MotionTokens.CARD_ENTRY.toMillis());
        assertEquals(1000, MotionTokens.LOGIN_ENTRY.toMillis());
        assertEquals(2600, MotionTokens.LIVE.toMillis());
        assertEquals(2400, MotionTokens.PIPELINE.toMillis());
        assertEquals(2200, MotionTokens.SHIMMER.toMillis());
        assertEquals(.8024, MotionTokens.CSS_EASE.interpolate(0.0, 1.0, .5), .001);
        assertEquals(.5, MotionTokens.CSS_EASE_IN_OUT.interpolate(0.0, 1.0, .5), .001);
    }

    @Test void entryHasInitialIntermediateFinalAndStagger() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Region r = new Region();
            m.reference.enter(r, MotionTokens.LOGIN_ENTRY, Duration.millis(240), .4);
            Timeline t = animation(r, "entry");
            assertEquals(240, t.getDelay().toMillis()); assertEquals(1000, t.getCycleDuration().toMillis());
            assertEquals(0, r.getOpacity()); assertEquals(8, r.getTranslateY());
            t.jumpTo(Duration.millis(500));
            assertEquals(.4 * .8024, r.getOpacity(), .001); assertEquals(8 * (1 - .8024), r.getTranslateY(), .01);
            finish(t); assertEquals(.4, r.getOpacity()); assertEquals(0, r.getTranslateY());
            assertFalse(r.getProperties().containsKey("reference.entry.settle"));
        });
    }

    @Test void hoverReversesFromCurrentFrameWithoutStacking() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); HoverButton b = new HoverButton(); b.getStyleClass().add("btn");
            m.reference.bind(b); b.hover(true); Timeline first = animation(b, "hover");
            first.jumpTo(Duration.millis(80)); double y = b.getTranslateY(); assertTrue(y < 0 && y > -1);
            b.hover(false); Timeline second = animation(b, "hover");
            assertEquals(javafx.animation.Animation.Status.STOPPED, first.getStatus()); assertEquals(y, b.getTranslateY());
            second.jumpTo(Duration.millis(100)); assertTrue(b.getTranslateY() > y);
            finish(second); assertEquals(0, b.getTranslateY()); assertNull(b.getEffect());
            for (int i = 0; i < 100; i++) b.hover(i % 2 == 0);
            assertEquals(1, b.getProperties().values().stream().filter(v -> v instanceof Timeline).count());
            m.reference.settleTree(b);
            assertEquals(0, b.getProperties().values().stream().filter(v -> v instanceof Timeline).count());
        });
    }

    @Test void cssPaintInterpolatesAndCanReverse() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Button b = new Button(); b.getStyleClass().add("nav-item");
            Background dark = new Background(new BackgroundFill(Color.BLACK, CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY));
            Background light = new Background(new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY));
            b.setBackground(dark); m.reference.bind(b); b.setBackground(light);
            Timeline first = animation(b, "background"); first.jumpTo(Duration.millis(100));
            Color mid = (Color)b.getBackground().getFills().getFirst().getFill(); assertTrue(mid.getRed() > 0 && mid.getRed() < 1);
            b.setBackground(dark); Timeline reverse = animation(b, "background");
            assertEquals(mid, b.getBackground().getFills().getFirst().getFill()); finish(reverse);
            assertEquals(Color.BLACK, b.getBackground().getFills().getFirst().getFill());
        });
    }

    @Test void stylesheetHoverAndSelectionActuallyInterpolate() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); HoverButton b = new HoverButton(); b.getStyleClass().add("nav-item");
            Pane root = new Pane(b); Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/panel/panel.css").toExternalForm());
            scene.getStylesheets().add(getClass().getResource("/panel/byx.css").toExternalForm());
            root.applyCss(); m.reference.bind(root);
            Color normal = (Color)b.getBackground().getFills().getFirst().getFill();
            b.hover(true); root.applyCss(); Timeline hover = animation(b, "background");
            assertEquals(normal, b.getBackground().getFills().getFirst().getFill());
            hover.jumpTo(Duration.millis(100)); Color mid = (Color)b.getBackground().getFills().getFirst().getFill();
            assertNotEquals(normal, mid); finish(hover);
            b.getStyleClass().add("selected"); root.applyCss(); m.reference.settleTree(root);
            assertEquals(Color.web("#7C96FF"), b.getTextFill());
        });
    }

    @Test void detachedEntrySettlesAndLoadingSkeletonDoesNotLeak() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Region card = card(); Region skeleton = panel.ui.motion.Skeleton.bar(100,10);
            Pane root = new Pane(card, skeleton); new Scene(root); m.reference.bind(root);
            m.reference.enter(card, MotionTokens.CARD_ENTRY, Duration.ZERO, 1);
            Timeline cancelled = animation(card, "entry");
            assertEquals(1, m.loopCount()); root.getChildren().clear();
            cancelled.jumpTo(Duration.millis(200));
            assertEquals(0, m.loopCount()); assertEquals(1, card.getOpacity()); assertEquals(0, card.getTranslateY());
            assertNull(card.getProperties().get("reference.entry"));
            assertNull(card.getProperties().get("reference.entry.pending.settle"));
        });
    }

    @Test void focusBlurReversesRingAndKeepsInputValue() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); FocusInput input = new FocusInput(); input.setText("unchanged");
            Pane root = new Pane(input); new Scene(root);
            root.getStylesheets().add(getClass().getResource("/panel/byx.css").toExternalForm());
            root.applyCss(); m.reference.bind(input);
            input.focus(true); root.applyCss(); Timeline focus = animation(input, "border");
            focus.jumpTo(Duration.millis(100));
            double alpha = ((Color)input.getBorder().getStrokes().get(1).getTopStroke()).getOpacity();
            assertTrue(alpha > 0 && alpha < 34.0 / 255);
            input.focus(false); root.applyCss(); Timeline blur = animation(input, "border");
            assertEquals(alpha, ((Color)input.getBorder().getStrokes().get(1).getTopStroke()).getOpacity(), 1e-7);
            finish(blur); assertNull(input.getEffect()); assertEquals("unchanged", input.getText());
            assertEquals(3, input.getBorder().getStrokes().get(1).getWidths().getTop());
        });
    }

    @Test void rapidWorkspaceChangesLeaveOnlyTargetWithoutMovingWholePage() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); ViewTransitionService navigation = new ViewTransitionService(m);
            Pane a = new Pane(card()), b = new Pane(card()), c = new Pane(card());
            List<javafx.scene.Node> pages = List.of(a, b, c);
            navigation.show(pages, a, false); navigation.show(pages, b, true); navigation.show(pages, c, true);
            assertFalse(a.isVisible()); assertFalse(b.isVisible()); assertTrue(c.isVisible());
            assertEquals(0, c.getTranslateY()); assertEquals(1, c.getOpacity());
            assertEquals(1, a.getChildren().getFirst().getOpacity()); assertEquals(1, b.getChildren().getFirst().getOpacity());
            assertNull(a.getChildren().getFirst().getProperties().get("reference.entry"));
            Timeline entry = animation((Region)c.getChildren().getFirst(), "entry");
            navigation.show(pages, c, true); assertSame(entry, c.getChildren().getFirst().getProperties().get("reference.entry"));
            m.reference.settleTree(c);
        });
    }
    private static Region card() { Region r = new Region(); r.getStyleClass().add("card"); return r; }

    @Test void reducedAndOffSettleExistingMotionAndSkipFutureEntries() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Region r = card(); m.reference.bind(r);
            m.reference.enter(r, MotionTokens.CARD_ENTRY, Duration.ZERO, 1);
            m.preference.set(MotionPreference.REDUCED); assertEquals(1, r.getOpacity()); assertEquals(0, r.getTranslateY());
            assertNull(r.getProperties().get("reference.entry"));
            m.reference.enter(r, MotionTokens.CARD_ENTRY, Duration.ZERO, 1); assertEquals(1, r.getOpacity());
            m.preference.set(MotionPreference.OFF); assertEquals(0, m.loopCount());
        });
    }

    @Test void loopsPauseDetachAndResumeWithoutLeakingRegistrations() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Region r = new Region(); Pane parent = new Pane(r); new Scene(parent);
            m.reference.breathe(r, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
            assertEquals(1, m.runningLoops());
            parent.setVisible(false); m.refreshLoops(); assertEquals(0, m.runningLoops());
            parent.setVisible(true); m.refreshLoops(); assertEquals(1, m.runningLoops());
            m.setActive(false); assertEquals(0, m.runningLoops()); m.setActive(true);
            for (int i = 0; i < 20; i++) { m.preference.set(MotionPreference.REDUCED); m.preference.set(MotionPreference.FULL); }
            assertEquals(1, m.loopCount());
            parent.getChildren().clear(); assertEquals(0, m.loopCount()); assertEquals(1, r.getOpacity());
            parent.getChildren().add(r); assertEquals(1, m.loopCount());
            m.stopLoops();
        });
    }

    @Test void paletteImmediateOpenCloseOpenKeepsOneOverlayAndNoAnimations() throws Exception {
        FxSupport.fx(() -> {
            MotionService m = new MotionService(); Pane baseline = new Pane(); var root = new javafx.scene.layout.StackPane(baseline);
            new Scene(root); m.reference.bind(root); CommandPalette palette = new CommandPalette(id -> {});
            for (int i = 0; i < 20; i++) { palette.open(root, false, false); palette.close(); }
            palette.open(root, false, false); assertEquals(2, root.getChildren().size());
            assertEquals(1, root.getChildren().getLast().getOpacity()); assertEquals(0, m.loopCount());
            palette.close(); assertEquals(List.of(baseline), root.getChildren());
        });
    }
}
