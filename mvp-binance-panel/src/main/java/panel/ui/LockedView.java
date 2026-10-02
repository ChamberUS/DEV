package panel.ui;

import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;

/** Tela bloqueada (Validation / Paper / Live): apenas explica os requisitos; sem ações. */
public class LockedView extends PageView {
    private final String title;
    private final String subtitle;
    private final String[] requirements;

    public LockedView(AppContext ctx, String title, String subtitle, String... requirements) {
        super(ctx);
        this.title = title;
        this.subtitle = subtitle;
        this.requirements = requirements;
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        page.getChildren().add(Ui.pageHeader(title, subtitle, Ui.badge("🔒 LOCKED", "bad")));
        VBox req = Ui.card("Requirements");
        for (String r : requirements) {
            req.getChildren().add(Ui.kvNode(r, Ui.badge("NOT MET", "bad")));
        }
        VBox c = Ui.card("Methodological barrier", Ui.label("No action is available. Unlocking requires explicit future authorization and a backend change; the panel never offers a shortcut.", "muted"));
        c.setAlignment(Pos.TOP_LEFT);
        page.getChildren().addAll(req, c);
    }
}
