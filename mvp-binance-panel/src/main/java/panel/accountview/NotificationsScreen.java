package panel.accountview;

import java.util.EnumSet;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxRegion;
import panel.design.RegionState;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.shell.NotificationPanel;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Notifications V2. Sem serviço de notificações o estado é UNAVAILABLE (e não EMPTY): INTENTIONALLY PRESERVED da decisão do
 * Passo 5. Nenhuma notificação demonstrativa entra no produto; os filtros existem desabilitados.
 */
public final class NotificationsScreen implements View {
    private final ScrollPane scroll;
    private final ByxRegion region;

    public NotificationsScreen(MotionService motion) {
        FlowPane chips = new FlowPane(6, 6);
        for (String c : List.of("All", "Unread", "System", "Research", "Capture", "BYX Network", "Wallet", "Security", "Updates")) {
            Button b = new Button(c);
            b.getStyleClass().add("byx-filter-chip");
            b.setDisable(true);
            chips.getChildren().add(b);
        }
        region = new ByxRegion("Notifications", EnumSet.of(RegionState.UNAVAILABLE), motion);
        region.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("Notifications unavailable", NotificationPanel.NO_SERVICE));
        region.setMinHeight(260);
        VBox page = Kit.page(14);
        VBox list = Kit.panel(null, Kit.titled("Notification center", ByxBadge.availability(ByxBadge.Availability.UNAVAILABLE)), chips, region);
        list.setId("notifications-list");
        page.getChildren().addAll(Kit.header("Notifications", "Alerts from the system, research and BYX."), list);
        scroll = Kit.scroll(page);
    }

    RegionState state() {
        return region.state();
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    public void dispose() {
        region.dispose();
    }
}
