package panel.ui;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Catalog;
import panel.model.HypothesisInfo;
import panel.model.Snapshot;
import panel.util.Fmt;

public class HypothesesView extends PageView {
    private String selected;

    public HypothesesView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        HypothesisInfo sel = s.hypotheses.stream().filter(h -> h.id().equals(selected)).findFirst().orElse(null);
        if (sel != null) {
            detail(sel, s, page);
            return;
        }
        page.getChildren().add(Ui.pageHeader("Hypotheses", "Scientific experiments on Pure Forward Mid labels, TRAIN only"));
        FlowPane flow = new FlowPane(14, 14);
        for (HypothesisInfo h : s.hypotheses) {
            Button run = runButton(s);
            VBox card = Ui.card(h.id(), Ui.label(h.name(), "stage-title"),
                    Ui.kvNode("Status", Ui.stateBadge(h.state())), Ui.kv("Target", h.target()), Ui.kv("Horizons", h.horizons()),
                    Ui.kv("Dataset", h.dataset()), Ui.kv("Results", h.results()), run);
            card.setPrefWidth(300);
            card.getStyleClass().add("clickable");
            Ui.hoverLift(card);
            card.setOnMouseClicked(e -> {
                selected = h.id();
                rebuild(ctx.research.snapshot.get());
            });
            flow.getChildren().add(card);
        }
        page.getChildren().add(flow);
        page.getChildren().add(Ui.card("Signal × horizon heatmap", heatmap(s)));
    }

    private static Button runButton(Snapshot s) {
        Button run = Ui.button("RUN ANALYSIS", "primary");
        run.setDisable(true);
        run.setTooltip(new Tooltip("Not available: the backend has no hypothesis analysis command yet."));
        return run;
    }

    private void detail(HypothesisInfo h, Snapshot s, VBox page) {
        Button back = Ui.button("← Hypotheses", "ghost");
        back.setOnAction(e -> {
            selected = null;
            rebuild(s);
        });
        page.getChildren().add(new HBox(back));
        page.getChildren().add(Ui.pageHeader(h.id() + " · " + h.name(), "Feature: " + h.feature() + " · target: " + h.target(), Ui.stateBadge(h.state()), runButton(s)));
        VBox quant;
        if (h.quantileMeans() != null) {
            quant = Ui.card("Forward mid return by feature quantile", Charts.line("mean fwd return (bps)", List.of("Q1", "Q2", "Q3", "Q4", "Q5"), h.quantileMeans()),
                    Ui.label("MOCK data", "muted"));
        } else {
            quant = Ui.card("Forward mid return by feature quantile", Ui.emptyState("Q1 … Q5 vs future mid return — pending analysis"));
        }
        FlowPane flow = new FlowPane(14, 14);
        flow.getChildren().add(quant);
        for (String t : new String[] {"Signal distribution", "Quantile buckets", "Monotonicity", "Session consistency", "Temporal split", "Bootstrap CI", "Effect size"}) {
            flow.getChildren().add(Ui.card(t, Ui.emptyState("Pending analysis")));
        }
        flow.getChildren().forEach(n -> ((VBox) n).setPrefWidth(n == quant ? 620 : 300));
        page.getChildren().add(flow);
    }

    private GridPane heatmap(Snapshot s) {
        GridPane g = new GridPane();
        g.setHgap(6);
        g.setVgap(6);
        for (int c = 0; c < Catalog.HORIZONS_MS.size(); c++) {
            g.add(Ui.label(Fmt.horizon(Catalog.HORIZONS_MS.get(c)), "muted"), c + 1, 0);
        }
        int r = 1;
        for (HypothesisInfo h : s.hypotheses) {
            g.add(Ui.label(h.feature(), "kv-value"), 0, r);
            for (int c = 0; c < Catalog.HORIZONS_MS.size(); c++) {
                HBox cell = new HBox(Ui.label(Fmt.NA, "muted"));
                cell.setAlignment(Pos.CENTER);
                cell.getStyleClass().add("heat-cell");
                cell.setPrefWidth(70);
                g.add(cell, c + 1, r);
            }
            r++;
        }
        return g;
    }
}
