package panel.systemview;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxOverlayHost;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.v2.Kit;

/**
 * Onboarding V2: diálogo persistente (camada 70) de seis passos (Welcome, Primary workspace, Security, Environment, Interface,
 * Finish). Controles Back, Next, Skip, Finish; Esc = Skip; o fundo não fecha. Só guarda o workspace principal e a conclusão:
 * NUNCA liga trading, conecta carteira, gera chave, ativa DEVNET, abre Validation, mexe no Final Holdout ou move fundos (o diálogo
 * nem tem acesso a esses serviços). A troca de passo é imediata e determinística: cinco Next rápidos terminam no passo 6 com um
 * único passo ativo; o fade é só apresentação.
 */
public final class OnboardingDialog extends VBox {
    /** workspace: TRADING, RESEARCH ou BYX ("All" vira TRADING); completed=false quando foi pulado. */
    public record Result(String workspace, boolean completed) {
    }

    private final OnboardingContent content;
    private final MotionService motion;
    private final Consumer<Result> onDone;
    private final Label counter = Fx.label("", "byx-desk-t3");
    private final VBox body = new VBox(10);
    private final ByxButton back;
    private final ByxButton skip;
    private final ByxButton next;
    private final List<Region> dots = new ArrayList<>();
    private final ToggleGroup choice = new ToggleGroup();
    private int step;
    private String workspace;
    private boolean done;

    public OnboardingDialog(MotionService motion, OnboardingContent content, String initialWorkspace, Consumer<Result> onDone) {
        super(14);
        this.motion = motion;
        this.content = content;
        this.onDone = onDone;
        this.workspace = initialWorkspace == null ? "TRADING" : initialWorkspace;
        getStyleClass().add("byx-dialog");
        setPrefWidth(560);
        setMaxSize(560, Region.USE_PREF_SIZE);
        setAccessibleRole(javafx.scene.AccessibleRole.DIALOG);
        setAccessibleText("Onboarding");
        setId("onboarding");
        HBox dotRow = new HBox(6);
        for (int i = 0; i < content.steps().size(); i++) {
            Region d = new Region();
            d.getStyleClass().add("byx-step-dot");
            dots.add(d);
            dotRow.getChildren().add(d);
        }
        dotRow.setAlignment(Pos.CENTER_LEFT);
        back = new ByxButton("Back", ByxButton.Variant.SECONDARY, motion);
        skip = new ByxButton("Skip", ByxButton.Variant.GHOST, motion);
        next = new ByxButton("Next", ByxButton.Variant.PRIMARY, motion);
        back.setOnAction(e -> go(step - 1));
        next.setOnAction(e -> {
            if (step == content.steps().size() - 1) {
                finish(true);
            } else {
                go(step + 1);
            }
        });
        skip.setOnAction(e -> finish(false));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(8, skip, sp, back, next);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(new HBox(12, dotRow, Fx.spacer(), counter, ByxBadge.data(ByxBadge.Data.PLACEHOLDER_CONTENT)), body, actions);
        counter.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                finish(false);
                e.consume();
            }
        });
        render();
    }

    public int step() {
        return step;
    }

    public String workspace() {
        return workspace;
    }

    public boolean finished() {
        return done;
    }

    public int activeDots() {
        return (int) dots.stream().filter(d -> d.getStyleClass().contains("on")).count();
    }

    public ByxButton nextButton() {
        return next;
    }

    public ByxButton backButton() {
        return back;
    }

    public ByxButton skipButton() {
        return skip;
    }

    public void go(int target) {
        if (done) {
            return;
        }
        int previous = step;
        step = Math.max(0, Math.min(content.steps().size() - 1, target));
        render();
        if (step != previous) {
            // onboardingStepEnter: direção +1 ao avançar, -1 ao voltar (REDUCED/OFF: sem deslocamento, via MotionService)
            motion.fadeSlideIn(body, 16 * (step > previous ? 1 : -1), 0, motion.duration("onboardingStepEnter"));
        }
    }

    private void finish(boolean completed) {
        if (done) {
            return;
        }
        done = true;
        onDone.accept(new Result(workspace, completed));
    }

    private void render() {
        OnboardingContent.Step s = content.steps().get(step);
        int n = content.steps().size();
        counter.setText("Step " + (step + 1) + " of " + n);
        counter.setAccessibleText("Step " + (step + 1) + " of " + n + ": " + s.title());
        for (int i = 0; i < dots.size(); i++) {
            Fx.cls(dots.get(i), "on", i == step);
        }
        body.getChildren().setAll(Fx.label(s.title(), "byx-section-title"), Kit.muted(s.body()));
        switch (s.id()) {
            case "workspace" -> {
                for (OnboardingContent.Workspace w : content.workspaces()) {
                    ToggleButton b = new ToggleButton(w.label() + " · " + w.text());
                    b.getStyleClass().add("byx-choice");
                    b.setMaxWidth(Double.MAX_VALUE);
                    b.setWrapText(true);
                    b.setMinHeight(Region.USE_PREF_SIZE);
                    b.setToggleGroup(choice);
                    String value = w.id().equals("all") ? "TRADING" : w.id().toUpperCase(java.util.Locale.ROOT);
                    b.setSelected(workspace.equals(value) && !w.id().equals("all"));
                    b.setOnAction(e -> workspace = value);
                    b.setAccessibleText(w.label() + ". " + w.text());
                    body.getChildren().add(b);
                }
            }
            case "environment" -> content.environments().forEach(p -> body.getChildren().add(row(p)));
            case "tour" -> content.tour().forEach(p -> body.getChildren().add(row(p)));
            case "complete" -> body.getChildren().add(Kit.row("Primary workspace", workspace.charAt(0) + workspace.substring(1).toLowerCase(java.util.Locale.ROOT), false));
            default -> { }
        }
        back.setDisable(step == 0);
        Fx.shown(skip, step < n - 1);
        next.setText(step == n - 1 ? "Finish" : "Next");
    }

    private static HBox row(OnboardingContent.Pair p) {
        HBox h = new HBox(12, ByxBadge.of(p.key(), ByxBadge.Tone.NEUTRAL), Kit.muted(p.text()));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Abre como diálogo persistente; o resultado fecha o diálogo e devolve o foco ao abridor. */
    public static ByxOverlayHost.DialogHandle open(ByxOverlayHost host, MotionService motion, String initialWorkspace, Consumer<Result> onDone) {
        OnboardingDialog[] ref = new OnboardingDialog[1];
        ByxOverlayHost.DialogHandle[] handle = new ByxOverlayHost.DialogHandle[1];
        ref[0] = new OnboardingDialog(motion, OnboardingContent.load(), initialWorkspace, r -> {
            handle[0].close();
            onDone.accept(r);
        });
        handle[0] = host.openDialog(ref[0], true, ref[0].next, null);
        return handle[0];
    }
}
