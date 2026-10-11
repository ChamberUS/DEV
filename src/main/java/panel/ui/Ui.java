package panel.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.model.StageState;
import panel.util.Fmt;

/** Fábrica de componentes visuais compartilhados. */
public final class Ui {
    private static panel.motion.MotionService motion;

    private Ui() {
    }

    public static void init(panel.motion.MotionService m) {
        motion = m;
    }

    /** Cards have entry motion, but no hover transform in the reference. */
    public static <T extends Node> T hoverLift(T n) {
        return n;
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        l.setWrapText(true);
        return l;
    }

    public static Label badge(String text, String tone) {
        return label(text, "badge", "badge-" + tone);
    }

    public static Label stateBadge(StageState s) {
        return badge(s.icon + " " + s.name(), s.tone);
    }

    public static Button button(String text, String variant) {
        Button b = new Button(text);
        b.getStyleClass().addAll("btn", "btn-" + variant);
        return b;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static VBox card(String title, Node... content) {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        box.getChildren().add(label(title.toUpperCase(), "card-title"));
        box.getChildren().addAll(content);
        return box;
    }

    public static HBox kv(String key, String value) {
        Label k = label(key, "muted");
        k.setMinWidth(130);
        Label v = label(Fmt.text(value), "kv-value");
        v.setWrapText(true);
        HBox row = new HBox(8, k, v);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    public static HBox kvNode(String key, Node value) {
        Label k = label(key, "muted");
        k.setMinWidth(130);
        HBox row = new HBox(8, k, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static VBox metric(String key, String value, String tone) {
        VBox v = new VBox(2, label(key, "muted"), label(Fmt.text(value), "metric", "metric-" + tone));
        return v;
    }

    public static Node emptyState(String text) {
        Label l = label(text, "muted");
        l.setWrapText(true);
        VBox b = new VBox(l);
        b.getStyleClass().add("empty");
        b.setAlignment(Pos.CENTER);
        b.setMinHeight(90);
        return b;
    }

    public static HBox pageHeader(String title, String subtitle, Node... right) {
        VBox t = new VBox(2, label(title, "h1"), label(subtitle, "muted"));
        HBox h = new HBox(12, t, spacer());
        h.getChildren().addAll(right);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("page-scroll");
        return sp;
    }

    public static VBox page(Node... children) {
        VBox p = new VBox(14, children);
        p.setPadding(new Insets(16, 20, 16, 20));
        p.getStyleClass().add("page");
        return p;
    }

    public static Label testEnvironment() {
        Label label = label("LOCALNET / TEST ASSETS / NO FINANCIAL VALUE", "environment-test");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setWrapText(true);
        return label;
    }

    public static javafx.scene.layout.GridPane columns(Node... nodes) {
        var grid = new javafx.scene.layout.GridPane();
        grid.getStyleClass().add("byx-columns");
        for (int i = 0; i < nodes.length; i++) {
            var column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(100.0 / nodes.length);
            column.setHgrow(Priority.ALWAYS);
            column.setMinWidth(0);
            column.setFillWidth(true);
            grid.getColumnConstraints().add(column);
            grid.add(nodes[i], i, 0);
            javafx.scene.layout.GridPane.setHgrow(nodes[i], Priority.ALWAYS);
            if (nodes[i] instanceof Region region) { region.setMinWidth(0); region.setMaxWidth(Double.MAX_VALUE); }
        }
        return grid;
    }

    public static String toneOf(String s) {
        return s == null ? "muted" : s;
    }
}
