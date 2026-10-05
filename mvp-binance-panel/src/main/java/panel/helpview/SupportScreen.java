package panel.helpview;

import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxRegion;
import panel.design.RegionState;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Help and Support V2. Opções reais: FAQ, diagnóstico, atalhos. Documentação: NOT AVAILABLE; contato: NOT CONFIGURED.
 * "Report a problem" não tem backend: fica UNAVAILABLE e DEMO ONLY · NOTHING IS SENT, sem formulário que finja envio e
 * sem nada saindo do app. O caminho real é copiar o diagnóstico e enviá-lo manualmente.
 */
public final class SupportScreen implements View {
    private final ScrollPane scroll;
    private final ByxRegion report;

    public SupportScreen(MotionService motion, Consumer<String> navigate, boolean publicMode) {
        VBox options = new VBox(0);
        options.getChildren().add(option(motion, "FAQ", "Answers about workspaces, environments and security.", "Open FAQ", () -> navigate.accept("h-faq"), null));
        options.getChildren().add(option(motion, "Documentation", "Product documentation.", null, null, ByxBadge.availability(ByxBadge.Availability.UNAVAILABLE)));
        options.getChildren().add(option(motion, "Contact support", "Talk to a person.", null, null, ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED)));
        if (!publicMode) {
            options.getChildren().add(option(motion, "System diagnostics", "What the app can tell you about itself, without secrets.", "Open diagnostics", () -> navigate.accept("h-diagnostics"), null));
            options.getChildren().add(option(motion, "Keyboard shortcuts", "Every shortcut that exists in the app.", "Show shortcuts", () -> navigate.accept("h-shortcuts"), null));
        }
        report = new ByxRegion("Report a problem", EnumSet.of(RegionState.UNAVAILABLE), motion);
        report.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("Reporting is not connected",
                "This build has no support endpoint, so a report can not be sent and nothing leaves the app. "
                        + (publicMode ? "Sign in to copy diagnostics and share them manually." : "Open Diagnostics, copy the report and share it manually.")));
        report.setMinHeight(200);
        VBox reportPanel = Kit.panel(null, Kit.titled("Report a problem", ByxBadge.data(ByxBadge.Data.DEMO_ONLY)), report);
        reportPanel.setId("support-report");
        GridPane grid = new GridPane();
        grid.setHgap(14);
        javafx.scene.layout.ColumnConstraints a = new javafx.scene.layout.ColumnConstraints();
        a.setPercentWidth(50);
        javafx.scene.layout.ColumnConstraints b = new javafx.scene.layout.ColumnConstraints();
        b.setPercentWidth(50);
        grid.getColumnConstraints().addAll(a, b);
        grid.add(Kit.panel("Get help", options), 0, 0);
        grid.add(reportPanel, 1, 0);
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Help and support", "Find an answer, check the system or report a problem."), grid);
        scroll = Kit.scroll(page);
    }

    RegionState reportState() {
        return report.state();
    }

    private static Node option(MotionService motion, String title, String text, String action, Runnable run, Node badge) {
        VBox copy = new VBox(2, Fx.label(title, "byx-section-title-sm"), Kit.muted(text));
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(12, copy);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("byx-desk-row");
        if (action != null) {
            ByxButton b = new ByxButton(action, ByxButton.Variant.SECONDARY, motion);
            b.setOnAction(e -> run.run());
            row.getChildren().add(b);
        }
        if (badge != null) {
            row.getChildren().add(badge);
        }
        return row;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    public void dispose() {
        report.dispose();
    }
}
