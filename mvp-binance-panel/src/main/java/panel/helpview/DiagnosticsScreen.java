package panel.helpview;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Diagnostics V2: o relatório sai de uma allow-list ({@link DiagnosticsReport}); o texto copiado é exatamente o preview.
 * Nada é lido de senha, token, OTP, segredo de sessão, chave, seed ou header de autorização. Reload só na tela visível.
 */
public final class DiagnosticsScreen implements View {
    private final Supplier<DiagnosticsReport> source;
    private final ScrollPane scroll;
    private final VBox rows = new VBox(0);
    private final Label preview = Fx.label("", "byx-code");
    private final ByxButton copyButton;
    private DiagnosticsReport current;

    public DiagnosticsScreen(MotionService motion, Supplier<DiagnosticsReport> source, Consumer<String> copy) {
        this.source = source;
        copyButton = new ByxButton("Copy diagnostics", ByxButton.Variant.PRIMARY, motion);
        copyButton.setOnAction(e -> {
            if (current != null) {
                copy.accept(current.text());
                copyButton.setText("Copied");
                javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(1500));
                p.setOnFinished(x -> copyButton.setText("Copy diagnostics"));
                p.play();
            }
        });
        ByxButton refresh = new ByxButton("Refresh", ByxButton.Variant.SECONDARY, motion);
        refresh.setOnAction(e -> load());
        preview.setWrapText(true);
        preview.setMaxWidth(Double.MAX_VALUE);
        VBox report = Kit.panel("System report", rows);
        report.setId("diagnostics-report");
        VBox copied = Kit.panel(null, Fx.label("What gets copied", "byx-section-title-sm"), preview, new HBox(8, copyButton, refresh));
        copied.setId("diagnostics-preview");
        VBox never = Kit.panel("Never included");
        for (String n : DiagnosticsReport.NEVER) {
            never.getChildren().add(Kit.muted("· " + n));
        }
        VBox left = new VBox(14, report, never);
        GridPane grid = new GridPane();
        grid.setHgap(14);
        javafx.scene.layout.ColumnConstraints a = new javafx.scene.layout.ColumnConstraints();
        a.setPercentWidth(50);
        javafx.scene.layout.ColumnConstraints b = new javafx.scene.layout.ColumnConstraints();
        b.setPercentWidth(50);
        grid.getColumnConstraints().addAll(a, b);
        grid.add(left, 0, 0);
        grid.add(copied, 1, 0);
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Diagnostics", "Application, services and environment. Values are read from the running app.",
                ByxBadge.of("ALLOW-LIST ONLY", ByxBadge.Tone.NEUTRAL)), grid);
        scroll = Kit.scroll(page);
        load();
    }

    String previewText() {
        return preview.getText();
    }

    DiagnosticsReport report() {
        return current;
    }

    private void load() {
        current = source.get();
        rows.getChildren().clear();
        for (String[] r : current.rows()) {
            rows.getChildren().add(Kit.row(r[0], r[1], true));
        }
        preview.setText(current.text());
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        load();
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
