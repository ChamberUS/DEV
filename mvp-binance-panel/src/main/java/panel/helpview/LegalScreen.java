package panel.helpview;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.model.Snapshot;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Terms of Use / Privacy V2 (página de leitura). O texto vem de /content/legal.json e permanece LEGAL_PLACEHOLDER: a tela
 * nunca o apresenta como contrato aprovado. O sumário leva à seção; nenhum texto jurídico está no código.
 */
public final class LegalScreen implements View {
    private final ScrollPane scroll;
    private final List<Node> sectionNodes = new ArrayList<>();
    private final VBox page = Kit.reading(14, 1240);

    public LegalScreen(String title, HelpContent.LegalContent content, boolean terms) {
        List<HelpContent.Section> sections = terms ? content.terms() : content.privacy();
        VBox toc = new VBox(2);
        toc.setMinWidth(240);
        toc.setMaxWidth(240);
        VBox body = new VBox(14);
        HBox.setHgrow(body, Priority.ALWAYS);
        int n = 1;
        for (HelpContent.Section s : sections) {
            Label head = Fx.label(n + ". " + s.title(), "byx-section-title-sm");
            head.setId("sec-" + s.id());
            VBox sec = Kit.panel(null, head, Kit.muted(s.body()));
            sectionNodes.add(sec);
            body.getChildren().add(sec);
            Button link = new Button(n + ". " + s.title());
            link.getStyleClass().add("byx-toc-link");
            link.setMaxWidth(Double.MAX_VALUE);
            int index = n - 1;
            link.setOnAction(e -> scrollTo(index));
            toc.getChildren().add(link);
            n++;
        }
        ByxBanner banner = new ByxBanner(ByxBanner.Kind.WARNING, "LEGAL PLACEHOLDER",
                "This is not an approved legal document. The text will be provided by counsel. Version " + content.version() + " · " + content.updated() + ".");
        page.getChildren().addAll(Kit.header(title, "Version " + content.version() + " · updated " + content.updated(), ByxBadge.data(ByxBadge.Data.LEGAL_PLACEHOLDER)),
                banner, new HBox(24, toc, body));
        scroll = Kit.scroll(page);
    }

    int sectionCount() {
        return sectionNodes.size();
    }

    void scrollTo(int index) {
        if (index < 0 || index >= sectionNodes.size()) {
            return;
        }
        scroll.applyCss();
        page.layout();
        double y = sectionNodes.get(index).getBoundsInParent().getMinY();
        double total = page.getHeight() - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(total <= 0 ? 0 : Math.max(0, Math.min(1, (y + 70) / total))); // sem animação de rolagem
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
