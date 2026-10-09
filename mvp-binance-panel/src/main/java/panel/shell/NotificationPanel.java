package panel.shell;

import java.util.EnumSet;
import panel.i18n.Strings;
import panel.i18n.LocaleView;
import panel.i18n.DisplayFormats;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxOverlayHost;
import panel.design.ByxRegion;
import panel.design.RegionState;
import panel.motion.MotionService;

/**
 * Native session notification popover. The legacy constructor without an event center remains
 * honestly UNAVAILABLE; production uses the bounded center through the existing header bell.
 * Uses the existing single-popover layer and focus restoration contract.
 */
public final class NotificationPanel {
    public static final String NO_SERVICE = "No notification service is connected in this build. "
            + "Notifications appear here once the backend provides them.";

    private final ByxOverlayHost overlay;
    private final Button opener;
    private final MotionService motion;
    private VBox panel;
    private ByxRegion region;
    private final panel.notifications.NotificationCenter center;
    private final java.util.function.Consumer<panel.notifications.NotificationEvent.Destination> navigate;
    private final java.util.function.IntConsumer unreadCount;
    private panel.notifications.NotificationCenterView centerView;
    private final javafx.beans.InvalidationListener bellUpdate = o -> refreshBell();
    private boolean disposed;

    public NotificationPanel(ByxOverlayHost overlay, Button opener, MotionService motion) {
        this(overlay, opener, motion, null, d -> {}, n -> {});
    }

    public NotificationPanel(ByxOverlayHost overlay, Button opener, MotionService motion,
            panel.notifications.NotificationCenter center,
            java.util.function.Consumer<panel.notifications.NotificationEvent.Destination> navigate,
            java.util.function.IntConsumer unreadCount) {
        this.center = center; this.navigate = navigate; this.unreadCount = unreadCount;
        this.overlay = overlay;
        this.opener = opener;
        this.motion = motion;
        opener.setOnAction(e -> toggle());
        if (center != null) {
            LocaleView.literal(opener);
            center.unreadProperty().addListener(bellUpdate);
            Strings.languageProperty().addListener(bellUpdate);
            refreshBell();
        }
    }

    public void dispose() {
        if (disposed) return; disposed = true;
        if (center != null) {
            center.unreadProperty().removeListener(bellUpdate);
            Strings.languageProperty().removeListener(bellUpdate);
        }
        if (panel != null) overlay.closePopover(panel);
        opener.setOnAction(null);
        region = null;
    }

    public boolean isOpen() {
        return panel != null && overlay.isPopoverOpen(panel);
    }

    public RegionState state() {
        if (center != null) return !center.active() ? RegionState.UNAVAILABLE : center.events().isEmpty() ? RegionState.EMPTY : RegionState.READY;
        return region == null ? null : region.state();
    }

    public void toggle() {
        if (isOpen()) {
            overlay.closePopover(panel);
        } else {
            open();
        }
    }

    public void open() {
        if (disposed) return;
        if (center != null) { openLocal(); return; }
        if (isOpen()) return; // do not rebuild a live surface or dispose its replacement through an old callback
        Label title = new Label("Notifications");
        title.getStyleClass().add("byx-section-title-sm");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button markAll = new Button("Mark all as read");
        markAll.getStyleClass().addAll("byx-btn", "ghost", "small");
        markAll.setDisable(true);
        HBox head = new HBox(8, title, spacer, markAll);
        head.setAlignment(Pos.CENTER_LEFT);
        Button all = new Button("All");
        Button unread = new Button("Unread");
        for (Button f : new Button[] {all, unread}) {
            f.getStyleClass().add("byx-filter-chip");
            f.setDisable(true); // sem serviço não há o que filtrar
        }
        HBox filters = new HBox(6, all, unread);
        // matriz do handoff lista READY LOADING EMPTY ERROR; sem serviço, EMPTY afirmaria "carregou e não há nada"
        region = new ByxRegion("Notifications", EnumSet.of(RegionState.UNAVAILABLE), motion);
        region.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("Notifications unavailable", NO_SERVICE));
        region.setMinHeight(180);
        Label center = new Label("Notification center");
        center.getStyleClass().addAll("byx-body", "byx-secondary");
        Region s2 = new Region();
        HBox.setHgrow(s2, Priority.ALWAYS);
        HBox foot = new HBox(8, center, s2, ByxBadge.availability(ByxBadge.Availability.COMING_SOON));
        foot.setAlignment(Pos.CENTER_LEFT);
        panel = new VBox(12, head, filters, region, foot);
        panel.getStyleClass().addAll("byx-popover", "byx-notification-panel");
        panel.setPrefWidth(420);
        panel.setMaxWidth(420);
        panel.setAccessibleText("Notifications");
        panel.getProperties().put("byx.popover.owner", opener);
        Bounds b = opener.localToScene(opener.getLayoutBounds());
        var p = overlay.sceneToLocal(b.getMaxX(), b.getMaxY() + 8);
        VBox opened = panel;
        overlay.openPopover(panel, Math.max(8, p.getX() - 420), p.getY(), () -> {
            UserMenu.returnFocusIfInside(opened, opener);
            region.dispose();
            panel = null;
            opener.getStyleClass().remove("expanded");
        });
        opener.getStyleClass().add("expanded");
    }
    private void refreshBell() {
        if (disposed || center == null) return;
        int count = center.unreadProperty().get(); unreadCount.accept(count);
        opener.setAccessibleText(Strings.fmt("notification.bell", "count", DisplayFormats.number(count, 0)));
        opener.notifyAccessibleAttributeChanged(javafx.scene.AccessibleAttribute.TEXT);
    }

    private void openLocal() {
        if (isOpen()) return;
        centerView = new panel.notifications.NotificationCenterView(center, destination -> {
            overlay.closePopovers(); navigate.accept(destination);
        }, java.time.Clock.systemUTC());
        Button close = new Button("×"); close.getStyleClass().addAll("byx-btn", "ghost", "small");
        close.accessibleTextProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> Strings.get("notification.close"), Strings.languageProperty()));
        close.setOnAction(e -> overlay.closePopovers());
        HBox closeRow = new HBox(close); closeRow.setAlignment(Pos.CENTER_RIGHT);
        panel = new VBox(4, closeRow, centerView); panel.getStyleClass().addAll("byx-popover", "byx-notification-panel");
        panel.setPrefWidth(420); panel.setMaxWidth(420);
        panel.getProperties().put("byx.popover.owner", opener);
        VBox opened = panel; var view = centerView;
        Bounds b = opener.localToScene(opener.getLayoutBounds()); var point = overlay.sceneToLocal(b.getMaxX(), b.getMaxY() + 8);
        overlay.openPopover(opened, Math.max(8, point.getX() - 420), point.getY(), () -> {
            UserMenu.returnFocusIfInside(opened, opener); view.close(); close.accessibleTextProperty().unbind(); close.setOnAction(null);
            if (panel == opened) { panel = null; centerView = null; }
            opener.getStyleClass().remove("expanded");
        });
        opener.getStyleClass().add("expanded"); view.focusList();
    }

}
