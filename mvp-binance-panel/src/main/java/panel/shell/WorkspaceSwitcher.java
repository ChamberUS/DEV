package panel.shell;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * Seletor de workspace V2 (Trading | Research | BYX; selecionado: bg3 + sublinhado de 2 px no acento).
 * A seleção vem da rota (ACCOUNT = nenhuma). Clique e setas só pedem navegação. O sublinhado desliza
 * (workspaceChange) só em FULL e é retomado do ponto atual quando interrompido; o conteúdo troca na hora.
 */
public final class WorkspaceSwitcher extends StackPane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final List<ShellContext> ORDER = List.of(ShellContext.TRADING, ShellContext.RESEARCH, ShellContext.BYX);

    private final MotionService motion;
    private final HBox segments = new HBox(0);
    private final Region underline = new Region();
    private final Map<ShellContext, Button> buttons = new EnumMap<>(ShellContext.class);
    private ShellContext selected;
    private boolean animateNext;
    private boolean placed;
    private Timeline slide;
    private double target = Double.NaN;

    public WorkspaceSwitcher(MotionService motion, Consumer<ShellContext> onPick) {
        this.motion = motion;
        getStyleClass().add("byx-switch");
        segments.setAlignment(Pos.CENTER_LEFT);
        for (ShellContext c : ORDER) {
            Button b = new Button(c.label);
            b.getStyleClass().add("byx-switch-seg");
            b.setContentDisplay(ContentDisplay.RIGHT);
            b.setOnAction(e -> onPick.accept(c));
            b.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                    ShellContext n = neighbour(c, e.getCode() == KeyCode.RIGHT ? 1 : -1);
                    if (n != null) {
                        buttons.get(n).requestFocus();
                        onPick.accept(n);
                    }
                    e.consume();
                }
            });
            buttons.put(c, b);
            segments.getChildren().add(b);
        }
        underline.getStyleClass().add("byx-switch-underline");
        underline.setManaged(false);
        underline.setVisible(false);
        underline.setMouseTransparent(true);
        Pane layer = new Pane(underline);
        layer.setMouseTransparent(true);
        layer.setPickOnBounds(false);
        getChildren().addAll(segments, layer);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    private ShellContext neighbour(ShellContext from, int step) {
        List<ShellContext> visible = ORDER.stream().filter(c -> buttons.get(c).isVisible()).toList();
        int i = visible.indexOf(from);
        int j = i + step;
        return j < 0 || j >= visible.size() ? null : visible.get(j);
    }

    /** Workspaces permitidos pela sessão (papéis vêm da autenticação). */
    public void setVisible(ShellContext c, boolean visible) {
        Button b = buttons.get(c);
        b.setVisible(visible);
        b.setManaged(visible);
        requestLayout();
    }

    /** Ícone ao lado do rótulo (ex.: cadeado de Research enquanto a sessão de admin não está verificada). */
    public void setBadge(ShellContext c, Node badge) {
        buttons.get(c).setGraphic(badge);
    }

    public Button button(ShellContext c) {
        return buttons.get(c);
    }

    /** Seleção vinda da rota; null (ACCOUNT) mostra nenhuma. */
    public void select(ShellContext c) {
        ShellContext next = c != null && c.workspace ? c : null;
        animateNext = placed && selected != null && next != null && next != selected;
        selected = next;
        for (ShellContext k : ORDER) {
            buttons.get(k).pseudoClassStateChanged(SELECTED, k == selected);
        }
        requestLayout();
    }

    public ShellContext selected() {
        return selected;
    }

    public boolean sliding() {
        return slide != null;
    }

    public double underlineX() {
        return underline.getTranslateX();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (selected == null) {
            stopSlide();
            underline.setVisible(false);
            placed = false;
            target = Double.NaN;
            return;
        }
        Button b = buttons.get(selected);
        if (b.getWidth() <= 0) {
            return;
        }
        double inset = 4; // acompanha o raio 8 do segmento, como o inset box-shadow da referência
        double x = segments.getLayoutX() + b.getLayoutX() + inset;
        double w = b.getWidth() - 2 * inset;
        double y = segments.getLayoutY() + b.getLayoutY() + b.getHeight() - 2;
        underline.setVisible(true);
        underline.resize(w, 2);
        underline.setLayoutY(y);
        underline.setLayoutX(0);
        if (!Double.isNaN(target) && Math.abs(target - x) < 0.5) {
            animateNext = false; // mesmo alvo: deslize em curso continua, nada a refazer
            placed = true;
            return;
        }
        target = x;
        Duration d = !animateNext || motion == null || !motion.translateAllowed() ? Duration.ZERO : motion.duration("workspaceChange");
        animateNext = false;
        stopSlide(); // novo alvo: retoma do ponto atual (ou salta, em REDUCED/OFF e em resize)
        if (d.equals(Duration.ZERO)) {
            underline.setTranslateX(x);
        } else {
            slide = new Timeline(new KeyFrame(d, new KeyValue(underline.translateXProperty(), x, motion.easing("workspaceChange"))));
            slide.setOnFinished(e -> slide = null);
            slide.play();
        }
        placed = true;
    }

    private void stopSlide() {
        if (slide != null) {
            slide.stop();
            slide = null;
        }
    }

    public void dispose() {
        stopSlide();
    }
}
