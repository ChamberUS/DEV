package byx.demo;

import static org.junit.jupiter.api.Assertions.*;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WindowPolicyTest {
    /** Modeled logical work areas; this does not change or certify a physical display scaling mode. */
    @ParameterizedTest(name="modeled {0}x{1}, scaling {2}")
    @CsvSource({"1366,768,1", "1366,768,1.25", "1366,768,1.5",
                "1920,1080,1", "1920,1080,1.25", "1920,1080,1.5",
                "2560,1440,1", "2560,1440,1.25", "2560,1440,1.5"})
    void modeledResolutionsAndScalingFitLogicalVisualArea(double width, double height, double scale) {
        Rectangle2D visual = new Rectangle2D(0, 0, width / scale, height / scale - 40);
        var g = WindowPolicy.plan(visual);
        assertTrue(g.x() > visual.getMinX());
        assertTrue(g.y() > visual.getMinY());
        assertTrue(g.x() + g.width() < visual.getMaxX());
        assertTrue(g.y() + g.height() < visual.getMaxY());
        assertTrue(g.width() <= 1200 && g.height() <= 760);
        assertTrue(g.minWidth() < g.width() && g.minHeight() < g.height(), "can shrink on small logical displays");
        assertEquals(visual.getMinX() + visual.getWidth() / 2, g.x() + g.width() / 2, .001);
        assertEquals(visual.getMinY() + visual.getHeight() / 2, g.y() + g.height() / 2, .001);
    }

    @Test void ordinaryWorkAreaUsesExpectedInitialWindowAndMinimum() {
        var g = WindowPolicy.plan(new Rectangle2D(0, 0, 1920, 1040));
        assertEquals(1200, g.width()); assertEquals(760, g.height());
        assertEquals(900, g.minWidth()); assertEquals(560, g.minHeight());
    }
    @Test void negativeMonitorOriginIsPreserved() {
        Rectangle2D visual = new Rectangle2D(-1920, -120, 1920, 1040);
        var g = WindowPolicy.plan(visual);
        assertTrue(g.x() >= visual.getMinX() && g.x() + g.width() <= visual.getMaxX());
        assertEquals(-120 + 1040 / 2d, g.y() + g.height() / 2, .001);
    }
    @Test void smallLogicalDisplayDoesNotForceOldMinimum() {
        var g = WindowPolicy.plan(new Rectangle2D(0, 0, 800, 450));
        assertTrue(g.width() < 800 && g.height() < 450);
        assertTrue(g.minWidth() < 800 && g.minHeight() < 450);
    }
    @Test void invalidVisualAreaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WindowPolicy.plan(null));
        assertThrows(IllegalArgumentException.class, () -> WindowPolicy.plan(new Rectangle2D(0, 0, 0, 100)));
        assertThrows(IllegalArgumentException.class, () -> WindowPolicy.plan(new Rectangle2D(0, 0, Double.POSITIVE_INFINITY, 100)));
    }
}