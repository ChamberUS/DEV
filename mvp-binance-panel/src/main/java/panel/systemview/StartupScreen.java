package panel.systemview;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.tradeview.Fx;
import panel.v2.Kit;

/** Estado de startup mínimo (logo + quatro passos), só para espera real acima de 800 ms. Sem splash longa nem animação própria. */
public final class StartupScreen extends StackPane {
    public StartupScreen(int doneSteps) {
        VBox bars = new VBox(4);
        for (double w : new double[] {30, 20, 12}) {
            Region r = new Region();
            r.getStyleClass().add("byx-ledger");
            r.setPrefSize(w, 7);
            r.setMinSize(w, 7);
            r.setMaxSize(w, 7);
            bars.getChildren().add(r);
        }
        VBox steps = new VBox(6);
        for (int i = 0; i < StartupModel.STEPS.size(); i++) {
            Label l = Fx.label((i < doneSteps ? "✓ " : "· ") + StartupModel.STEPS.get(i), i < doneSteps ? "byx-desk-secondary" : "byx-desk-t3");
            steps.getChildren().add(l);
        }
        VBox box = new VBox(16, bars, Fx.label("BYX-MVP", "byx-section-title"), steps);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(220);
        getChildren().add(box);
        setAlignment(Pos.CENTER);
        getStyleClass().addAll("byx-desk", "byx-screen");
        setId("startup");
        setAccessibleText("Starting BYX-MVP");
    }
}
