package panel.helpview;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.model.Snapshot;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/** What's new V2 (leitura): conteúdo local versionado em /content/whats-new.json. O texto atual é um changelog de design (placeholder). */
public final class WhatsNewScreen implements View {
    private final ScrollPane scroll;

    public WhatsNewScreen(HelpContent.WhatsNewContent content) {
        VBox page = Kit.reading(14, 1240);
        page.getChildren().add(Kit.header("What's new", "Changes by version.", ByxBadge.of(content.status(), ByxBadge.Tone.WARNING)));
        for (HelpContent.Release r : content.entries()) {
            VBox panel = Kit.panel(null, Kit.titled(r.version(), Fx.label(r.date(), "byx-desk-t3")));
            for (HelpContent.Change c : r.items()) {
                ByxBadge.Tone tone = switch (c.kind()) {
                    case "NEW" -> ByxBadge.Tone.ACCENT;
                    case "FIXED" -> ByxBadge.Tone.POSITIVE;
                    default -> ByxBadge.Tone.INFO;
                };
                HBox row = new HBox(12, ByxBadge.of(c.kind(), tone), Kit.muted(c.text()));
                row.setAlignment(Pos.TOP_LEFT);
                row.getStyleClass().add("byx-desk-row");
                panel.getChildren().add(row);
            }
            page.getChildren().add(panel);
        }
        scroll = Kit.scroll(page);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
