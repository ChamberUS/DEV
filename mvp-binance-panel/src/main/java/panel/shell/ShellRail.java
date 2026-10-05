package panel.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import panel.design.ByxIcon;
import panel.motion.MotionService;

/**
 * Rail V2 (68 px; itens 60x58, ícone 22, rótulo 11; ativo: bg3 + barra de 3 px no acento). Só lê a rota e
 * pede navegação ao roteador. O indicador desliza (railIndicator) só em FULL e só dentro do mesmo contexto;
 * uma nova seleção retoma do ponto atual (interrompível). Settings fica no pé de todo rail.
 */
public final class ShellRail extends VBox {
    static final double ITEM_W = 60;
    static final double ITEM_H = 58;
    static final double GAP = 4;
    static final double BAR_INSET = 12;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final ShellRouter router;
    private final MotionService motion;
    private final String shortcutPrefix;
    private final VBox items = new VBox(GAP);
    private final Region indicator = new Region();
    private final StackPane settingsWrap;
    private final Button settings;
    private final List<Button> buttons = new ArrayList<>();
    private ShellContext context;
    private Predicate<String> available = id -> true;
    private Timeline slide;
    private int selectedIndex = -1;

    public ShellRail(ShellRouter router, MotionService motion, String shortcutPrefix) {
        this.router = router;
        this.motion = motion;
        this.shortcutPrefix = shortcutPrefix;
        getStyleClass().add("byx-rail");
        setAlignment(Pos.TOP_CENTER);
        setMinWidth(68);
        setPrefWidth(68);
        setMaxWidth(68);
        Node logo = logo();
        indicator.getStyleClass().add("byx-rail-indicator");
        indicator.setManaged(false);
        indicator.resizeRelocate(0, BAR_INSET, 3, ITEM_H - 2 * BAR_INSET);
        indicator.setVisible(false);
        indicator.setMouseTransparent(true);
        Pane indicatorLayer = new Pane(indicator);
        indicatorLayer.setMouseTransparent(true);
        indicatorLayer.setPickOnBounds(false);
        items.setAlignment(Pos.TOP_CENTER);
        StackPane itemsStack = new StackPane(items, indicatorLayer);
        StackPane.setAlignment(items, Pos.TOP_CENTER);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        settings = item(ShellRoutes.require(ShellRoutes.SETTINGS), "Settings · " + shortcutPrefix + ",");
        settingsWrap = new StackPane(settings, bar());
        getChildren().addAll(logo, itemsStack, spacer, settingsWrap);
        VBox.setMargin(logo, new javafx.geometry.Insets(0, 0, 16, 0));
    }

    /** Quais rotas existem para esta sessão (ex.: Research só para admin). */
    public void setAvailable(Predicate<String> available) {
        this.available = available;
        if (context != null) {
            ShellContext c = context;
            context = null;
            showContext(c);
        }
    }

    /** Aplica a rota atual (vinda do roteador). */
    public void select(String route) {
        ShellContext c = ShellRoutes.contextOf(route);
        boolean sameContext = c == context;
        if (!sameContext) {
            showContext(c);
        }
        List<ShellRoutes.Route> rail = railRoutes();
        int index = -1;
        for (int i = 0; i < rail.size(); i++) {
            boolean on = rail.get(i).id().equals(route);
            buttons.get(i).pseudoClassStateChanged(SELECTED, on);
            if (on) {
                index = i;
            }
        }
        boolean settingsOn = ShellRoutes.SETTINGS.equals(route);
        settings.pseudoClassStateChanged(SELECTED, settingsOn);
        settingsWrap.getChildren().get(1).setVisible(settingsOn);
        moveIndicator(index, sameContext && selectedIndex >= 0);
        selectedIndex = index;
    }

    public ShellContext context() {
        return context;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public double indicatorY() {
        return indicator.getTranslateY();
    }

    /** Alvo de posição do indicador para o índice (determinístico, independente de layout). */
    static double indicatorTarget(int index) {
        return index * (ITEM_H + GAP);
    }

    public List<Button> itemButtons() {
        return List.copyOf(buttons);
    }

    public Button settingsButton() {
        return settings;
    }

    private List<ShellRoutes.Route> railRoutes() {
        return ShellRoutes.rail(context).stream().filter(r -> available.test(r.id())).toList();
    }

    private void showContext(ShellContext c) {
        context = c;
        stopSlide();
        buttons.clear();
        items.getChildren().clear();
        List<ShellRoutes.Route> rail = railRoutes();
        for (int i = 0; i < rail.size(); i++) {
            ShellRoutes.Route r = rail.get(i);
            Button b = item(r, r.title() + " · " + shortcutPrefix + (i + 1));
            buttons.add(b);
            items.getChildren().add(b);
        }
        selectedIndex = -1;
    }

    private Button item(ShellRoutes.Route r, String tip) {
        Label label = railLabel(r.railLabel());
        VBox graphic = new VBox(4, ByxIcon.path(ShellIcons.path(r.icon()), 22, null), label);
        graphic.setAlignment(Pos.CENTER);
        graphic.setMinWidth(Region.USE_PREF_SIZE);
        Button b = new Button();
        b.setGraphic(graphic);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("byx-rail-item");
        b.setMinSize(ITEM_W, ITEM_H);
        b.setPrefSize(ITEM_W, ITEM_H);
        b.setMaxSize(ITEM_W, ITEM_H);
        b.setAccessibleText(r.title());
        b.setTooltip(tooltip(tip));
        b.setOnAction(e -> router.request(r.id()));
        b.getProperties().put("route", r.id());
        return b;
    }

    /** Destino sem tela ainda: desabilitado, com o motivo no tooltip (o tooltip fica no invólucro). */
    private Node pending(ShellRoutes.Pending p) {
        Label label = railLabel(p.label());
        VBox graphic = new VBox(4, ByxIcon.path(ShellIcons.path(p.icon()), 22, null), label);
        graphic.setAlignment(Pos.CENTER);
        graphic.setMinWidth(Region.USE_PREF_SIZE);
        Button b = new Button();
        b.setGraphic(graphic);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().addAll("byx-rail-item", "pending");
        b.setMinSize(ITEM_W, ITEM_H);
        b.setPrefSize(ITEM_W, ITEM_H);
        b.setMaxSize(ITEM_W, ITEM_H);
        b.setDisable(true);
        b.setAccessibleText(p.label() + ", " + p.reason());
        StackPane wrap = new StackPane(b);
        wrap.setMaxSize(ITEM_W, ITEM_H);
        Tooltip.install(wrap, tooltip(p.label() + " · " + p.reason()));
        return wrap;
    }

    /** Rótulo 11 px nunca reticenciado: como na referência, palavras longas (Hypotheses) passam do item centralizadas. */
    private static Label railLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("byx-rail-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static Tooltip tooltip(String text) {
        Tooltip t = new Tooltip(text);
        t.getStyleClass().add("byx-tooltip");
        t.setShowDelay(Duration.millis(300)); // tokens.tooltip.delayMs
        return t;
    }

    private Region bar() {
        Region r = new Region();
        r.getStyleClass().add("byx-rail-indicator");
        r.setManaged(false);
        r.resizeRelocate(0, BAR_INSET, 3, ITEM_H - 2 * BAR_INSET);
        r.setVisible(false);
        return r;
    }

    private Node logo() {
        Pane p = new Pane();
        p.getStyleClass().add("byx-logo");
        double s = 32 / 24.0;
        double[][] bars = {{2, 3, 20}, {2, 10, 14}, {2, 17, 8}};
        for (double[] b : bars) {
            Rectangle r = new Rectangle(b[0] * s, b[1] * s, b[2] * s, 5 * s);
            r.setArcWidth(5 * s);
            r.setArcHeight(5 * s);
            r.getStyleClass().add("byx-logo-bar");
            p.getChildren().add(r);
        }
        p.setMinSize(32, 32);
        p.setPrefSize(32, 32);
        p.setMaxSize(32, 32);
        p.setAccessibleText("BYX-MVP");
        return p;
    }

    private void moveIndicator(int index, boolean animate) {
        stopSlide();
        if (index < 0) {
            indicator.setVisible(false);
            return;
        }
        indicator.setVisible(true);
        double target = indicatorTarget(index);
        Duration d = !animate || motion == null || !motion.translateAllowed() ? Duration.ZERO : motion.duration("railIndicator");
        if (d.equals(Duration.ZERO)) {
            indicator.setTranslateY(target);
            return;
        }
        // retoma do ponto atual: um pedido novo no meio do caminho só muda o alvo
        slide = new Timeline(new KeyFrame(d, new KeyValue(indicator.translateYProperty(), target, motion.easing("railIndicator"))));
        slide.setOnFinished(e -> slide = null);
        slide.play();
    }

    private void stopSlide() {
        if (slide != null) {
            slide.stop();
            slide = null;
        }
    }

    public boolean sliding() {
        return slide != null;
    }

    public void dispose() {
        stopSlide();
    }
}
