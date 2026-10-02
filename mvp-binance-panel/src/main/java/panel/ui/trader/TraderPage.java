package panel.ui.trader;

import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;
import panel.ui.PageView;

/** Base das telas do Trader: recebe só o TraderSnapshot operacional. */
public abstract class TraderPage extends PageView {
    protected TraderPage(AppContext ctx) {
        super(ctx);
        // páginas do trader usam o estado operacional, nunca detalhes de pesquisa
    }

    protected abstract void build(TraderSnapshot t, VBox page);

    @Override
    protected void build(Snapshot s, VBox page) {
        build(ctx.trading.snapshot.get(), page);
    }
}
