package panel.ui;

import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;

public class ExecutionView extends PageView {
    public ExecutionView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        page.getChildren().add(Ui.pageHeader("Execution Simulator", "Signal edge and execution cost are different things", Ui.badge("NOT OPERATIONAL", "muted")));
        HBox split = new HBox(14,
                Ui.card("Signal edge", Ui.label("Does the feature predict the mid price?", "muted"), Ui.kv("Source", "Hypotheses · Pure Mid labels"), Ui.kv("Value", "N/A"), Ui.emptyState("Pending hypothesis results")),
                Ui.card("Execution cost", Ui.label("Can the edge survive trading costs?", "muted"), Ui.kv("Source", "Execution model"), Ui.kv("Value", "N/A"), Ui.emptyState("Pending execution model")));
        split.getChildren().forEach(n -> HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS));
        page.getChildren().add(split);
        FlowPane flow = new FlowPane(14, 14);
        flow.getChildren().addAll(
                model("TAKER", "fees", "spread", "slippage", "latency", "depth", "notional"),
                model("MAKER", "fees / rebate", "fill assumptions", "queue model", "adverse selection", "cancel latency", "partial fills", "markout"));
        flow.getChildren().forEach(n -> ((VBox) n).setPrefWidth(420));
        page.getChildren().add(flow);
    }

    private static VBox model(String title, String... params) {
        VBox c = Ui.card(title);
        for (String p : params) {
            c.getChildren().add(Ui.kv(p, "N/A"));
        }
        return c;
    }
}
