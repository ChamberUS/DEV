package panel.shell;

import java.util.EnumSet;
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
 * Painel de notificações V2 (popover 420). Não há serviço de notificações nesta build (BACKEND_REQUIRED):
 * o painel mostra UNAVAILABLE com o motivo e não inventa itens nem contagem. Filtros e "Mark all as read"
 * ficam desabilitados; a central de notificações chega no passo 10. Painel e menu do usuário nunca coexistem.
 */
public final class NotificationPanel {
    public static final String NO_SERVICE = "No notification service is connected in this build. "
            + "Notifications appear here once the backend provides them.";

    private final ByxOverlayHost overlay;
    private final Button opener;
    private final MotionService motion;
    private VBox panel;
    private ByxRegion region;

    public NotificationPanel(ByxOverlayHost overlay, Button opener, MotionService motion) {
        this.overlay = overlay;
        this.opener = opener;
        this.motion = motion;
        opener.setOnAction(e -> toggle());
    }

    public boolean isOpen() {
        return panel != null && overlay.isPopoverOpen(panel);
    }

    public RegionState state() {
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
            f.getStyleClass().addAll("byx-btn", "secondary", "small");
            f.setDisable(true);
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
}
