package panel.ui.motion;

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.motion.icon.AnimationRepository;
import panel.ui.Ui;

/** Native profile popup: immediate lifecycle, ESC/outside dismissal and arrow navigation. */
public class UserMenu extends Button {
    public record Item(String label, String icon, Runnable action) {
        public static final Item SEPARATOR = new Item(null, null, null);
    }

    private final MotionService motion;
    private final AnimationRepository icons;
    private final Popup popup = new Popup();
    private final VBox content = new VBox(2);

    public UserMenu(MotionService motion, AnimationRepository icons) {
        this.motion = motion;
        this.icons = icons;
        getStyleClass().add("user-menu");
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        content.getStyleClass().add("menu-pop");
        content.setPadding(new Insets(6));
        popup.getContent().add(content);
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeAnimated();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                move(e.getCode() == KeyCode.DOWN ? 1 : -1);
                e.consume();
            }
        });
        setOnAction(e -> {
            if (popup.isShowing()) {
                closeAnimated();
            } else {
                open();
            }
        });
    }

    private List<Item> items = List.of();

    public void setUser(String name, String role, List<Item> items) {
        this.items = items;
        setAccessibleText(name + " · " + role + " · account menu");
        content.getProperties().put("account.identity", name + " · " + role);
    }

    private void open() {
        content.getChildren().setAll(Ui.label((String) content.getProperties().get("account.identity"), "muted"));
        for (Item it : items) {
            if (it.label() == null) {
                content.getChildren().add(new Separator());
                continue;
            }
            Button b = new Button(it.label());
            b.setGraphic(icons.staticIcon(it.icon(), 16, "text").node());
            b.setGraphicTextGap(10);
            b.getStyleClass().add("menu-pop-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setOnAction(e -> {
                closeAnimated();
                it.action().run();
            });
            content.getChildren().add(b);
        }
        var bounds = localToScreen(getBoundsInLocal());
        popup.show(this, bounds.getMaxX() - 220, bounds.getMaxY() + 6);
        content.applyCss();
        content.autosize();
        double right = getScene().getWindow().getX() + getScene().getWindow().getWidth();
        popup.setX(Math.min(bounds.getMaxX(), right - 8) - popup.getWidth());
        motion.reset(content);
        content.getChildren().stream().filter(n -> n instanceof Button).findFirst().ifPresent(javafx.scene.Node::requestFocus);
    }

    private void closeAnimated() {
        if (!popup.isShowing()) {
            return;
        }
        popup.hide();
        motion.reset(content);
    }

    private void move(int dir) {
        var buttons = content.getChildren().filtered(n -> n instanceof Button);
        int i = buttons.indexOf(content.getScene().getFocusOwner());
        int next = Math.floorMod(i + dir, buttons.size());
        buttons.get(next).requestFocus();
    }

    public boolean menuShowing() {
        return popup.isShowing();
    }

    public HBox wrap() {
        return new HBox(this);
    }
}
