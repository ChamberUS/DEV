package panel.helpview;

import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/** Product overview V2: os três pilares com navegação real. Os estados são conceituais; os valores vivos ficam em cada workspace. */
public final class OverviewScreen implements View {
    private final ScrollPane scroll;

    public OverviewScreen(MotionService motion, Consumer<String> navigate) {
        HBox cards = new HBox(14, pillar(motion, navigate, "TRADING", "Trading", "Market observation and trading workspace.",
                new String[] {"ETHUSDT market from Binance USD-M Futures", "Order book, trades and chart", "Bot state, risk and data freshness"},
                new String[] {"LIVE TRADING OFF", "MARKET DATA WAITING"}, "Open Trading", "t-desk"),
                pillar(motion, navigate, "RESEARCH", "Research", "Scientific pipeline and experiment environment.",
                        new String[] {"Capture, datasets, features and labels", "Hypotheses on TRAIN", "Research guard for VALIDATION and FINAL_HOLDOUT"},
                        new String[] {"TRAIN OPEN", "VALIDATION LOCKED", "FINAL HOLDOUT SEALED"}, "Open Research", "overview"),
                pillar(motion, navigate, "BYX", "BYX", "Network, wallet, benefits and treasury ecosystem.",
                        new String[] {"Network health on LOCALNET", "Watch and verified wallets", "Benefits tiers and treasury, TEST only"},
                        new String[] {"LOCALNET", "TEST ASSETS ONLY"}, "Open BYX", "t-byx"));
        cards.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        VBox notes = new VBox(14, Kit.panel("Research gates Trading", Kit.muted("A strategy reaches live trading only after research validation. The interface never skips this.")),
                Kit.panel("BYX never grants access", Kit.muted("Benefits never unlock admin, validation, final holdout or unapproved live trading.")),
                Kit.panel("One shell", Kit.muted("Switch workspaces from the top bar. The rail, search and status dock stay the same.")));
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Product overview", "What each workspace is for and where it stands. States here are conceptual; live values are on each workspace."), cards, notes);
        scroll = Kit.scroll(page);
    }

    private static Node pillar(MotionService motion, Consumer<String> navigate, String kicker, String title, String text, String[] points, String[] states,
            String open, String route) {
        VBox v = new VBox(8, Kit.label(kicker), Fx.label(title, "byx-section-title"), Kit.muted(text));
        for (String p : points) {
            v.getChildren().add(Kit.muted("· " + p));
        }
        v.getChildren().add(Kit.label("Current state (conceptual)"));
        FlowPane badges = new FlowPane(6, 6);
        for (String s : states) {
            badges.getChildren().add(ByxBadge.of(s, ByxBadge.Tone.NEUTRAL));
        }
        ByxButton b = new ByxButton(open, ByxButton.Variant.PRIMARY, motion);
        b.setOnAction(e -> navigate.accept(route));
        v.getChildren().addAll(badges, b);
        v.getStyleClass().addAll("byx-panel", "byx-pillar");
        v.setMaxWidth(Double.MAX_VALUE);
        v.setPrefWidth(1);
        return v;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
