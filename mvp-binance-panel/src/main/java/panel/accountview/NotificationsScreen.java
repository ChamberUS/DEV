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
 * Existing Account destination: shares session notification state with the header popover.
 * The source-less compatibility constructor preserves its honest unavailable state.
 */
public final class NotificationsScreen implements View {
    private final ScrollPane scroll;
    private final ByxRegion region;
    private panel.notifications.NotificationCenterView centerView;

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


    /** Existing Account destination shares the same session history as the header popover. */
    public NotificationsScreen(panel.notifications.NotificationCenter center,
            java.util.function.Consumer<panel.notifications.NotificationEvent.Destination> navigate) {
        region = null;
        centerView = new panel.notifications.NotificationCenterView(center, navigate, java.time.Clock.systemUTC());
        VBox page = Kit.page(14); page.getChildren().add(centerView); scroll = Kit.scroll(page);
    }

    RegionState state() {
        return region == null ? RegionState.READY : region.state();
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    public void dispose() {
        if (region != null) region.dispose();
        if (centerView != null) centerView.close();
    }
}
