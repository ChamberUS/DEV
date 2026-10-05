package panel.researchview;

import java.time.Clock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.security.AccessDeniedException;
import panel.security.AdminGate;
import panel.service.CaptureMonitorService;
import panel.ui.View;

/**
 * Tela Capture V2. Mantém o ciclo de vida da tela anterior: o monitor (leitura somente) e o timer de apresentação
 * existem só enquanto a tela está visível E o administrador está autorizado; escondida, sem sessão ou descartada,
 * ambos param e o painel é limpo. Nada aqui inicia, para ou recupera uma captura.
 */
public final class CaptureScreen implements View {
    private final CapturePanel panel;
    private final ScrollPane scroll;
    private final CaptureMonitorService monitor;
    private final AdminGate gate;
    private final BooleanSupplier hasSession;
    private final Supplier<Snapshot> snapshot;
    private boolean observing;

    public CaptureScreen(MotionService motion, Clock clock, CaptureMonitorService monitor, AdminGate gate,
            BooleanSupplier hasAdminSession, Supplier<Snapshot> snapshot) {
        this.monitor = monitor;
        this.gate = gate;
        this.hasSession = hasAdminSession;
        this.snapshot = snapshot;
        this.panel = new CapturePanel(motion, clock);
        this.scroll = V2Scroll.wrap(panel);
        scroll.visibleProperty().addListener((o, a, b) -> visibility());
        scroll.sceneProperty().addListener((o, a, b) -> visibility());
        ContextMenu menu = new ContextMenu();
        MenuItem refresh = new MenuItem("Refresh read-only monitor");
        refresh.setOnAction(e -> monitor.refresh());
        menu.getItems().add(refresh);
        panel.setOnContextMenuRequested(e -> menu.show(panel, e.getScreenX(), e.getScreenY()));
        panel.setFocusTraversable(true);
        panel.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) {
                monitor.refresh();
            }
        });
    }

    CapturePanel panel() {
        return panel;
    }

    boolean observing() {
        return observing;
    }

    @Override
    public Node node() {
        return scroll;
    }

    private void visibility() {
        boolean visible = scroll.getScene() != null && scroll.isVisible();
        if (visible && !observing) {
            try {
                gate.requireAdmin();
                monitor.start(panel::show);
                panel.start(gate::requireAdmin);
                observing = true;
            } catch (AccessDeniedException denied) {
                panel.clear();
            }
        } else if (!visible && observing) {
            release();
        }
    }

    private void release() {
        monitor.stop();
        panel.stop();
        panel.clear();
        observing = false;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        if (!hasSession.getAsBoolean()) {
            release();
            return;
        }
        panel.setCurrentSession(snapshot.get().capture.currentSession());
        visibility();
    }

    /** Fim da View (sessão encerrada): zero timers, zero leituras. */
    public void dispose() {
        release();
    }
}
