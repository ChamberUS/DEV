package panel.authview;

import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.motion.MotionService;

/**
 * AuthShell V2: região de marca (flex) + painel de formulário. Painel 520/560/640, padding 64/64/96 e formulário
 * 392/432/448 em 1440/1600/1920 (handoff §3). Topo: nome do passo e selo de ambiente real; rodapé: links públicos.
 * Trocar o conteúdo usa authTransition (FULL: fade + 8 px com stagger 30 ms; REDUCED: só fade; OFF: na hora).
 */
public final class AuthLayout extends HBox {
    public enum Breakpoint {
        COMPACT(520, 64, 392), STANDARD(560, 64, 432), EXPANDED(640, 96, 448);

        public final double pane;
        public final double padX;
        public final double form;

        Breakpoint(double pane, double padX, double form) {
            this.pane = pane;
            this.padX = padX;
            this.form = form;
        }

        public static Breakpoint of(double width) {
            return width >= 1920 ? EXPANDED : width >= 1600 ? STANDARD : COMPACT;
        }
    }

    private final MotionService motion;
    private final BrandPanel brand;
    private final StackPane pane = new StackPane();
    private final Label step = new Label();
    private final HBox top;
    private final VBox formHost = new VBox();
    private final HBox footer;
    private Breakpoint breakpoint;
    private ParallelTransition swap;

    /** devBadge: selo real do ambiente (ex.: provedor de autenticação de desenvolvimento); null = nenhum. */
    public AuthLayout(MotionService motion, String devBadge, Runnable about) {
        this(motion, devBadge, route -> about.run());
    }

    /** openPublic: pede ao roteador uma página pública (h-about, h-faq, h-help, h-terms, h-privacy). */
    public AuthLayout(MotionService motion, String devBadge, java.util.function.Consumer<String> openPublic) {
        this.motion = motion;
        getStyleClass().add("byx-auth");
        brand = new BrandPanel(motion);
        HBox.setHgrow(brand, Priority.ALWAYS);
        brand.setMinWidth(0);

        step.getStyleClass().add("byx-auth-step");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        top = new HBox(gap);
        top.getChildren().add(0, step);
        if (devBadge != null) {
            top.getChildren().add(ByxBadge.of(devBadge, ByxBadge.Tone.WARNING));
        }
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("byx-auth-top");

        formHost.getStyleClass().add("byx-auth-form");
        formHost.setFillWidth(true);
        formHost.setMaxHeight(Region.USE_PREF_SIZE);

        footer = new HBox(18);
        for (String[] l : new String[][] {{"About", "h-about"}, {"FAQ", "h-faq"}, {"Help", "h-help"}, {"Terms", "h-terms"}, {"Privacy", "h-privacy"}}) {
            Button b = link(l[0]);
            b.setOnAction(e -> openPublic.accept(l[1]));
            footer.getChildren().add(b);
        }
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("byx-auth-footer");

        // sem isso o HBox ocupa a altura toda do StackPane e centraliza o conteúdo
        top.setMaxHeight(Region.USE_PREF_SIZE);
        footer.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(top, Pos.TOP_LEFT);
        StackPane.setAlignment(footer, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(formHost, Pos.CENTER_LEFT);
        pane.getChildren().addAll(formHost, top, footer);
        pane.getStyleClass().add("byx-auth-pane");
        getChildren().addAll(brand, pane);
        widthProperty().addListener((o, a, w) -> apply(Breakpoint.of(w.doubleValue())));
        apply(Breakpoint.COMPACT);
    }

    private static Button link(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("byx-auth-footer-link");
        return b;
    }

    /** Página pública que ainda não existe (passo 11): visível, desabilitada, sem destino falso. */
    private static Node pending(String text) {
        Button b = link(text);
        b.setDisable(true);
        b.setAccessibleText(text + ", arrives in step 11");
        return b;
    }

    private void apply(Breakpoint bp) {
        if (bp == breakpoint) {
            return;
        }
        breakpoint = bp;
        pane.setMinWidth(bp.pane);
        pane.setPrefWidth(bp.pane);
        pane.setMaxWidth(bp.pane);
        pane.setPadding(new Insets(56, bp.padX, 56, bp.padX));
        StackPane.setMargin(top, new Insets(24 - 56, 0, 0, 0));    // .ptop top 24
        StackPane.setMargin(footer, new Insets(0, 0, 22 - 56, 0)); // .pfoot bottom 22
        formHost.setMaxWidth(bp.form);
        formHost.setPrefWidth(bp.form);
        brand.setExpanded(bp == Breakpoint.EXPANDED);
    }

    public Breakpoint breakpoint() {
        return breakpoint;
    }

    public BrandPanel brand() {
        return brand;
    }

    public VBox formHost() {
        return formHost;
    }

    public Region pane() {
        return pane;
    }

    /** Troca o conteúdo do painel. Só apresentação: quem decide o estado é o roteador/serviço. */
    public void show(String stepName, List<Node> children) {
        step.setText(stepName);
        if (swap != null) {
            swap.stop();
            swap = null;
        }
        formHost.getChildren().setAll(children);
        Duration d = motion.duration("authTransition");
        children.forEach(n -> {
            n.setOpacity(1);
            n.setTranslateY(0);
        });
        if (d.equals(Duration.ZERO)) {
            return;
        }
        ParallelTransition all = new ParallelTransition();
        for (int i = 0; i < children.size(); i++) {
            Node n = children.get(i);
            n.setOpacity(0);
            FadeTransition f = new FadeTransition(d, n);
            f.setFromValue(0);
            f.setToValue(1);
            ParallelTransition one = new ParallelTransition(f);
            if (motion.translateAllowed()) {
                n.setTranslateY(8);
                TranslateTransition tr = new TranslateTransition(d, n);
                tr.setFromY(8);
                tr.setToY(0);
                one.getChildren().add(tr);
            }
            one.setInterpolator(motion.easing("authTransition"));
            one.setDelay(Duration.millis(motion.full() ? Math.min(i, 4) * 30 : 0)); // stagger 30 ms até 4
            all.getChildren().add(one);
        }
        swap = all;
        all.setOnFinished(e -> swap = null);
        all.play();
    }

    public void dispose() {
        if (swap != null) {
            swap.stop();
        }
        brand.dispose();
    }
}
