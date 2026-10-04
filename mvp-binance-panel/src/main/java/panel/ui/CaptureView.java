package panel.ui;

import java.time.Clock;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.security.AccessDeniedException;

public class CaptureView extends PageView {
    private final CaptureMonitorCard monitor;
    private boolean observing;
    public CaptureView(AppContext ctx) {
        super(ctx);
        monitor = new CaptureMonitorCard(ctx.motion, Clock.systemUTC());
        node().visibleProperty().addListener((o,a,b) -> visibility());
        node().sceneProperty().addListener((o,a,b) -> visibility());
    }
    private void visibility() {
        boolean visible = node().getScene() != null && node().isVisible();
        if (visible && !observing) {
            try {
                ctx.adminAccess.requireAdmin();
                ctx.captureMonitor.start(monitor::show);
                monitor.start(ctx.adminAccess::requireAdmin); observing = true;
            } catch (AccessDeniedException denied) { monitor.clear(); }
        } else if (!visible && observing) {
            ctx.captureMonitor.stop(); monitor.stop(); monitor.clear(); observing = false;
        }
    }
    @Override protected int stateKey(Snapshot s) { return 1; }
    @Override public void onSnapshot(Snapshot s) {
        if (!ctx.adminAccess.hasValidAdminSession()) {
            ctx.captureMonitor.stop(); monitor.stop(); monitor.clear(); observing = false; return;
        }
        monitor.setCurrentSession(s.capture.currentSession());
        super.onSnapshot(s); visibility();
    }
    @Override protected void build(Snapshot s, VBox page) {
        ctx.adminAccess.requireAdmin();
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        var item = new javafx.scene.control.MenuItem("Refresh read-only monitor");
        item.setOnAction(e -> ctx.captureMonitor.refresh()); menu.getItems().add(item);
        monitor.setOnContextMenuRequested(e -> menu.show(monitor, e.getScreenX(), e.getScreenY()));
        monitor.setFocusTraversable(true);
        monitor.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.F5) ctx.captureMonitor.refresh(); });
        page.getChildren().add(monitor);
        VBox.setVgrow(monitor, javafx.scene.layout.Priority.ALWAYS);
    }
}
